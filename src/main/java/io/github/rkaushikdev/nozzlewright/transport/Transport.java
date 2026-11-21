package io.github.rkaushikdev.nozzlewright.transport;

import io.github.rkaushikdev.nozzlewright.LinkClosed;
import java.time.Duration;

/**
 * What every link to a printer has to provide.
 *
 * <p>A transport moves bytes and nothing else. It does not know about frames, verbs or sessions.
 * That split matters because the same session code runs over an RFCOMM socket on the handheld, a
 * TCP socket on the line gateway and an in process pipe in the test suite, and none of them should
 * be able to change how a command is built.
 *
 * <p>Single reader and single writer: one thread calls {@link #recv} in a loop, one thread at a
 * time calls {@link #send}. The session layer above enforces both. Subclasses implement the four
 * protected methods; this class handles the open flag, the counters and closing, so no subclass has
 * to get that right twice. Blocking calls give up with {@link InterruptedException} when their
 * thread is interrupted.
 */
public abstract class Transport implements AutoCloseable {

    private final String endpoint;
    private final TransportStats stats = new TransportStats();
    private volatile boolean open;

    /** A closed link to {@code endpoint}. */
    protected Transport(String endpoint) {
        this.endpoint = endpoint;
    }

    /** Where the link goes, as the registry writes it. */
    public final String endpoint() {
        return endpoint;
    }

    /** The counters for this link. */
    public final TransportStats stats() {
        return stats;
    }

    /** True between a successful open and a close. */
    public final boolean isOpen() {
        return open;
    }

    /**
     * Bring the link up. Opening an open link does nothing.
     *
     * @throws io.github.rkaushikdev.nozzlewright.TransportError when the link cannot be opened
     */
    public final synchronized void open() throws InterruptedException {
        if (open) {
            return;
        }
        openLink();
        open = true;
        stats.opened();
    }

    /** Take the link down. Closing a closed link does nothing. */
    @Override
    public final synchronized void close() {
        if (!open) {
            return;
        }
        open = false;
        closeLink();
    }

    /**
     * Write {@code data}.
     *
     * @throws LinkClosed when the link is not open or went away
     */
    public final void send(byte[] data) throws InterruptedException {
        requireOpen();
        try {
            write(data);
        } catch (LinkClosed error) {
            open = false;
            stats.failed();
            throw error;
        }
        stats.wrote(data.length);
    }

    /**
     * Read whatever is available, waiting at most {@code timeout}.
     *
     * <p>Returns empty bytes on a timeout rather than failing, because an idle link is normal
     * between keepalives and the caller loops anyway.
     *
     * @throws LinkClosed when the link is not open or went away
     */
    public final byte[] recv(Duration timeout) throws InterruptedException {
        requireOpen();
        byte[] data;
        try {
            data = read(timeout);
        } catch (LinkClosed error) {
            open = false;
            stats.failed();
            throw error;
        }
        if (data.length == 0) {
            stats.timedOut();
            return data;
        }
        stats.read(data.length);
        return data;
    }

    private void requireOpen() {
        if (!open) {
            throw new LinkClosed("link is not open", endpoint);
        }
    }

    @Override
    public String toString() {
        return "<" + getClass().getSimpleName() + " " + endpoint + " " + (open ? "open" : "closed") + ">";
    }

    /** Do whatever it takes to make the link usable. */
    protected abstract void openLink() throws InterruptedException;

    /** Release the underlying resource. Must not fail. */
    protected abstract void closeLink();

    /** Put {@code data} on the link. */
    protected abstract void write(byte[] data) throws InterruptedException;

    /** Bytes from the link, or empty bytes if nothing arrived in time. */
    protected abstract byte[] read(Duration timeout) throws InterruptedException;
}
