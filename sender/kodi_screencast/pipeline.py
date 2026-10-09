"""Setzt die GStreamer-Pipeline aus Aufnahme, Kodierung und Transport zusammen.

Aufnahme und Encoder sind die plattformabhängigen Teile; ein macOS-Port
ersetzt capture_video(), capture_audio() und encode_video().
"""

from dataclasses import dataclass

# Elemente, die cli.py im laufenden Betrieb anspricht: Das Ventil hält den
# Bildstrom für eine Sendepause zurück, der Encoder bekommt danach die
# Aufforderung zu einem Keyframe.
GATE = "gate"
ENCODER = "encoder"


@dataclass
class Settings:
    host: str
    port: int = 5004
    fps: int = 30
    max_height: int = 1080
    bitrate_kbps: int = 8000
    audio: bool = True
    audio_device: str = ""
    audio_port: int = 5005


def scaled_size(width, height, max_height):
    """Verkleinert auf max_height Zeilen, Seitenverhältnis bleibt, Maße gerade."""
    if height > max_height:
        width = width * max_height / height
        height = max_height
    return int(width) // 2 * 2, int(height) // 2 * 2


def capture_video(fd, node_id, fps):
    # KWin liefert nur bei Bildänderungen Frames; videorate füllt auf eine
    # feste Bildrate auf, damit der Empfänger gleichmäßig versorgt wird.
    return (
        f"pipewiresrc fd={fd} path={node_id} do-timestamp=true keepalive-time=500"
        f" ! videorate ! video/x-raw,framerate={fps}/1"
    )


def encode_video(width, height, fps, bitrate_kbps):
    # Keine B-Frames und ein Keyframe pro Sekunde: geringe Verzögerung, und
    # der Empfänger findet nach Paketverlust schnell wieder ein vollständiges Bild.
    return (
        f"vapostproc ! video/x-raw(memory:VAMemory),format=NV12,width={width},height={height}"
        f" ! vah265enc name={ENCODER} rate-control=cbr bitrate={bitrate_kbps} key-int-max={fps}"
        f" b-frames=0 ref-frames=1 aud=true"
        f" ! h265parse config-interval=-1"
    )


def capture_audio(device):
    return (
        f"pulsesrc device={device} buffer-time=20000 latency-time=5000"
        f" ! audioconvert ! audioresample"
        f" ! audio/x-raw,format=S16LE,rate=48000,channels=2"
    )


def transport(host, port):
    # alignment=7: sieben TS-Pakete (1316 Byte) pro UDP-Datagramm.
    return (
        f"mpegtsmux name=mux alignment=7 latency=0"
        f" ! valve name={GATE}"
        f" ! udpsink host={host} port={port} sync=false async=false"
    )


def audio_transport(host, port):
    # Der Ton geht unkodiert und getrennt vom Bild an den Empfänger des
    # Addons; über Kodis Player käme er über eine Sekunde später an.
    return f"udpsink host={host} port={port} sync=false async=false"


def build(settings, fd, node_id, src_width, src_height):
    width, height = scaled_size(src_width, src_height, settings.max_height)
    parts = [
        transport(settings.host, settings.port),
        capture_video(fd, node_id, settings.fps)
        + " ! " + encode_video(width, height, settings.fps, settings.bitrate_kbps)
        + " ! queue ! mux.",
    ]
    if settings.audio:
        parts.append(
            capture_audio(settings.audio_device)
            + " ! " + audio_transport(settings.host, settings.audio_port)
        )
    return "  ".join(parts)
