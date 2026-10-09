"""Stream-Adresse und Wiedergabe-Eigenschaften, ohne Kodi-Abhängigkeit."""

import re
from urllib.parse import parse_qsl

ADDON_ID = "plugin.video.screencast"
DEFAULT_PORT = 5004
DEFAULT_AUDIO_DELAY_MS = 350

# Kodi soll den Stream als Live-Quelle behandeln und nicht vorpuffern.
PROPERTIES = {
    "inputstream": "inputstream.ffmpegdirect",
    "inputstream.ffmpegdirect.open_mode": "ffmpeg",
    "inputstream.ffmpegdirect.is_realtime_stream": "true",
}
MIME_TYPE = "video/mp2t"


def _port(params, key, default=None):
    if key not in params:
        return default
    port = int(params[key])
    if not 1024 <= port <= 65535:
        raise ValueError(f"Port {port} liegt außerhalb von 1024-65535")
    return port


def _params(query):
    return dict(parse_qsl(query.partition("?")[2]))


def port_from_query(query):
    return _port(_params(query), "port", DEFAULT_PORT)


def audio_from_query(query):
    """(Port, Verzögerung in ms) des Tons; None, wenn ohne Ton übertragen wird."""
    params = _params(query)
    port = _port(params, "audio_port")
    if port is None:
        return None
    return port, max(0, int(params.get("audio_delay", DEFAULT_AUDIO_DELAY_MS)))


def stream_url(port, audio=None):
    # overrun_nonfatal: bei vollem Empfangspuffer Pakete verwerfen statt abbrechen.
    url = f"udp://@:{port}?overrun_nonfatal=1&fifo_size=50000"
    if audio:
        # ffmpeg übergeht die beiden Angaben; der Ton-Dienst liest sie aus der
        # Adresse des laufenden Streams.
        url += f"&audio_port={audio[0]}&audio_delay={audio[1]}"
    return url


def is_screencast(file):
    """Kodi spielt gerade einen Stream dieses Addons (aufgelöst oder als Plugin-Adresse)."""
    if file.startswith(f"plugin://{ADDON_ID}/"):
        return True
    return file.startswith("udp://@:") and "overrun_nonfatal=1&fifo_size=50000" in file


def audio_of_playing(file):
    """Ton-Angaben, wenn Kodi gerade einen Stream dieses Addons spielt, sonst None."""
    if not is_screencast(file):
        return None
    try:
        return audio_from_query(file)
    except ValueError:
        return None


def alsa_device(kodi_audio_device):
    """ALSA-Gerät derselben Karte, die Kodi laut Einstellung benutzt.

    Kodi nennt z. B. "ALSA:hdmi:CARD=vc4hdmi0,DEV=0|vc4-hdmi-0"; "default"
    nimmt im Gegensatz zu "hdmi" jedes gängige PCM-Format an.
    """
    card = re.search(r"^ALSA:.*?CARD=([^,|]+)", kodi_audio_device)
    return f"default:CARD={card.group(1)}" if card else "default"
