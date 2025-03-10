"""Byte level framing for the Halvex line protocol.

The protocol is line oriented ASCII. A frame is the command or the response text
followed by CR LF. Firmware revision 2 added an optional checksum that rides on
the end of the payload as ``#HH``, where HH is the upper case hex of the XOR over
every byte before the marker.

Real machines are messy. A printer that has just been power cycled will spit half
a boot banner at us, an RFCOMM link that dropped mid frame leaves a fragment in
the buffer, and some of the older HVXLCP heads emit a bare LF instead of CR LF.
:class:`FrameBuffer` is written to survive all three without losing the frames
that come after.
"""

from __future__ import annotations

from collections.abc import Iterator

from nozzlewright.errors import FrameError

__all__ = [
    "CHECKSUM_MARKER",
    "MAX_FRAME_BYTES",
    "TERMINATOR",
    "FrameBuffer",
    "checksum",
    "encode_frame",
    "strip_checksum",
]

#: CR LF, what the firmware puts at the end of every frame it sends.
TERMINATOR = b"\r\n"

#: Longest frame we will assemble. The biggest legitimate payload is an
#: ENUMLABELS response on an eight pen machine, which tops out around 3 kB.
MAX_FRAME_BYTES = 4096

#: Separator between payload and checksum on revision 2 firmware.
CHECKSUM_MARKER = b"#"

# Tab is the only control byte that ever appears inside a legitimate payload,
# and only in the statistics table. Everything outside printable ASCII is noise.
_ALLOWED_CONTROL = frozenset({0x09})


def checksum(payload: bytes) -> int:
    """XOR every byte of ``payload`` together.

    The firmware uses a plain XOR rather than a CRC because the 8 bit MCU on the
    HVXLCP boards has no room for the table.
    """
    acc = 0
    for byte in payload:
        acc ^= byte
    return acc


def encode_frame(text: str, *, with_checksum: bool = False) -> bytes:
    """Turn command text into the bytes to put on the wire."""
    payload = text.encode("ascii", errors="strict")
    if b"\r" in payload or b"\n" in payload:
        raise FrameError("frame text must not contain CR or LF", raw=payload)
    if with_checksum:
        digits = format(checksum(payload), "02X").encode("ascii")
        payload = payload + CHECKSUM_MARKER + digits
    if len(payload) + len(TERMINATOR) > MAX_FRAME_BYTES:
        raise FrameError(
            "frame of %d bytes exceeds the %d byte limit" % (len(payload), MAX_FRAME_BYTES),
            raw=payload[:64],
        )
    return payload + TERMINATOR


def strip_checksum(payload: bytes) -> bytes:
    """Validate and remove a trailing ``#HH`` if one is present."""
    marker = payload.rfind(CHECKSUM_MARKER)
    if marker == -1 or marker != len(payload) - 3:
        return payload
    body = payload[:marker]
    claimed_text = payload[marker + 1 :]
    try:
        claimed = int(claimed_text, 16)
    except ValueError:
        raise FrameError("bad checksum digits %r" % (claimed_text,), raw=payload) from None
    actual = checksum(body)
    if claimed != actual:
        raise FrameError(
            "checksum mismatch, frame says %02X and we computed %02X" % (claimed, actual),
            raw=payload,
        )
    return body


class FrameBuffer:
    """Incremental frame assembler.

    Feed it whatever the transport hands back and iterate the frames that have
    completed. State survives between calls, so a frame split across four reads
    still comes out whole.
    """

    __slots__ = ("_buffer", "_dropped", "_verify")

    def __init__(self, *, verify_checksum: bool = True) -> None:
        self._buffer = bytearray()
        self._dropped = 0
        self._verify = verify_checksum

    @property
    def pending(self) -> int:
        """Bytes held that have not yet formed a frame."""
        return len(self._buffer)

    @property
    def dropped(self) -> int:
        """Bytes discarded because they could never be part of a valid frame."""
        return self._dropped

    def reset(self) -> None:
        """Throw away partial state. Called after every reconnect."""
        self._dropped += len(self._buffer)
        self._buffer.clear()

    def feed(self, data: bytes) -> Iterator[bytes]:
        """Add ``data`` and yield every complete frame it finished."""
        self._buffer.extend(data)
        while True:
            index = self._buffer.find(b"\n")
            if index == -1:
                if len(self._buffer) > MAX_FRAME_BYTES:
                    raise FrameError(
                        "no terminator inside %d bytes, link is out of sync" % MAX_FRAME_BYTES,
                        raw=bytes(self._buffer[:64]),
                    )
                return
            raw = bytes(self._buffer[:index])
            del self._buffer[: index + 1]
            if raw.endswith(b"\r"):
                raw = raw[:-1]
            frame = self._clean(raw)
            if frame is None:
                continue
            yield frame

    def _clean(self, raw: bytes) -> bytes | None:
        """Drop noise, validate the checksum, hand back a usable payload."""
        if not raw:
            return None
        filtered = bytes(b for b in raw if 0x20 <= b < 0x7F or b in _ALLOWED_CONTROL)
        self._dropped += len(raw) - len(filtered)
        if not filtered:
            return None
        if self._verify:
            return strip_checksum(filtered)
        marker = filtered.rfind(CHECKSUM_MARKER)
        if marker == len(filtered) - 3:
            return filtered[:marker]
        return filtered
