import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from kodi_screencast.kodi import OTHER_CAST, Kodi, KodiError, is_own_stream, plugin_url


@pytest.fixture
def server():
    """Kleiner JSON-RPC-Server, der Aufrufe mitschreibt und Antworten vorgibt."""
    calls, answers = [], {}

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            calls.append((body["method"], body.get("params"), self.headers.get("Authorization")))
            answer = answers.get(body["method"], {"result": "OK"})
            payload = json.dumps({"jsonrpc": "2.0", "id": body["id"], **answer}).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *args):
            pass

    httpd = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield httpd.server_address[1], calls, answers
    httpd.shutdown()


def test_play_opens_the_plugin_url(server):
    port, calls, _ = server
    Kodi("127.0.0.1", port).play(5004)
    assert calls == [
        ("Player.Open", {"item": {"file": "plugin://plugin.video.screencast/?port=5004"}}, None),
    ]


def test_play_passes_the_audio_settings_to_the_addon(server):
    port, calls, _ = server
    Kodi("127.0.0.1", port).play(5004, 5005, 150)
    assert calls[0][1] == {"item": {
        "file": "plugin://plugin.video.screencast/?port=5004&audio_port=5005&audio_delay=150",
    }}


def test_credentials_are_sent_as_basic_auth(server):
    port, calls, _ = server
    Kodi("127.0.0.1", port, "kodi", "geheim").play(5004)
    assert calls[0][2] == "Basic a29kaTpnZWhlaW0="


def test_error_answer_raises(server):
    port, _, answers = server
    answers["Player.Open"] = {"error": {"code": -32602, "message": "Invalid params."}}
    with pytest.raises(KodiError, match="Invalid params"):
        Kodi("127.0.0.1", port).play(5004)


def test_unreachable_kodi_names_the_setting():
    with pytest.raises(KodiError, match="Fernsteuerung über HTTP"):
        Kodi("127.0.0.1", 1, timeout=1).call("JSONRPC.Ping")


def test_stop_only_stops_the_own_stream(server):
    port, calls, answers = server
    answers["Player.GetActivePlayers"] = {"result": [{"playerid": 1, "type": "video"}]}
    answers["Player.GetItem"] = {"result": {"item": {"file": "/storage/videos/film.mkv"}}}
    Kodi("127.0.0.1", port).stop(5004)
    assert "Player.Stop" not in [method for method, _, _ in calls]

    answers["Player.GetItem"] = {"result": {"item": {"file": "udp://@:5004?overrun_nonfatal=1"}}}
    Kodi("127.0.0.1", port).stop(5004)
    assert calls[-1][:2] == ("Player.Stop", {"playerid": 1})


def test_other_playback_names_what_a_cast_would_interrupt(server):
    port, _, answers = server
    answers["Player.GetActivePlayers"] = {"result": []}
    assert Kodi("127.0.0.1", port).other_playback(5004) is None

    answers["Player.GetActivePlayers"] = {"result": [{"playerid": 1, "type": "video"}]}
    mine = plugin_url(5004, source="192.168.1.5")
    answers["Player.GetItem"] = {"result": {"item": {"file": mine, "label": "Screencast"}}}
    assert Kodi("127.0.0.1", port).other_playback(5004, "192.168.1.5") is None
    # Dieselbe Übertragung von einem anderen Gerät aus gesehen, und die eines älteren Senders.
    assert Kodi("127.0.0.1", port).other_playback(5004, "192.168.1.6") is OTHER_CAST
    answers["Player.GetItem"] = {"result": {"item": {"file": plugin_url(5004), "label": "Screencast"}}}
    assert Kodi("127.0.0.1", port).other_playback(5004, "192.168.1.6") is OTHER_CAST

    answers["Player.GetItem"] = {
        "result": {"item": {"file": "/media/film.mkv", "title": "", "label": "Send Help"}}
    }
    assert Kodi("127.0.0.1", port).other_playback(5004) == "Send Help"

    answers["Player.GetItem"] = {"result": {"item": {"file": "/media/clip.mkv"}}}
    assert Kodi("127.0.0.1", port).other_playback(5004) == "clip.mkv"


def test_playback_time_only_counts_the_own_stream(server):
    port, _, answers = server
    answers["Player.GetActivePlayers"] = {"result": []}
    assert Kodi("127.0.0.1", port).playback_time(5004) is None

    answers["Player.GetActivePlayers"] = {"result": [{"playerid": 1, "type": "video"}]}
    answers["Player.GetItem"] = {"result": {"item": {"file": "/storage/videos/film.mkv"}}}
    answers["Player.GetProperties"] = {
        "result": {"time": {"hours": 0, "minutes": 1, "seconds": 2, "milliseconds": 500}}
    }
    assert Kodi("127.0.0.1", port).playback_time(5004) is None

    answers["Player.GetItem"] = {"result": {"item": {"file": plugin_url(5004, 5005, 200)}}}
    assert Kodi("127.0.0.1", port).playback_time(5004) == 62.5


def test_has_addon_is_false_when_kodi_does_not_know_it(server):
    port, _, answers = server
    answers["Addons.GetAddonDetails"] = {"error": {"code": -32602, "message": "Invalid params."}}
    assert Kodi("127.0.0.1", port).has_addon() is False
    answers["Addons.GetAddonDetails"] = {"result": {"addon": {"enabled": True}}}
    assert Kodi("127.0.0.1", port).has_addon() is True


def test_is_own_stream_matches_resolved_and_plugin_url():
    assert is_own_stream("udp://@:5004?fifo_size=50000", 5004)
    assert is_own_stream("udp://@:5004/?fifo_size=50000", 5004)
    assert is_own_stream(plugin_url(5004), 5004)
    assert is_own_stream(plugin_url(5004, 5005, 200), 5004)
    assert not is_own_stream("udp://@:5005", 5004)


def test_has_addon_does_not_hide_connection_problems():
    with pytest.raises(KodiError, match="nicht erreichbar"):
        Kodi("127.0.0.1", 1, timeout=1).has_addon()


def test_a_cast_of_another_device_is_not_the_own_stream():
    theirs = "udp://@:5004?overrun_nonfatal=1&fifo_size=50000&sources=192.168.1.5&audio_port=5005"
    assert is_own_stream(theirs, 5004, "192.168.1.5")
    assert not is_own_stream(theirs, 5004, "192.168.1.6")
    assert not is_own_stream(plugin_url(5004, 5005, 200, "192.168.1.5"), 5004, "192.168.1.6")
    # Ein Addon ohne Absenderfilter nennt keinen Absender.
    assert is_own_stream("udp://@:5004?overrun_nonfatal=1&fifo_size=50000", 5004, "192.168.1.6")


def test_stop_leaves_the_cast_of_another_device_alone(server):
    port, calls, answers = server
    answers["Player.GetActivePlayers"] = {"result": [{"playerid": 1, "type": "video"}]}
    answers["Player.GetItem"] = {"result": {"item": {"file": plugin_url(5004, source="192.168.1.5")}}}
    Kodi("127.0.0.1", port).stop(5004, "192.168.1.6")
    assert "Player.Stop" not in [method for method, _, _ in calls]
    Kodi("127.0.0.1", port).stop(5004, "192.168.1.5")
    assert calls[-1][:2] == ("Player.Stop", {"playerid": 1})


def test_play_names_the_sender_so_the_addon_can_filter(server):
    port, calls, _ = server
    Kodi("127.0.0.1", port).play(5004, 5005, 150, "192.168.1.5")
    assert calls[0][1] == {"item": {"file": (
        "plugin://plugin.video.screencast/?port=5004&source=192.168.1.5"
        "&audio_port=5005&audio_delay=150"
    )}}


def test_local_address_is_the_one_facing_kodi():
    assert Kodi("127.0.0.1").local_address() == "127.0.0.1"
