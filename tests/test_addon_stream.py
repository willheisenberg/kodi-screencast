import pytest

from resources.lib import stream


def test_port_is_read_from_the_plugin_query():
    assert stream.port_from_query("?port=6000") == 6000


def test_missing_port_falls_back_to_default():
    assert stream.port_from_query("") == stream.DEFAULT_PORT


@pytest.mark.parametrize("query", ["?port=80", "?port=70000"])
def test_ports_outside_the_user_range_are_rejected(query):
    with pytest.raises(ValueError):
        stream.port_from_query(query)


def test_stream_url_listens_on_the_port():
    assert stream.stream_url(6000).startswith("udp://@:6000?")


def test_stream_is_marked_as_realtime_for_ffmpegdirect():
    assert stream.PROPERTIES["inputstream"] == "inputstream.ffmpegdirect"
    assert stream.PROPERTIES["inputstream.ffmpegdirect.is_realtime_stream"] == "true"


def test_audio_settings_are_read_from_the_plugin_query():
    assert stream.audio_from_query("?port=6000&audio_port=6001&audio_delay=150") == (6001, 150)
    assert stream.audio_from_query("?port=6000&audio_port=6001") == (6001, stream.DEFAULT_AUDIO_DELAY_MS)
    assert stream.audio_from_query("?port=6000") is None


def test_stream_url_carries_the_audio_settings_for_the_service():
    url = stream.stream_url(6000, (6001, 150))
    assert stream.audio_of_playing(url) == (6001, 150)
    assert stream.audio_of_playing(stream.stream_url(6000)) is None


def test_other_playing_files_have_no_screencast_audio():
    assert stream.audio_of_playing("") is None
    assert stream.audio_of_playing("/storage/videos/film.mkv?audio_port=6001") is None
    assert stream.audio_of_playing("plugin://plugin.video.screencast/?port=6000&audio_port=6001") == (
        6001, stream.DEFAULT_AUDIO_DELAY_MS,
    )


def test_alsa_device_uses_the_card_kodi_is_set_to():
    assert stream.alsa_device("ALSA:hdmi:CARD=vc4hdmi0,DEV=0|vc4-hdmi-0 (vc4hdmi0)") == "default:CARD=vc4hdmi0"
    assert stream.alsa_device("PULSE:Default") == "default"
    assert stream.alsa_device("") == "default"


def test_only_own_streams_count_as_screencast():
    assert stream.is_screencast(stream.stream_url(6000))
    assert stream.is_screencast(stream.stream_url(6000, (6001, 150)))
    assert stream.is_screencast("plugin://plugin.video.screencast/?port=6000")
    assert not stream.is_screencast("udp://@:1234")
    assert not stream.is_screencast("/storage/videos/film.mkv")
    assert not stream.is_screencast("")
