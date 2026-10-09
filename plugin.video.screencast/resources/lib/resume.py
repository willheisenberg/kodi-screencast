"""Merkt sich, was vor einem Screencast lief, um es danach fortzusetzen.

Ohne Kodi-Abhängigkeit: Der Dienst meldet in kurzen Abständen, was gerade
spielt, und bekommt zurück, ob etwas fortzusetzen ist.
"""

# So lange darf zwischen dem Ende der alten Wiedergabe und dem Beginn des
# Screencasts liegen (Kodi braucht ein paar Sekunden, um den Stream zu öffnen).
HANDOVER_SECONDS = 8.0
# Ein Stück vor der Unterbrechung wieder einsetzen.
REWIND_SECONDS = 3.0

# Kodi öffnet Einträge aus der Bibliothek über ihre Kennung; nur so bleiben
# Titel, Gesehen-Status und Ähnliches erhalten.
ID_KEYS = {
    "movie": "movieid",
    "episode": "episodeid",
    "musicvideo": "musicvideoid",
    "song": "songid",
    "channel": "channelid",
}


class Interruption:
    def __init__(self):
        self._last = None  # (Zeitpunkt, Wiedergabe) der letzten fremden Wiedergabe
        self._interrupted = None
        self._casting = False

    def forget(self):
        """Die laufende Wiedergabe wurde gestoppt oder ist zu Ende.

        Kodi meldet das nur, wenn wirklich gestoppt wurde, nicht wenn ein
        Screencast die Wiedergabe verdrängt. Ein danach gestarteter Screencast
        hat also nichts unterbrochen.
        """
        self._last = None

    def observe(self, playback, casting, now):
        """Nimmt den aktuellen Stand auf.

        playback: Beschreibung der laufenden fremden Wiedergabe oder None.
        casting: Es läuft gerade ein Screencast.
        Gibt die Wiedergabe zurück, die jetzt fortzusetzen ist, sonst None.
        """
        if casting:
            if not self._casting:
                self._casting = True
                recent = self._last and now - self._last[0] <= HANDOVER_SECONDS
                self._interrupted = self._last[1] if recent else None
            self._last = None
            return None

        was_casting, self._casting = self._casting, False
        if playback:
            # Während oder nach dem Screencast wurde etwas anderes gestartet.
            self._last = (now, playback)
            self._interrupted = None
            return None
        if was_casting:
            interrupted, self._interrupted = self._interrupted, None
            return interrupted
        return None


def open_params(playback):
    """Parameter für Player.Open, die die Wiedergabe an alter Stelle fortsetzen.

    playback: {"item": Antwort von Player.GetItem, "position": Sekunden,
               "live": bool}. Gibt None zurück, wenn sich nichts öffnen lässt.
    """
    item = playback.get("item") or {}
    key = ID_KEYS.get(item.get("type"))
    if key and item.get("id", -1) >= 0:
        target = {key: item["id"]}
    elif item.get("file"):
        target = {"file": item["file"]}
    else:
        return None

    params = {"item": target}
    if not playback.get("live") and item.get("type") != "channel":
        seconds = max(0.0, playback.get("position", 0.0) - REWIND_SECONDS)
        whole = int(seconds)
        params["options"] = {"resume": {
            "hours": whole // 3600,
            "minutes": whole % 3600 // 60,
            "seconds": whole % 60,
            "milliseconds": int((seconds - whole) * 1000),
        }}
    return params
