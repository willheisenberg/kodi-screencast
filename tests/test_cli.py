import threading

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
