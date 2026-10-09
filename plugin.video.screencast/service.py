"""Begleitet einen Screencast auf der Kodi-Seite.

Kodi bekommt nur das Bild. Der Ton läuft an Kodis Player vorbei direkt auf
die Soundkarte; dafür muss Kodi das Gerät für die Dauer freigeben. Was vor
dem Screencast lief, wird danach an derselben Stelle fortgesetzt.
"""

import json
import os
import socket
import subprocess
import time

import xbmc

from resources.lib import resume, stream

RECEIVER = os.path.join(os.path.dirname(__file__), "resources", "lib", "receiver.py")


def log(message, level=xbmc.LOGINFO):
    xbmc.log(f"[{stream.ADDON_ID}] {message}", level)


def rpc(method, params=None):
    request = {"jsonrpc": "2.0", "id": 1, "method": method}
    if params is not None:
        request["params"] = params
    return json.loads(xbmc.executeJSONRPC(json.dumps(request))).get("result")


def kodi_audio_device():
    result = rpc("Settings.GetSettingValue", {"setting": "audiooutput.audiodevice"})
    return (result or {}).get("value", "")


class Audio:
    def __init__(self):
        self.current = None
        self.process = None

    def switch(self, wanted):
        if wanted == self.current:
            return
        self.stop()
        if wanted:
            port, delay = wanted
            device = stream.alsa_device(kodi_audio_device())
            xbmc.audioSuspend()
            try:
                self.process = subprocess.Popen(
                    ["python3", RECEIVER, str(port), str(delay), device]
                )
            except OSError as error:
                xbmc.audioResume()
                log(f"Ton-Empfänger startet nicht: {error}", xbmc.LOGERROR)
            else:
                log(f"Ton auf Port {port} über {device}, {delay} ms verzögert")
        self.current = wanted

    def stop(self):
        self.current = None
        if self.process is None:
            return
        self.process.terminate()
        try:
            self.process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.process.kill()
        self.process = None
        xbmc.audioResume()
        log("Ton wieder bei Kodi")


def playing_item():
    """Was Kodi gerade spielt, so wie Player.Open es später wieder braucht."""
    for player in rpc("Player.GetActivePlayers") or []:
        answer = rpc("Player.GetItem", {"playerid": player["playerid"], "properties": ["file"]})
        return (answer or {}).get("item")
    return None


def sender_still_sending(port):
    """Der Sender läuft noch, also wurde der Screencast an Kodi beendet.

    Kodi hat den Port dann schon freigegeben; kommen dort weiter Pakete an,
    hat jemand an Kodi auf Stopp gedrückt, und es wird nichts fortgesetzt.
    """
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.bind(("", port))
        probe.settimeout(0.6)
        probe.recv(2048)
        return True
    except OSError:
        return False
    finally:
        probe.close()


class Player(xbmc.Player):
    """Meldet, wenn eine Wiedergabe wirklich endet statt verdrängt zu werden."""

    def __init__(self, interruption):
        super().__init__()
        self.interruption = interruption

    def onPlayBackStopped(self):
        self.interruption.forget()

    def onPlayBackEnded(self):
        self.interruption.forget()

    def onPlayBackError(self):
        self.interruption.forget()


def main():
    monitor = xbmc.Monitor()
    audio = Audio()
    interruption = resume.Interruption()
    player = Player(interruption)
    known_file, known_item = "", None
    cast_port = stream.DEFAULT_PORT
    try:
        while not monitor.waitForAbort(0.25):
            try:
                file = player.getPlayingFile() if player.isPlaying() else ""
                position = player.getTime() if file else 0.0
                live = player.getTotalTime() <= 0 if file else False
            except RuntimeError:
                file, position, live = "", 0.0, False

            casting = stream.is_screencast(file)
            audio.switch(stream.audio_of_playing(file))

            playback = None
            if casting:
                cast_port = stream.port_from_query(file)
            elif file:
                if file != known_file:
                    known_file, known_item = file, playing_item()
                playback = {"item": known_item, "position": position, "live": live}

            interrupted = interruption.observe(playback, casting, time.monotonic())
            if not interrupted:
                continue
            params = resume.open_params(interrupted)
            if params is None or sender_still_sending(cast_port):
                continue
            log(f"Setze fort: {json.dumps(params)}")
            rpc("Player.Open", params)
    finally:
        audio.stop()


main()
