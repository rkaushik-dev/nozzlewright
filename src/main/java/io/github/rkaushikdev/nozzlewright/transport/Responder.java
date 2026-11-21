package io.github.rkaushikdev.nozzlewright.transport;

/**
 * The far end of a {@link LoopbackTransport}: called with everything written, it returns the bytes
 * to hand back, which may be empty.
 */
@FunctionalInterface
public interface Responder {

    /** The reply to {@code data}. */
    byte[] respond(byte[] data) throws InterruptedException;
}
