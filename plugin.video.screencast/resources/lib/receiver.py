"""Ton-Empfänger: nimmt rohes PCM per UDP an und gibt es über aplay aus.

Läuft als eigener Prozess neben Kodi, damit der Ton nicht durch Kodis Player
und dessen Puffer muss. Aufruf: receiver.py PORT VERZÖGERUNG_MS ALSA_GERÄT [SENDER]

Mit SENDER (IP-Adresse) zählen nur dessen Pakete; der Ton eines zweiten
Geräts, das an denselben Port sendet, mischt sich dann nicht dazu.
"""

import collections
import fcntl
import os
import select
import signal
import socket
import subprocess
import sys
import time

FORMAT = ["-f", "S16_LE", "-r", "48000", "-c", "2"]
BUFFER_US = 60000  # Puffer der Soundkarte
PIPE_BYTES = 4096  # kleinste Pipe, damit sich davor kein Ton staut
MAX_LATE = 0.06  # mehr Rückstand als das wird verworfen
IDLE_EXIT = 5.0  # ohne Pakete ist die Übertragung vorbei
APLAY_RETRIES = 20


class Delay:
    """Hält Pakete eine feste Zeit zurück und verwirft, was zu spät dran ist.

    Der Rückstand entsteht, wenn die Soundkarte langsamer läuft als der
    Rechner aufnimmt; ohne Verwerfen würde der Ton immer weiter nachhängen.
    """

    def __init__(self, delay, max_late=MAX_LATE):
        self.delay = delay
        self.max_late = max_late
        self.dropped = 0
        self._queue = collections.deque()

    def push(self, data, now):
        self._queue.append((now + self.delay, data))

    def pop(self, now):
        while self._queue and now - self._queue[0][0] > self.max_late:
            self._queue.popleft()
            self.dropped += 1
        if self._queue and self._queue[0][0] <= now:
            return self._queue.popleft()[1]
        return None

    def __len__(self):
        return len(self._queue)


def open_aplay(device):
    aplay = subprocess.Popen(
        ["aplay", "-q", "-D", device, *FORMAT, f"--buffer-time={BUFFER_US}"],
        stdin=subprocess.PIPE,
    )
    fd = aplay.stdin.fileno()
    try:
        fcntl.fcntl(fd, fcntl.F_SETPIPE_SZ, PIPE_BYTES)
    except (AttributeError, OSError):
        pass
    os.set_blocking(fd, False)
    return aplay


def run(port, delay_ms, device, source=""):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("", port))
    sock.setblocking(False)

    running = [True]
    signal.signal(signal.SIGTERM, lambda *_: running.__setitem__(0, False))

    aplay = open_aplay(device)
    retries = APLAY_RETRIES
    delay = Delay(delay_ms / 1000)
    pending = b""
    last_packet = time.monotonic()

    while running[0]:
        busy = pending or len(delay)
        readable, _, _ = select.select([sock], [], [], 0.005 if busy else 0.5)
        now = time.monotonic()
        if readable:
            try:
                while True:
                    data, address = sock.recvfrom(65536)
                    if source and address[0] != source:
                        continue
                    delay.push(data, now)
                    last_packet = now
            except BlockingIOError:
                pass
        elif now - last_packet > IDLE_EXIT:
            break

        try:
            while True:
                pending = pending or delay.pop(now)
                if not pending:
                    break
                pending = pending[os.write(aplay.stdin.fileno(), pending):]
        except BlockingIOError:
            pass
        except BrokenPipeError:
            # aplay ist weg, meist weil Kodi das Gerät noch nicht freigegeben hat.
            aplay.wait()
            retries -= 1
            if retries < 0:
                print("aplay lässt sich nicht starten", file=sys.stderr)
                return 1
            time.sleep(0.25)
            aplay, pending = open_aplay(device), b""

    aplay.stdin.close()
    aplay.terminate()
    aplay.wait()
    if delay.dropped:
        print(f"{delay.dropped} Pakete verworfen", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(run(int(sys.argv[1]), int(sys.argv[2]), *sys.argv[3:5]))
