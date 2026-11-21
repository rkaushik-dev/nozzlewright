package io.github.rkaushikdev.nozzlewright.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Running several blocking calls at once and gathering what each one ended with. */
public final class Concurrently {

    /** What one call ended with: a value or a failure. */
    public record Outcome<T>(T value, Throwable failure) {

        /** True when the call returned. */
        public boolean ok() {
            return failure == null;
        }
    }

    private Concurrently() {}

    /** Start every call on its own thread, now. */
    public static <T> List<CompletableFuture<T>> start(List<Callable<T>> calls) {
        List<CompletableFuture<T>> started = new ArrayList<>();
        for (Callable<T> call : calls) {
            CompletableFuture<T> future = new CompletableFuture<>();
            Thread.ofPlatform().daemon().start(() -> {
                try {
                    future.complete(call.call());
                } catch (Exception | Error error) {
                    future.completeExceptionally(error);
                }
            });
            started.add(future);
        }
        return started;
    }

    /** Start one call on its own thread, now. */
    public static <T> CompletableFuture<T> start(Callable<T> call) {
        return start(List.of(call)).get(0);
    }

    /** Run {@code count} copies of {@code call} at once and wait for every one of them. */
    public static <T> List<Outcome<T>> run(int count, Callable<T> call) {
        List<Callable<T>> calls = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            calls.add(call);
        }
        return gather(start(calls));
    }

    /** Wait for every future and say how each ended. */
    public static <T> List<Outcome<T>> gather(List<CompletableFuture<T>> futures) {
        List<Outcome<T>> outcomes = new ArrayList<>();
        for (CompletableFuture<T> future : futures) {
            try {
                outcomes.add(new Outcome<>(future.get(), null));
            } catch (ExecutionException | CompletionException error) {
                outcomes.add(new Outcome<>(null, error.getCause()));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                outcomes.add(new Outcome<>(null, error));
            }
        }
        return outcomes;
    }
}
