package io.github.rkaushikdev.nozzlewright.transport;

import io.github.rkaushikdev.nozzlewright.LinkClosed;
import io.github.rkaushikdev.nozzlewright.util.Durations;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.Arrays;

/**
 * A byte stream over a connected socket channel, shared by the TCP and RFCOMM links.
 *
 * <p>The channel runs non blocking under two selectors, one for reads and one for writes, so a read
 * that is waiting out its timeout does not hold up a write, and an interrupt wakes a waiting read
 * without closing the socket underneath it. A read that returns end of stream is the far end
 * closing, never an idle link: a half open connection otherwise reads as idle forever.
 */
abstract class SocketLink extends Transport {

    /** The words a link uses when it reports its socket gone. */
    record Wording(String gone, String writeBlocked, String farEndClosed, int readSize) {}

    private final Wording wording;
    private volatile SocketChannel channel;
    private Selector reads;
    private Selector writes;
    private final double writeTimeoutSeconds;

    SocketLink(String endpoint, double writeTimeoutSeconds, Wording wording) {
        super(endpoint);
        this.writeTimeoutSeconds = writeTimeoutSeconds;
        this.wording = wording;
    }

    /** Take over a connected channel and get it ready for reading and writing. */
    final void attach(SocketChannel connected) throws IOException {
        connected.configureBlocking(false);
        Selector forReads = Selector.open();
        Selector forWrites = Selector.open();
        connected.register(forReads, SelectionKey.OP_READ);
        connected.register(forWrites, SelectionKey.OP_WRITE);
        this.reads = forReads;
        this.writes = forWrites;
        this.channel = connected;
    }

    @Override
    protected final void closeLink() {
        SocketChannel held = channel;
        channel = null;
        quietly(reads);
        quietly(writes);
        if (held != null) {
            try {
                held.close();
            } catch (IOException error) {
                // A socket that is already broken fails here and there is nothing left to do.
                return;
            }
        }
    }

    @Override
    protected final void write(byte[] data) throws InterruptedException {
        SocketChannel held = channel;
        if (held == null) {
            throw new LinkClosed(wording.gone(), endpoint());
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        long deadline =
                System.nanoTime() + Durations.ofSeconds(writeTimeoutSeconds).toNanos();
        try {
            held.write(buffer);
            while (buffer.hasRemaining()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new LinkClosed(
                            wording.writeBlocked() + " " + Durations.tenths(writeTimeoutSeconds), endpoint());
                }
                writes.select(Durations.waitMillis(Duration.ofNanos(left)));
                writes.selectedKeys().clear();
                if (Thread.interrupted()) {
                    throw new InterruptedException("write interrupted");
                }
                held.write(buffer);
            }
        } catch (IOException error) {
            throw new LinkClosed(String.valueOf(error.getMessage()), endpoint(), error);
        }
    }

    @Override
    protected final byte[] read(Duration timeout) throws InterruptedException {
        SocketChannel held = channel;
        if (held == null) {
            throw new LinkClosed(wording.gone(), endpoint());
        }
        ByteBuffer buffer = ByteBuffer.allocate(wording.readSize());
        try {
            int count = held.read(buffer);
            if (count == 0) {
                reads.select(Durations.waitMillis(timeout));
                reads.selectedKeys().clear();
                if (Thread.interrupted()) {
                    throw new InterruptedException("read interrupted");
                }
                count = held.read(buffer);
            }
            if (count < 0) {
                throw new LinkClosed(wording.farEndClosed(), endpoint());
            }
            return Arrays.copyOf(buffer.array(), count);
        } catch (IOException error) {
            throw new LinkClosed(String.valueOf(error.getMessage()), endpoint(), error);
        }
    }

    private static void quietly(Selector selector) {
        if (selector == null) {
            return;
        }
        try {
            selector.close();
        } catch (IOException error) {
            // Nothing to release that closing did not already release.
            return;
        }
    }
}
