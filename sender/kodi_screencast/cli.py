"""Kommandozeile: kodi-screencast start | stop"""

import argparse
import json
import os
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

from . import discovery, pipeline
from .kodi import ADDON_ID, Kodi, KodiError

STATE_FILE = Path(
    os.environ.get("XDG_STATE_HOME", Path.home() / ".local/state")
) / "kodi-screencast/state.json"
PID_FILE = Path(os.environ.get("XDG_RUNTIME_DIR", "/tmp")) / "kodi-screencast.pid"


def load_state():
    try:
        return json.loads(STATE_FILE.read_text())
    except (OSError, ValueError):
        return {}


def save_state(state):
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(state))


def find_kodi(args):
    if args.host:
        return args.host, args.rpc_port
    found = discovery.find()
    if not found:
        sys.exit(
            "Kein Kodi im Netz gefunden. Entweder --host angeben oder in Kodi "
            "unter Einstellungen > Dienste Zeroconf und die Fernsteuerung über "
            "HTTP einschalten."
        )
    if len(found) > 1:
        names = ", ".join(f"{name} ({address})" for name, address, _ in found)
        sys.exit(f"Mehrere Kodi-Geräte gefunden: {names}. Bitte --host angeben.")
    _name, address, port = found[0]
    return address, port


def default_monitor():
    """Monitor-Quelle des Standard-Ausgabegeräts, also der Systemton."""
    try:
        sink = subprocess.run(
            ["pactl", "get-default-sink"],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return ""
    return sink + ".monitor" if sink else ""


# Kodi spielt alles ab, was während seines Starts ankommt, und läuft deshalb
# dauerhaft um diese Startzeit (gut 1 s) hinterher. Eine längere Sendepause,
# sobald die Wiedergabe läuft, lässt Kodis Puffer leerlaufen; den Zeitsprung
# danach rechnet Kodi heraus. Das klappt nur, weil der Strom keinen Ton
# enthält: Mit Tonspur füllt Kodi den Puffer von sich aus wieder auf.
CATCH_UP_PAUSE = 2.0
CATCH_UP_AFTER = 1.0  # Sekunden Wiedergabe, bevor die Pause beginnt
CATCH_UP_GIVE_UP = 20.0


def catch_up(pipe, kodi, stream_port, stopped, force_keyframe):
    """Baut Kodis Start-Rückstand mit einer Sendepause ab (läuft im eigenen Thread)."""
    give_up = time.monotonic() + CATCH_UP_GIVE_UP
    while True:
        try:
            played = kodi.playback_time(stream_port)
        except KodiError:
            played = None
        if played is not None and played >= CATCH_UP_AFTER:
            break
        if stopped.wait(0.25) or time.monotonic() > give_up:
            return

    gate = pipe.get_by_name(pipeline.GATE)
    gate.set_property("drop", True)
    stopped.wait(CATCH_UP_PAUSE)
    gate.set_property("drop", False)
    # Der Strom setzt mitten in einer Bildgruppe wieder ein; Kodi braucht ein
    # vollständiges Bild, um weiterzudekodieren.
    force_keyframe(pipe.get_by_name(pipeline.ENCODER))


def start(args):
    # Erst hier importieren, damit `stop` und die Tests ohne GStreamer laufen.
    import gi
    gi.require_version("Gst", "1.0")
    gi.require_version("GstVideo", "1.0")
    from gi.repository import GLib, Gst, GstVideo
    from .portal import PortalError, ScreenCast

    host, rpc_port = find_kodi(args)
    kodi = Kodi(host, rpc_port, args.user, args.password)
    if not kodi.has_addon():
        sys.exit(f"Das Addon {ADDON_ID} ist in Kodi nicht installiert oder deaktiviert.")

    settings = pipeline.Settings(
        host=host, port=args.port, fps=args.fps, max_height=args.height,
        bitrate_kbps=args.bitrate, audio=not args.no_audio,
        audio_port=args.audio_port,
    )
    if settings.audio:
        settings.audio_device = default_monitor()
        if not settings.audio_device:
            print("Kein Ausgabegerät gefunden, übertrage ohne Ton.", file=sys.stderr)
            settings.audio = False

    state = load_state()
    screencast = ScreenCast()
    try:
        stream = screencast.open(state.get("restore_token", ""))
    except PortalError as error:
        sys.exit(f"Bildschirmfreigabe fehlgeschlagen: {error}")
    save_state(dict(state, restore_token=stream.restore_token))

    Gst.init(None)
    pipe = Gst.parse_launch(
        pipeline.build(settings, stream.fd, stream.node_id, stream.width, stream.height)
    )
    loop = GLib.MainLoop()
    failure = []

    def on_message(_bus, message):
        if message.type == Gst.MessageType.ERROR:
            error, _debug = message.parse_error()
            failure.append(error.message)
            loop.quit()
        elif message.type == Gst.MessageType.EOS:
            loop.quit()

    bus = pipe.get_bus()
    bus.add_signal_watch()
    bus.connect("message", on_message)
    for signum in (signal.SIGINT, signal.SIGTERM):
        GLib.unix_signal_add(GLib.PRIORITY_HIGH, signum, loop.quit)

    def force_keyframe(encoder):
        encoder.send_event(
            GstVideo.video_event_new_upstream_force_key_unit(Gst.CLOCK_TIME_NONE, True, 0)
        )

    stopped = threading.Event()
    PID_FILE.write_text(str(os.getpid()))
    try:
        pipe.set_state(Gst.State.PLAYING)
        if settings.audio:
            kodi.play(settings.port, settings.audio_port, args.audio_delay)
        else:
            kodi.play(settings.port)
        threading.Thread(
            target=catch_up, daemon=True,
            args=(pipe, kodi, settings.port, stopped, force_keyframe),
        ).start()
        print(f"Übertrage an {host}. Beenden mit Strg+C oder `kodi-screencast stop`.")
        loop.run()
    finally:
        stopped.set()
        PID_FILE.unlink(missing_ok=True)
        pipe.set_state(Gst.State.NULL)
        screencast.close()
        try:
            kodi.stop(settings.port)
        except KodiError as error:
            print(f"Kodi-Wiedergabe nicht gestoppt: {error}", file=sys.stderr)

    if failure:
        sys.exit(f"Übertragung abgebrochen: {failure[0]}")


def stop(_args):
    try:
        pid = int(PID_FILE.read_text())
        os.kill(pid, signal.SIGTERM)
    except (OSError, ValueError):
        sys.exit("Es läuft keine Übertragung.")


def status(_args):
    """Exit-Code 0, wenn eine Übertragung läuft, sonst 1."""
    try:
        os.kill(int(PID_FILE.read_text()), 0)
    except (OSError, ValueError):
        print("Es läuft keine Übertragung.")
        sys.exit(1)
    print("Übertragung läuft.")


def main(argv=None):
    parser = argparse.ArgumentParser(prog="kodi-screencast")
    commands = parser.add_subparsers(dest="command", required=True)

    start_parser = commands.add_parser("start", help="Bildschirm an Kodi übertragen")
    start_parser.add_argument("--host", help="Adresse von Kodi (sonst Suche im Netz)")
    start_parser.add_argument("--rpc-port", type=int, default=8080,
                              help="HTTP-Port von Kodi, nur mit --host (Standard 8080)")
    start_parser.add_argument("--user", default=os.environ.get("KODI_USER", ""))
    start_parser.add_argument("--password", default=os.environ.get("KODI_PASSWORD", ""))
    start_parser.add_argument("--port", type=int, default=5004, help="UDP-Port für den Stream")
    start_parser.add_argument("--fps", type=int, default=30)
    start_parser.add_argument("--height", type=int, default=1080, help="maximale Bildhöhe")
    start_parser.add_argument("--bitrate", type=int, default=8000, help="Video-Bitrate in kbit/s")
    start_parser.add_argument("--no-audio", action="store_true")
    start_parser.add_argument("--audio-port", type=int, default=5005, help="UDP-Port für den Ton")
    start_parser.add_argument("--audio-delay", type=int, default=350,
                              help="Ton um so viele ms verzögern, damit er zum Bild passt")
    start_parser.set_defaults(run=start)

    commands.add_parser("stop", help="laufende Übertragung beenden").set_defaults(run=stop)
    commands.add_parser("status", help="zeigt, ob übertragen wird").set_defaults(run=status)

    args = parser.parse_args(argv)
    try:
        args.run(args)
    except KodiError as error:
        sys.exit(str(error))


if __name__ == "__main__":
    main()
