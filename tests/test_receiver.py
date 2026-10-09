from resources.lib.receiver import Delay


def test_packets_come_out_after_the_delay_in_order():
    delay = Delay(0.2)
    delay.push(b"a", now=10.0)
    delay.push(b"b", now=10.005)
    assert delay.pop(now=10.1) is None
    assert delay.pop(now=10.2) == b"a"
    assert delay.pop(now=10.2) is None
    assert delay.pop(now=10.21) == b"b"


def test_packets_that_fell_too_far_behind_are_dropped():
    delay = Delay(0.0, max_late=0.06)
    for step in range(4):
        delay.push(bytes([step]), now=10.0 + step * 0.005)
    assert delay.pop(now=10.068) == bytes([2])
    assert delay.dropped == 2
    assert len(delay) == 1
