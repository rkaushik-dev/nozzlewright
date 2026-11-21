package io.github.rkaushikdev.nozzlewright.protocol;

import io.github.rkaushikdev.nozzlewright.FrameError;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Incremental frame assembler.
 *
 * <p>Feed it whatever the transport hands back and collect the frames that have completed. State
 * survives between calls, so a frame split across four reads still comes out whole.
 *
 * <p>Real machines are messy. A printer that has just been power cycled sends half a boot banner,
 * an RFCOMM link that dropped mid frame leaves a fragment in the buffer, and some of the older
 * HVXLCP heads end a frame with a bare LF instead of CR LF. The buffer survives all three without
 * losing the frames that come after. Not thread safe: one reader owns it.
 */
public final class FrameBuffer {

    private byte[] buffer = new byte[0];
    private int length;
    private long dropped;
    private int resyncs;
    private int run;
    private final int limit;
    private final boolean verify;

    /** A buffer that verifies checksums and absorbs up to four resyncs in a row. */
    public FrameBuffer() {
        this(true, 4);
    }

    /** A buffer that verifies checksums or not, absorbing up to {@code resyncLimit} resyncs. */
    public FrameBuffer(boolean verifyChecksum, int resyncLimit) {
        this.limit = Math.max(1, resyncLimit);
        this.verify = verifyChecksum;
    }

    /** Bytes held that have not yet formed a frame. */
    public int pending() {
        return length;
    }

    /** Bytes discarded because they could never be part of a valid frame. */
    public long dropped() {
        return dropped;
    }

    /** How many times the buffer has been thrown away to find a frame again. */
    public int resyncs() {
        return resyncs;
    }

    /** Throw away partial state. Called after every reconnect. */
    public void reset() {
        dropped += length;
        length = 0;
        run = 0;
    }

    /** Add {@code data} and return every complete frame it finished. */
    public List<byte[]> feed(byte[] data) {
        List<byte[]> frames = new ArrayList<>();
        feed(data, frames::add);
        return frames;
    }

    /**
     * Add {@code data} and hand every complete frame it finished to {@code sink}, in order.
     *
     * <p>Frames completed before a fault are handed over before the fault is thrown.
     *
     * @throws FrameError when the link has gone too long without a terminator, or a checksum fails
     */
    public void feed(byte[] data, Consumer<byte[]> sink) {
        append(data);
        while (true) {
            int index = indexOf((byte) '\n');
            if (index == -1) {
                if (length > Framing.MAX_FRAME_BYTES) {
                    resync();
                    continue;
                }
                return;
            }
            int end = index;
            if (end > 0 && buffer[end - 1] == '\r') {
                end--;
            }
            byte[] raw = Arrays.copyOf(buffer, end);
            consume(index + 1);
            run = 0;
            byte[] frame = clean(raw);
            if (frame != null) {
                sink.accept(frame);
            }
        }
    }

    /**
     * Throw the buffer away and look for a frame again.
     *
     * <p>A printer that is booting sends a banner with no terminator in it, and failing there takes
     * the reader down with a link that is about to start working. So the first few of these are
     * absorbed. What is not absorbed is a link that never syncs, because that is a wiring fault and
     * silently swallowing it means a machine that looks idle forever.
     */
    private void resync() {
        dropped += length;
        length = 0;
        resyncs++;
        run++;
        if (run >= limit) {
            run = 0;
            throw new FrameError("no terminator in " + limit + " runs of " + Framing.MAX_FRAME_BYTES
                    + " bytes, the link is not speaking the protocol");
        }
    }

    /** Drop noise, validate the checksum, hand back a usable payload or {@code null}. */
    private byte[] clean(byte[] raw) {
        if (raw.length == 0) {
            return null;
        }
        ByteArrayOutputStream kept = new ByteArrayOutputStream(raw.length);
        for (byte b : raw) {
            int value = b & 0xFF;
            if ((value >= 0x20 && value < 0x7F) || value == 0x09) {
                kept.write(value);
            }
        }
        byte[] filtered = kept.toByteArray();
        dropped += raw.length - filtered.length;
        if (filtered.length == 0) {
            return null;
        }
        if (verify) {
            return Framing.stripChecksum(filtered);
        }
        int marker = Framing.lastIndexOf(filtered, Framing.CHECKSUM_MARKER);
        if (marker != -1 && marker == filtered.length - 3) {
            return Arrays.copyOf(filtered, marker);
        }
        return filtered;
    }

    private void append(byte[] data) {
        if (length + data.length > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, length + data.length));
        }
        System.arraycopy(data, 0, buffer, length, data.length);
        length += data.length;
    }

    private int indexOf(byte value) {
        for (int index = 0; index < length; index++) {
            if (buffer[index] == value) {
                return index;
            }
        }
        return -1;
    }

    private void consume(int count) {
        System.arraycopy(buffer, count, buffer, 0, length - count);
        length -= count;
    }
}
