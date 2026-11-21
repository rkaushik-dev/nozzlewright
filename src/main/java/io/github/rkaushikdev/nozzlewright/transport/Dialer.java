package io.github.rkaushikdev.nozzlewright.transport;

import io.github.rkaushikdev.nozzlewright.TransportError;
import io.github.rkaushikdev.nozzlewright.util.Durations;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Getting a link up, retrying on a schedule.
 *
 * <p>The transports make one attempt and report what happened. Deciding to try again, how long to
 * wait and when to stop is this class, because the answer depends on where the controller runs.
 * The handheld in an operator's pocket keeps trying forever at a slow rate; a rollout job with forty
 * machines to visit gives up on one and moves on.
 *
 * <p>Backoff is the schedule from the settings, with the last entry repeating, plus a small jitter
 * so a rack of gateways that all lost the switch at once do not come back in lockstep. Each attempt
 * gets a fresh transport, because a socket that failed to connect is not reusable.
 */
public final class Dialer {

    /** Called after every failed attempt with the attempt number and the fault. */
    @FunctionalInterface
    public interface FailureHook {

        /** Attempt {@code attempt} failed with {@code error}. */
        void failed(int attempt, Exception error) throws InterruptedException;
    }

    private final Supplier<? extends Transport> factory;
    private final List<Double> backoffSeconds;
    private final double jitter;
    private final int maxAttempts;
    private final FailureHook onFailure;
    private Random random = new Random();

    private Dialer(Builder builder) {
        if (builder.backoffSeconds.isEmpty()) {
            throw new IllegalArgumentException("backoff schedule must not be empty");
        }
        if (!(builder.jitter >= 0.0 && builder.jitter < 1.0)) {
            throw new IllegalArgumentException("jitter must sit between zero and one");
        }
        this.factory = builder.factory;
        this.backoffSeconds = List.copyOf(builder.backoffSeconds);
        this.jitter = builder.jitter;
        this.maxAttempts = builder.maxAttempts;
        this.onFailure = builder.onFailure;
    }

    /** A dialer over {@code factory} with the default schedule, a fifth of jitter and no limit. */
    public static Builder builder(Supplier<? extends Transport> factory) {
        return new Builder(factory);
    }

    /** Fix the jitter sequence, so a test sees the same delays every run. */
    public synchronized void seed(long value) {
        random = new Random(value);
    }

    /**
     * The wait in seconds before attempt number {@code attempt}, counting from one.
     *
     * @throws IllegalArgumentException when {@code attempt} is below one
     */
    public synchronized double delayFor(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempts are counted from one");
        }
        int index = Math.min(attempt, backoffSeconds.size()) - 1;
        double base = backoffSeconds.get(index);
        if (jitter == 0.0) {
            return base;
        }
        double spread = base * jitter;
        return base - spread + 2 * spread * random.nextDouble();
    }

    /** Keep trying until a link comes up or the attempt budget runs out. */
    public DialOutcome dial() throws InterruptedException {
        int attempts = 0;
        double waited = 0.0;
        Exception lastError = null;
        while (true) {
            attempts++;
            Transport transport = factory.get();
            try {
                transport.open();
            } catch (TransportError error) {
                lastError = error;
                transport.close();
                if (onFailure != null) {
                    onFailure.failed(attempts, error);
                }
                if (maxAttempts > 0 && attempts >= maxAttempts) {
                    return new DialOutcome(Optional.empty(), attempts, waited, Optional.of(lastError));
                }
                double delay = delayFor(attempts);
                waited += delay;
                Thread.sleep(Durations.ofSeconds(delay));
                continue;
            }
            return new DialOutcome(Optional.of(transport), attempts, waited, Optional.ofNullable(lastError));
        }
    }

    /** Assembles a {@link Dialer}. */
    public static final class Builder {
        private final Supplier<? extends Transport> factory;
        private List<Double> backoffSeconds = List.of(0.5, 1.0, 2.0, 5.0, 10.0);
        private double jitter = 0.2;
        private int maxAttempts;
        private FailureHook onFailure;

        private Builder(Supplier<? extends Transport> factory) {
            this.factory = factory;
        }

        /** The delays in seconds, the last one repeating. */
        public Builder backoffSeconds(List<Double> delays) {
            backoffSeconds = List.copyOf(delays);
            return this;
        }

        /** The delays in seconds, the last one repeating. */
        public Builder backoffSeconds(Double... delays) {
            return backoffSeconds(List.of(delays));
        }

        /** The fraction each delay may move either way, zero up to but not including one. */
        public Builder jitter(double value) {
            jitter = value;
            return this;
        }

        /** Give up after this many attempts; zero means never. */
        public Builder maxAttempts(int value) {
            maxAttempts = value;
            return this;
        }

        /** Called after every failed attempt. */
        public Builder onFailure(FailureHook hook) {
            onFailure = hook;
            return this;
        }

        /**
         * The dialer.
         *
         * @throws IllegalArgumentException when the schedule is empty or the jitter is out of range
         */
        public Dialer build() {
            return new Dialer(this);
        }
    }
}
