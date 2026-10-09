"""JSON-RPC-Client für Kodis Fernsteuer-Schnittstelle (HTTP)."""

import base64
import json
import urllib.error
import urllib.request

ADDON_ID = "plugin.video.screencast"


class KodiError(RuntimeError):
    pass


class RpcError(KodiError):
    """Kodi hat den Aufruf angenommen, aber mit einem Fehler beantwortet."""


def plugin_url(port):
    return f"plugin://{ADDON_ID}/?port={port}"


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

    def play(self, stream_port):
        self.call("Player.Open", {"item": {"file": plugin_url(stream_port)}})

    def stop(self, stream_port):
        """Stoppt die Wiedergabe nur, wenn noch der eigene Stream läuft."""
        for player in self.call("Player.GetActivePlayers"):
            item = self.call(
                "Player.GetItem",
                {"playerid": player["playerid"], "properties": ["file"]},
            )["item"]
            if is_own_stream(item.get("file", ""), stream_port):
                self.call("Player.Stop", {"playerid": player["playerid"]})


def is_own_stream(file, stream_port):
    return file.startswith(f"udp://@:{stream_port}") or file == plugin_url(stream_port)
