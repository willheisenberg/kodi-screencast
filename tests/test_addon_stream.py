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
