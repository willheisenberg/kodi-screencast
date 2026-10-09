from kodi_screencast import pipeline


def test_scaled_size_shrinks_to_max_height_keeping_aspect():
    assert pipeline.scaled_size(3840, 2160, 1080) == (1920, 1080)


def test_scaled_size_leaves_smaller_sources_alone():
    assert pipeline.scaled_size(1280, 720, 1080) == (1280, 720)


def test_scaled_size_rounds_to_even_dimensions():
    assert pipeline.scaled_size(2880, 1920, 1080) == (1620, 1080)
    assert pipeline.scaled_size(1695, 1131, 1080) == (1618, 1080)
    assert pipeline.scaled_size(1001, 701, 1080) == (1000, 700)


def test_build_sends_hevc_without_b_frames_to_the_target():
    settings = pipeline.Settings(host="10.0.0.5", port=6000, fps=25, audio=False)
    description = pipeline.build(settings, fd=7, node_id=42, src_width=3840, src_height=2160)
    assert "pipewiresrc fd=7 path=42" in description
    assert "width=1920,height=1080" in description
    assert "b-frames=0" in description
    assert "key-int-max=25" in description
    assert "udpsink host=10.0.0.5 port=6000" in description
    assert "pulsesrc" not in description


def test_build_adds_audio_branch_when_enabled():
    settings = pipeline.Settings(host="10.0.0.5", audio_device="sink.monitor")
    description = pipeline.build(settings, fd=7, node_id=42, src_width=1920, src_height=1080)
    assert "pulsesrc device=sink.monitor" in description
    assert description.count("mux.") == 2
