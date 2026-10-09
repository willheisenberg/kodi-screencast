"""Bildschirmfreigabe über das ScreenCast-Portal (xdg-desktop-portal).

Linux-spezifisch. Liefert einen PipeWire-fd samt Node-ID, aus dem die Pipeline
das Bild liest. Die Freigabe bleibt bestehen, solange das ScreenCast-Objekt
lebt.
"""

from dataclasses import dataclass
from itertools import count

from gi.repository import Gio, GLib

PORTAL_BUS = "org.freedesktop.portal.Desktop"
PORTAL_PATH = "/org/freedesktop/portal/desktop"
SCREENCAST_IFACE = "org.freedesktop.portal.ScreenCast"
REQUEST_IFACE = "org.freedesktop.portal.Request"

SOURCE_MONITOR = 1
CURSOR_EMBEDDED = 2
PERSIST_UNTIL_REVOKED = 2


class PortalError(RuntimeError):
    pass


@dataclass
class Stream:
    fd: int
    node_id: int
    width: int
    height: int
    restore_token: str


class ScreenCast:
    def __init__(self):
        self._bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        self._sender = self._bus.get_unique_name()[1:].replace(".", "_")
        self._tokens = count(1)
        self._session = None

    def open(self, restore_token=""):
        """Fragt die Freigabe an und blockiert, bis der Nutzer entschieden hat."""
        token = self._token()
        results = self._request(
            "CreateSession",
            lambda opts: GLib.Variant("(a{sv})", (opts,)),
            {"session_handle_token": GLib.Variant("s", token)},
        )
        self._session = results["session_handle"]

        options = {
            "types": GLib.Variant("u", SOURCE_MONITOR),
            "multiple": GLib.Variant("b", False),
            "cursor_mode": GLib.Variant("u", CURSOR_EMBEDDED),
            "persist_mode": GLib.Variant("u", PERSIST_UNTIL_REVOKED),
        }
        if restore_token:
            options["restore_token"] = GLib.Variant("s", restore_token)
        self._request(
            "SelectSources",
            lambda opts: GLib.Variant("(oa{sv})", (self._session, opts)),
            options,
        )

        results = self._request(
            "Start",
            lambda opts: GLib.Variant("(osa{sv})", (self._session, "", opts)),
            {},
        )
        streams = results.get("streams")
        if not streams:
            raise PortalError("Portal hat keinen Stream geliefert")
        node_id, props = streams[0]
        width, height = props.get("size", (0, 0))

        return Stream(
            fd=self._open_pipewire_remote(),
            node_id=node_id,
            width=width,
            height=height,
            restore_token=results.get("restore_token", ""),
        )

    def close(self):
        if self._session:
            self._bus.call_sync(
                PORTAL_BUS, self._session, "org.freedesktop.portal.Session",
                "Close", None, None, Gio.DBusCallFlags.NONE, -1, None,
            )
            self._session = None

    def _token(self):
        return f"kodiscreencast{next(self._tokens)}"

    def _request(self, method, build_params, options):
        """Ruft eine Portal-Methode auf und wartet auf deren Response-Signal."""
        token = self._token()
        request_path = f"{PORTAL_PATH}/request/{self._sender}/{token}"
        options = dict(options, handle_token=GLib.Variant("s", token))

        loop = GLib.MainLoop()
        answer = {}

        def on_response(_bus, _sender, _path, _iface, _signal, params):
            answer["code"], answer["results"] = params.unpack()
            loop.quit()

        # Vor dem Aufruf abonnieren, sonst kann die Antwort verloren gehen.
        subscription = self._bus.signal_subscribe(
            PORTAL_BUS, REQUEST_IFACE, "Response", request_path, None,
            Gio.DBusSignalFlags.NONE, on_response,
        )
        try:
            self._bus.call_sync(
                PORTAL_BUS, PORTAL_PATH, SCREENCAST_IFACE, method,
                build_params(options), None, Gio.DBusCallFlags.NONE, -1, None,
            )
            loop.run()
        finally:
            self._bus.signal_unsubscribe(subscription)

        if answer["code"] != 0:
            raise PortalError(f"{method} wurde abgelehnt (Code {answer['code']})")
        return answer["results"]

    def _open_pipewire_remote(self):
        _result, fd_list = self._bus.call_with_unix_fd_list_sync(
            PORTAL_BUS, PORTAL_PATH, SCREENCAST_IFACE, "OpenPipeWireRemote",
            GLib.Variant("(oa{sv})", (self._session, {})),
            None, Gio.DBusCallFlags.NONE, -1, None, None,
        )
        return fd_list.steal_fds()[0]
