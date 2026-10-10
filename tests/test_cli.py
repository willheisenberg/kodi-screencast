import argparse
import os
import threading

import pytest

from kodi_screencast import cli, pipeline
from kodi_screencast.kodi import KodiError


class FakeElement:
    def __init__(self, log, name):
        self.log, self.name = log, name

    def set_property(self, key, value):
        self.log.append((self.name, key, value))


class FakePipe:
    def __init__(self, log):
        self.log = log

    def get_by_name(self, name):
        return FakeElement(self.log, name)


class FakeKodi:
    def __init__(self, times):
        self.times = iter(times)

    def playback_time(self, _port):
        value = next(self.times)
        if isinstance(value, Exception):
            raise value
        return value


def test_catch_up_pauses_once_playback_runs_then_asks_for_a_keyframe(monkeypatch):
    log = []
    kodi = FakeKodi([None, KodiError("weg"), 0.2, 1.4])
    stopped = threading.Event()
    monkeypatch.setattr(stopped, "wait", lambda _seconds: False)

    cli.catch_up(FakePipe(log), kodi, 5004, stopped, lambda encoder: log.append(encoder.name))

    assert log == [
        (pipeline.GATE, "drop", True),
        (pipeline.GATE, "drop", False),
        pipeline.ENCODER,
    ]


def test_catch_up_does_nothing_when_the_cast_ends_first():
    log = []
    stopped = threading.Event()
    stopped.set()
    cli.catch_up(FakePipe(log), FakeKodi([None]), 5004, stopped, log.append)
    assert log == []


def test_status_tells_by_exit_code_whether_a_cast_runs(tmp_path, monkeypatch):
    pid_file = tmp_path / "kodi-screencast.pid"
    monkeypatch.setattr(cli, "PID_FILE", pid_file)
    with pytest.raises(SystemExit) as stopped:
        cli.main(["status"])
    assert stopped.value.code == 1

    pid_file.write_text(str(os.getpid()))
    cli.main(["status"])


def kodi_args(**overrides):
    values = dict(host=None, rpc_port=8080, user="", password="")
    return argparse.Namespace(**dict(values, **overrides))


def test_explicit_host_is_trusted():
    found = cli.find_kodi(kodi_args(host="10.0.0.5", rpc_port=8081), {})
    assert found == ("10.0.0.5", 8081, True)


def test_remembered_host_is_trusted_and_skips_discovery(monkeypatch):
    monkeypatch.setattr(cli.discovery, "find", lambda: pytest.fail("darf nicht suchen"))
    state = {"host": "10.0.0.5", "rpc_port": 8081}
    assert cli.find_kodi(kodi_args(), state) == ("10.0.0.5", 8081, True)


def test_discovered_host_is_not_trusted(monkeypatch):
    monkeypatch.setattr(cli.discovery, "find", lambda: [("Kodi", "10.0.0.9", 8080)])
    assert cli.find_kodi(kodi_args(), {}) == ("10.0.0.9", 8080, False)


def test_credentials_are_never_sent_to_a_merely_discovered_host(monkeypatch):
    monkeypatch.setattr(cli.discovery, "find", lambda: [("Kodi", "10.0.0.9", 8080)])
    monkeypatch.setattr(cli, "load_state", lambda: {})
    monkeypatch.setattr(cli, "Kodi", lambda *a, **k: pytest.fail("darf keinen Client anlegen"))
    with pytest.raises(SystemExit, match="--host 10.0.0.9"):
        cli.open_kodi(kodi_args(user="kodi", password="geheim"))


def test_host_given_on_the_command_line_is_remembered(tmp_path, monkeypatch):
    monkeypatch.setattr(cli, "STATE_FILE", tmp_path / "state.json")
    cli.open_kodi(kodi_args(host="10.0.0.5", user="kodi", password="geheim"))
    assert cli.load_state() == {"host": "10.0.0.5", "rpc_port": 8080}


def test_pid_file_never_falls_back_to_tmp():
    assert not str(cli.PID_FILE).startswith("/tmp/")
