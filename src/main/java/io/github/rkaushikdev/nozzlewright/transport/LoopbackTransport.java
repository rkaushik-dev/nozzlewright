package io.github.rkaushikdev.nozzlewright.transport;

import io.github.rkaushikdev.nozzlewright.LinkClosed;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * An in process link.
 *
 * <p>Used by the test suite and by the desk simulator. Bytes written go to a {@link Responder}, and
 * whatever it hands back comes out of {@link #recv}. No sockets, no radios, no timing luck.
 *
 * <p>It also carries the knobs needed to reproduce field failures: chop the response into
 * fragments, drop the link at a chosen moment, stall for longer than the read timeout.
 */
public class LoopbackTransport extends Transport {

    private final Responder responder;
    private final LinkedBlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>();
    private final int fragmentSize;
    private final Duration stall;
    private final List<byte[]> written = new ArrayList<>();
    private volatile int dropAfterWrites = -1;

    /** A link to nothing: writes vanish and reads time out. */
    public LoopbackTransport() {
        this(data -> new byte[0], "loopback");
    }

    /** A link named {@code endpoint} that answers nothing. */
    public LoopbackTransport(String endpoint) {
        this(data -> new byte[0], endpoint);
    }

    /** A link to {@code responder}. */
    public LoopbackTransport(Responder responder) {
        this(responder, "loopback");
    }

    /** A link to {@code responder} named {@code endpoint}. */
    public LoopbackTransport(Responder responder, String endpoint) {
        this(responder, endpoint, 0, Duration.ZERO);
    }

    /**
     * A link that hands replies back in pieces of {@code fragmentSize} bytes (whole when zero) and
     * stalls every read by {@code stall}.
     */
    public LoopbackTransport(Responder responder, String endpoint, int fragmentSize, Duration stall) {
        super(endpoint);
        this.responder = responder;
        this.fragmentSize = fragmentSize;
        this.stall = stall;
    }

    /** Make the link fail once {@code writes} more frames have been sent. */
    public void dropAfter(int writes) {
        dropAfterWrites = writes;
    }

    /** Queue unsolicited bytes, as a printer does when it raises an alarm. */
    public void push(byte[] data) {
        inbox.add(data.clone());
    }

    /** Everything written so far, in order. */
    public List<byte[]> written() {
        synchronized (written) {
            return written.stream().map(byte[]::clone).toList();
        }
    }

    @Override
    protected void openLink() throws InterruptedException {
        // Nothing to bring up.
    }

    @Override
    protected void closeLink() {
        inbox.clear();
    }

    @Override
    protected void write(byte[] data) throws InterruptedException {
        if (dropAfterWrites == 0) {
            throw new LinkClosed("simulated link drop", endpoint());
        }
        if (dropAfterWrites > 0) {
            dropAfterWrites--;
        }
        synchronized (written) {
            written.add(data.clone());
        }
        byte[] reply = responder.respond(data);
        if (reply.length > 0) {
            for (byte[] chunk : split(reply)) {
                inbox.add(chunk);
            }
        }
    }

    @Override
    protected byte[] read(Duration timeout) throws InterruptedException {
        if (!stall.isZero()) {
            Thread.sleep(stall.compareTo(timeout) < 0 ? stall : timeout);
            if (stall.compareTo(timeout) >= 0) {
                return new byte[0];
            }
        }
        byte[] data = inbox.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
        return data == null ? new byte[0] : data;
    }

    private List<byte[]> split(byte[] data) {
        if (fragmentSize <= 0) {
            return List.of(data);
        }
        List<byte[]> chunks = new ArrayList<>();
        for (int at = 0; at < data.length; at += fragmentSize) {
            chunks.add(Arrays.copyOfRange(data, at, Math.min(data.length, at + fragmentSize)));
        }
        return chunks;
    }
}
