package io.github.rkaushikdev.nozzlewright;

import java.util.Arrays;

/** A frame was truncated, oversized, carried a bad terminator or a checksum that did not match. */
public class FrameError extends ProtocolError {

    private final byte[] raw;

    /** A frame fault with no bytes attached. */
    public FrameError(String message) {
        this(message, new byte[0]);
    }

    /** A frame fault, with the bytes that caused it. */
    public FrameError(String message, byte[] raw) {
        super(message);
        this.raw = raw.clone();
    }

    /** The bytes that could not be framed. */
    public byte[] raw() {
        return Arrays.copyOf(raw, raw.length);
    }
}
