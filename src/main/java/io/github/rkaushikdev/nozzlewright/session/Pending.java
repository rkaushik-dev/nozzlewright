package io.github.rkaushikdev.nozzlewright.session;

import io.github.rkaushikdev.nozzlewright.protocol.Request;
import io.github.rkaushikdev.nozzlewright.protocol.Response;
import java.util.concurrent.CompletableFuture;

/** One command waiting for its turn on the wire. Higher priority first, then the oldest first. */
final class Pending implements Comparable<Pending> {

    final Request request;
    final CompletableFuture<Response> future = new CompletableFuture<>();
    final boolean raiseOnError;
    final Priority priority;
    final long sequence;
    int tries;

    Pending(Request request, boolean raiseOnError, long sequence) {
        this.request = request;
        this.raiseOnError = raiseOnError;
        this.priority = Priority.of(request.verb());
        this.sequence = sequence;
    }

    /** The answer, once there is one. */
    CompletableFuture<Response> future() {
        return future;
    }

    @Override
    public int compareTo(Pending other) {
        if (priority != other.priority) {
            return other.priority.compareTo(priority);
        }
        return Long.compare(sequence, other.sequence);
    }

    /** True when this command goes before {@code other}. */
    boolean before(Pending other) {
        return compareTo(other) < 0;
    }

    void settle(Response response) {
        future.complete(response);
    }

    void fail(RuntimeException error) {
        future.completeExceptionally(error);
    }
}
