"""JSON-RPC-Client für Kodis Fernsteuer-Schnittstelle (HTTP)."""

import base64
import json
import socket
import urllib.error
import urllib.parse
import urllib.request

ADDON_ID = "plugin.video.screencast"


class KodiError(RuntimeError):
    pass


class RpcError(KodiError):
    """Kodi hat den Aufruf angenommen, aber mit einem Fehler beantwortet."""


# Eine Rückfrage vor dem Start gilt statt einer Wiedergabe der Übertragung
# eines anderen Geräts.
OTHER_CAST = object()


def plugin_url(port, audio_port=None, audio_delay_ms=0, source=None):
    url = f"plugin://{ADDON_ID}/?port={port}"
    if source:
        url += f"&source={source}"
    if audio_port:
        url += f"&audio_port={audio_port}&audio_delay={audio_delay_ms}"
    return url


class Kodi:
    def __init__(self, host, port=8080, username="", password="", timeout=5):
        self.host = host
        self._url = f"http://{host}:{port}/jsonrpc"
        self._auth = None
        if username:
            credentials = f"{username}:{password}".encode()
            self._auth = "Basic " + base64.b64encode(credentials).decode()
        self._timeout = timeout

    def call(self, method, params=None):
        body = {"jsonrpc": "2.0", "id": 1, "method": method}
        if params is not None:
            body["params"] = params
        request = urllib.request.Request(
            self._url, json.dumps(body).encode(),
            {"Content-Type": "application/json"},
        )
        if self._auth:
            request.add_header("Authorization", self._auth)
        try:
            with urllib.request.urlopen(request, timeout=self._timeout) as response:
                answer = json.load(response)
        except urllib.error.HTTPError as error:
            if error.code == 401:
                raise KodiError(
                    "Kodi verlangt Benutzername und Passwort (--user/--password "
                    "oder KODI_USER/KODI_PASSWORD)"
                ) from error
            raise KodiError(f"Kodi antwortet mit HTTP {error.code}") from error
        except OSError as error:
            raise KodiError(
                f"Kodi unter {self._url} nicht erreichbar. In Kodi muss unter "
                "Einstellungen > Dienste > Steuerung die Fernsteuerung über HTTP "
                "erlaubt sein."
            ) from error
        if "error" in answer:
            raise RpcError(f"{method}: {answer['error'].get('message', answer['error'])}")
        return answer.get("result")

    def has_addon(self):
        try:
            details = self.call(
                "Addons.GetAddonDetails",
                {"addonid": ADDON_ID, "properties": ["enabled"]},
            )
        except RpcError:
            return False
        return details["addon"]["enabled"]

    def local_address(self):
        """Die eigene IP-Adresse, wie Kodi sie als Absender der Pakete sieht."""
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            probe.connect((self.host, 9))  # legt nur den Weg fest, sendet nichts
            return probe.getsockname()[0]
        except OSError:
            return None
        finally:
            probe.close()

    def play(self, stream_port, audio_port=None, audio_delay_ms=0, source=None):
        url = plugin_url(stream_port, audio_port, audio_delay_ms, source)
        self.call("Player.Open", {"item": {"file": url}})

    def other_playback(self, stream_port, source=None):
        """Was ein Start unterbräche: der Titel der laufenden Wiedergabe,
        OTHER_CAST für die Übertragung eines anderen Geräts, sonst None."""
        for player in self.call("Player.GetActivePlayers"):
            item = self.call(
                "Player.GetItem",
                {"playerid": player["playerid"], "properties": ["file", "title"]},
            )["item"]
            file = item.get("file", "")
            if not is_screencast(file, stream_port):
                return item.get("title") or item.get("label") or file.rsplit("/", 1)[-1]
            # Vor dem Start ist nur ein Strom mit der eigenen Adresse der eigene
            # (ein Rest eines früheren Laufs); einer ohne Absender stammt von
            # einem älteren Sender.
            if source is None or stream_source(file) != source:
                return OTHER_CAST
        return None

    def playback_time(self, stream_port, source=None):
        """Sekunden, die der eigene Stream schon spielt; None, solange er nicht läuft."""
        for player in self.call("Player.GetActivePlayers"):
            item = self.call(
                "Player.GetItem",
                {"playerid": player["playerid"], "properties": ["file"]},
            )["item"]
            if not is_own_stream(item.get("file", ""), stream_port, source):
                continue
            time = self.call(
                "Player.GetProperties",
                {"playerid": player["playerid"], "properties": ["time"]},
            )["time"]
            return (
                time["hours"] * 3600 + time["minutes"] * 60 + time["seconds"]
                + time["milliseconds"] / 1000
            )
        return None

    def stop(self, stream_port, source=None):
        """Stoppt die Wiedergabe nur, wenn noch der eigene Stream läuft."""
        for player in self.call("Player.GetActivePlayers"):
            item = self.call(
                "Player.GetItem",
                {"playerid": player["playerid"], "properties": ["file"]},
            )["item"]
            if is_own_stream(item.get("file", ""), stream_port, source):
                self.call("Player.Stop", {"playerid": player["playerid"]})


def is_screencast(file, stream_port):
    """Kodi spielt einen Screencast auf diesem Port, von welchem Gerät auch immer."""
    return file.startswith((
        f"udp://@:{stream_port}/?", f"udp://@:{stream_port}?", plugin_url(stream_port),
    ))


def stream_source(file):
    """Absenderadresse, auf die das Addon den Strom beschränkt hat, sonst None."""
    params = dict(urllib.parse.parse_qsl(file.partition("?")[2]))
    return params.get("sources") or params.get("source")


def is_own_stream(file, stream_port, source=None):
    """Der Strom einer laufenden eigenen Übertragung.

    Ein Addon ohne Absenderfilter nennt keinen Absender; dann gilt jeder
    Screencast auf dem Port als der eigene.
    """
    return is_screencast(file, stream_port) and stream_source(file) in (None, source)
