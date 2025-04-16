from __future__ import annotations

import pytest

from nozzlewright.errors import FrameError
from nozzlewright.protocol.framing import (
    MAX_FRAME_BYTES,
    FrameBuffer,
    checksum,
    encode_frame,
    strip_checksum,
)


def test_encode_appends_crlf() -> None:
    assert encode_frame("STATUS") == b"STATUS\r\n"


def test_encode_with_checksum() -> None:
    raw = encode_frame("PING", with_checksum=True)
    assert raw.endswith(b"\r\n")
    body = raw[:-2]
    assert body.startswith(b"PING#")
    assert int(body[-2:], 16) == checksum(b"PING")


def test_encode_rejects_embedded_terminator() -> None:
    with pytest.raises(FrameError):
        encode_frame("STATUS\rSTATUS")


def test_encode_rejects_oversize() -> None:
    with pytest.raises(FrameError):
        encode_frame("X" * (MAX_FRAME_BYTES + 1))


def test_encode_rejects_non_ascii() -> None:
    with pytest.raises(UnicodeEncodeError):
        encode_frame("LABEL:café")


def test_checksum_is_xor() -> None:
    assert checksum(b"") == 0
    assert checksum(b"\x01\x02") == 3
    assert checksum(b"AA") == 0


def test_strip_checksum_round_trip() -> None:
    body = b"0:READY"
    framed = body + b"#" + format(checksum(body), "02X").encode()
    assert strip_checksum(framed) == body


def test_strip_checksum_detects_corruption() -> None:
    with pytest.raises(FrameError, match="mismatch"):
        strip_checksum(b"0:READY#00")


def test_strip_checksum_rejects_bad_digits() -> None:
    with pytest.raises(FrameError, match="bad checksum"):
        strip_checksum(b"0:READY#ZZ")


def test_strip_checksum_leaves_plain_payload() -> None:
    assert strip_checksum(b"0:READY") == b"0:READY"


def test_buffer_reassembles_split_frame() -> None:
    buffer = FrameBuffer()
    assert list(buffer.feed(b"0:RE")) == []
    assert buffer.pending == 4
    assert list(buffer.feed(b"ADY\r\n")) == [b"0:READY"]
    assert buffer.pending == 0


def test_buffer_handles_bare_lf() -> None:
    buffer = FrameBuffer()
    assert list(buffer.feed(b"0:OK\n1:BUSY\n")) == [b"0:OK", b"1:BUSY"]


def test_buffer_skips_empty_lines() -> None:
    buffer = FrameBuffer()
    assert list(buffer.feed(b"\r\n\r\n0:OK\r\n")) == [b"0:OK"]


def test_buffer_strips_boot_noise() -> None:
    buffer = FrameBuffer()
    frames = list(buffer.feed(b"\x00\x00\xff0:OK\r\n"))
    assert frames == [b"0:OK"]
    assert buffer.dropped == 3


def test_buffer_resyncs_when_it_cannot_find_a_terminator() -> None:
    buffer = FrameBuffer()
    assert list(buffer.feed(b"A" * (MAX_FRAME_BYTES + 8))) == []
    assert buffer.resyncs == 1
    assert list(buffer.feed(encode_frame("0:OK"))) == [b"0:OK"]


def test_buffer_gives_up_on_a_link_that_never_syncs() -> None:
    buffer = FrameBuffer(resync_limit=2)
    with pytest.raises(FrameError, match="not speaking the protocol"):
        for _ in range(3):
            list(buffer.feed(b"A" * (MAX_FRAME_BYTES + 8)))


def test_buffer_reset_counts_discarded() -> None:
    buffer = FrameBuffer()
    list(buffer.feed(b"partial"))
    buffer.reset()
    assert buffer.pending == 0
    assert buffer.dropped == 7


def test_buffer_can_skip_checksum_verification() -> None:
    buffer = FrameBuffer(verify_checksum=False)
    assert list(buffer.feed(b"0:OK#00\r\n")) == [b"0:OK"]


def test_buffer_keeps_tab_inside_statistics_payload() -> None:
    buffer = FrameBuffer()
    assert list(buffer.feed(b"0:PRINTS\t1204\r\n")) == [b"0:PRINTS\t1204"]
