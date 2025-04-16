"""Feeding the frame buffer things a printer should never send.

Not a property based library, just a seeded generator and a few thousand ugly
inputs. What is being checked is narrow and worth stating: the buffer either
yields a frame or does not, it never returns something that was not sent, and it
never wedges. Everything a machine does when its power supply is dying looks
like one of these.
"""

from __future__ import annotations

import random

import pytest

from nozzlewright.errors import FrameError
from nozzlewright.protocol.framing import (
    MAX_FRAME_BYTES,
    FrameBuffer,
    checksum,
    encode_frame,
    strip_checksum,
)

ALPHABET = bytes(range(0, 256))


def noise(rng: random.Random, size: int) -> bytes:
    return bytes(rng.choice(ALPHABET) for _ in range(size))


def test_random_bytes_never_wedge_the_buffer() -> None:
    rng = random.Random(20250416)
    for _ in range(400):
        buffer = FrameBuffer(verify_checksum=False, resync_limit=1000)
        chunk = noise(rng, rng.randint(0, 200))
        try:
            frames = list(buffer.feed(chunk))
        except FrameError:
            continue
        for frame in frames:
            assert b"\n" not in frame
            assert b"\r" not in frame
            assert all(0x20 <= byte < 0x7F or byte == 0x09 for byte in frame)


def test_a_well_formed_frame_survives_being_buried_in_noise() -> None:
    rng = random.Random(4417)
    for _ in range(200):
        buffer = FrameBuffer(verify_checksum=False, resync_limit=1000)
        payload = "0:STATE=READY"
        stream = noise(rng, rng.randint(0, 40)).replace(b"\n", b"") + encode_frame(payload)
        frames = list(buffer.feed(stream))
        assert frames
        assert frames[-1].endswith(payload.encode("ascii"))


def test_a_frame_split_at_every_position_comes_out_whole() -> None:
    raw = encode_frame("0:FAM=HVX8PEN,REV=2")
    for cut in range(len(raw) + 1):
        buffer = FrameBuffer()
        frames = list(buffer.feed(raw[:cut])) + list(buffer.feed(raw[cut:]))
        assert frames == [b"0:FAM=HVX8PEN,REV=2"]


def test_one_byte_at_a_time() -> None:
    raw = encode_frame("0:OK") + encode_frame("1:BUSY")
    buffer = FrameBuffer()
    frames: list[bytes] = []
    for index in range(len(raw)):
        frames.extend(buffer.feed(raw[index : index + 1]))
    assert frames == [b"0:OK", b"1:BUSY"]


def test_a_long_run_without_a_terminator_resyncs_rather_than_raising() -> None:
    buffer = FrameBuffer(resync_limit=3)
    assert list(buffer.feed(b"A" * (MAX_FRAME_BYTES + 10))) == []
    assert buffer.resyncs == 1
    assert buffer.pending == 0
    assert list(buffer.feed(encode_frame("0:OK"))) == [b"0:OK"]


def test_a_link_that_never_syncs_is_reported() -> None:
    buffer = FrameBuffer(resync_limit=2)
    with pytest.raises(FrameError, match="not speaking the protocol"):
        for _ in range(4):
            list(buffer.feed(b"A" * (MAX_FRAME_BYTES + 10)))


def test_a_good_frame_clears_the_resync_run() -> None:
    buffer = FrameBuffer(resync_limit=2)
    list(buffer.feed(b"A" * (MAX_FRAME_BYTES + 10)))
    list(buffer.feed(encode_frame("0:OK")))
    list(buffer.feed(b"A" * (MAX_FRAME_BYTES + 10)))
    assert buffer.resyncs == 2


def test_reset_clears_the_resync_run() -> None:
    buffer = FrameBuffer(resync_limit=2)
    list(buffer.feed(b"A" * (MAX_FRAME_BYTES + 10)))
    buffer.reset()
    list(buffer.feed(b"A" * (MAX_FRAME_BYTES + 10)))
    assert buffer.resyncs == 2


def test_a_boot_banner_then_a_frame() -> None:
    banner = b"\x00\xff\xfeHALVEX BOOT 2.4.118\x00\r\n"
    buffer = FrameBuffer()
    frames = list(buffer.feed(banner + encode_frame("0:READY")))
    assert frames[-1] == b"0:READY"
    assert b"HALVEX BOOT 2.4.118" in frames[0]


def test_checksums_survive_the_round_trip_for_every_payload_length() -> None:
    for length in range(1, 200):
        payload = ("0:" + "X" * length).encode("ascii")
        framed = payload + b"#" + format(checksum(payload), "02X").encode()
        assert strip_checksum(framed) == payload


def test_a_corrupted_checksum_is_caught_wherever_the_bit_flips() -> None:
    payload = b"0:STATE=READY"
    framed = payload + b"#" + format(checksum(payload), "02X").encode()
    caught = 0
    for index in range(len(payload)):
        broken = bytearray(framed)
        broken[index] ^= 0x01
        try:
            strip_checksum(bytes(broken))
        except FrameError:
            caught += 1
    assert caught == len(payload)


def test_repeated_terminators_yield_nothing_extra() -> None:
    buffer = FrameBuffer()
    assert list(buffer.feed(b"\r\n" * 50)) == []
    assert buffer.pending == 0


def test_a_frame_of_only_noise_is_dropped() -> None:
    buffer = FrameBuffer()
    assert list(buffer.feed(b"\x00\x01\x02\r\n")) == []
    assert buffer.dropped == 3


def test_interleaved_good_and_bad_frames() -> None:
    rng = random.Random(99)
    buffer = FrameBuffer(verify_checksum=False, resync_limit=1000)
    expected: list[bytes] = []
    stream = bytearray()
    for index in range(50):
        if index % 3 == 0:
            junk = noise(rng, 20).replace(b"\n", b"").replace(b"\r", b"")
            stream.extend(junk + b"\r\n")
        else:
            payload = "0:N=%d" % index
            expected.append(payload.encode("ascii"))
            stream.extend(encode_frame(payload))
    frames = list(buffer.feed(bytes(stream)))
    for wanted in expected:
        assert wanted in frames
