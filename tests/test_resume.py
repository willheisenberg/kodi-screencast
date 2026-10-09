from resources.lib.resume import Interruption, open_params

FILM = {"item": {"id": 84, "type": "movie", "file": "/media/film.mkv"}, "position": 600.5, "live": False}


def test_what_played_before_the_cast_is_resumed_after_it():
    tracker = Interruption()
    assert tracker.observe(FILM, False, now=10.0) is None
    assert tracker.observe(None, False, now=10.5) is None  # Kodi öffnet den Stream
    assert tracker.observe(None, True, now=12.0) is None
    assert tracker.observe(None, True, now=60.0) is None
    assert tracker.observe(None, False, now=60.3) == FILM
    assert tracker.observe(None, False, now=60.6) is None


def test_a_cast_without_anything_before_resumes_nothing():
    tracker = Interruption()
    assert tracker.observe(None, True, now=5.0) is None
    assert tracker.observe(None, False, now=9.0) is None


def test_something_that_ended_long_before_the_cast_is_not_resumed():
    tracker = Interruption()
    tracker.observe(FILM, False, now=10.0)
    tracker.observe(None, False, now=11.0)
    tracker.observe(None, True, now=40.0)
    assert tracker.observe(None, False, now=50.0) is None


def test_something_stopped_just_before_the_cast_is_not_resumed():
    tracker = Interruption()
    tracker.observe(FILM, False, now=10.0)
    tracker.forget()
    tracker.observe(None, False, now=10.3)
    tracker.observe(None, True, now=12.0)
    assert tracker.observe(None, False, now=30.0) is None


def test_the_cast_ending_does_not_drop_what_it_interrupted():
    tracker = Interruption()
    tracker.observe(FILM, False, now=10.0)
    tracker.observe(None, True, now=12.0)
    tracker.forget()  # Kodi meldet auch das Ende des Screencasts als Stopp
    assert tracker.observe(None, False, now=30.0) == FILM


def test_starting_something_else_during_the_cast_cancels_the_resume():
    tracker = Interruption()
    tracker.observe(FILM, False, now=10.0)
    tracker.observe(None, True, now=12.0)
    other = {"item": {"file": "/media/other.mkv"}, "position": 0.0, "live": False}
    assert tracker.observe(other, False, now=30.0) is None
    assert tracker.observe(None, False, now=31.0) is None


def test_library_items_reopen_by_id_a_little_before_the_break():
    assert open_params(FILM) == {
        "item": {"movieid": 84},
        "options": {"resume": {"hours": 0, "minutes": 9, "seconds": 57, "milliseconds": 500}},
    }


def test_plain_files_reopen_by_path():
    playback = {"item": {"type": "unknown", "file": "/media/clip.mkv"}, "position": 3725.0, "live": False}
    assert open_params(playback) == {
        "item": {"file": "/media/clip.mkv"},
        "options": {"resume": {"hours": 1, "minutes": 2, "seconds": 2, "milliseconds": 0}},
    }


def test_live_sources_restart_without_a_position():
    channel = {"item": {"id": 7, "type": "channel"}, "position": 12.0, "live": True}
    assert open_params(channel) == {"item": {"channelid": 7}}
    radio = {"item": {"type": "unknown", "file": "http://radio/stream"}, "position": 99.0, "live": True}
    assert open_params(radio) == {"item": {"file": "http://radio/stream"}}


def test_nothing_to_open_without_id_or_file():
    assert open_params({"item": {"type": "unknown"}, "position": 1.0}) is None
    assert open_params({"item": None, "position": 1.0}) is None
