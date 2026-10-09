"""Stream-Adresse und Wiedergabe-Eigenschaften, ohne Kodi-Abhängigkeit."""

from urllib.parse import parse_qsl

DEFAULT_PORT = 5004

# Kodi soll den Stream als Live-Quelle behandeln und nicht vorpuffern.
PROPERTIES = {
    "inputstream": "inputstream.ffmpegdirect",
    "inputstream.ffmpegdirect.open_mode": "ffmpeg",
    "inputstream.ffmpegdirect.is_realtime_stream": "true",
}
MIME_TYPE = "video/mp2t"


def port_from_query(query):
    params = dict(parse_qsl(query.lstrip("?")))
    port = int(params.get("port", DEFAULT_PORT))
    if not 1024 <= port <= 65535:
        raise ValueError(f"Port {port} liegt außerhalb von 1024-65535")
    return port


def stream_url(port):
    # overrun_nonfatal: bei vollem Empfangspuffer Pakete verwerfen statt abbrechen.
    return f"udp://@:{port}?overrun_nonfatal=1&fifo_size=50000"
