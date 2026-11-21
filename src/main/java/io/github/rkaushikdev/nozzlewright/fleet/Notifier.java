package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.printer.Severity;
import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Telling somebody an alarm went up: which alarm changes are worth waking a person for, handed to
 * the sinks that carry them.
 *
 * <p>Three rules, all of them learned from the pager going off at three in the morning for nothing.
 * Repeats are not news: the poller sees the same open alarm every two seconds, so the notifier only
 * speaks when the state of an alarm changes (it went up, it got worse, or it went away), and a quiet
 * period on top of that stops a machine that flaps between fault and running from sending forty
 * messages a minute. Severity decides who hears: info never leaves the building, warnings go out once,
 * and a stop goes out again if nobody has acknowledged it, because that is the one that has the line
 * stopped. Delivery is best effort and says so: a sink that fails is counted and logged, never retried
 * forever and never allowed to stop the poll loop. The alarm table is the record; this is a courtesy
 * on top of it.
 *
 * <p>Safe to use from several polling threads at once.
 */
public final class Notifier {

    /** How long the same alarm on the same machine stays quiet after a message. */
    public static final Duration DEFAULT_QUIET = Duration.ofMinutes(5);

    /** How long a stop nobody has acknowledged waits before it is sent again. */
    public static final Duration DEFAULT_REMINDER = Duration.ofMinutes(20);

    private static final EventLog LOG = EventLog.get("nozzlewright.notify");

    /** What the notifier remembers about one alarm on one machine. */
    private record Seen(Severity severity, Instant sentAt, boolean open) {}

    private final String siteId;
    private final List<Sink> sinks;
    private final Severity floor;
    private final Duration quiet;
    private final Duration reminder;
    private final Clock clock;
    private final NotifierStats stats = new NotifierStats();
    private final Map<Notice.Key, Seen> seen = new HashMap<>();

    private Notifier(Builder builder) {
        this.siteId = builder.siteId;
        this.sinks = List.copyOf(builder.sinks);
        this.floor = builder.floor;
        this.quiet = builder.quiet;
        this.reminder = builder.reminder;
        this.clock = builder.clock;
    }

    /**
     * A notifier for {@code siteId} with no sinks, speaking from warnings up, quiet for five minutes
     * and reminding about stops after twenty.
     */
    public static Builder builder(String siteId) {
        return new Builder(siteId);
    }

    /** The sinks notices are delivered to, in order. */
    public List<Sink> sinks() {
        return sinks;
    }

    /** The least severity that is ever sent. */
    public Severity floor() {
        return floor;
    }

    /** How long the same alarm stays quiet after a message. */
    public Duration quiet() {
        return quiet;
    }

    /** How long an unacknowledged stop waits before it is sent again. */
    public Duration reminder() {
        return reminder;
    }

    /**
     * A notifier with the same rules speaking through other sinks, remembering nothing and counting
     * from nought.
     */
    public Notifier withSinks(List<? extends Sink> values) {
        return builder(siteId)
                .sinks(values)
                .floor(floor)
                .quiet(quiet)
                .reminder(reminder)
                .clock(clock)
                .build();
    }

    /** The site the notices are for. */
    public String siteId() {
        return siteId;
    }

    /** What has gone out and what has not. */
    public NotifierStats stats() {
        return stats;
    }

    /** Whether this alarm state is worth a message now. */
    public Optional<Notice> consider(AlarmState state) {
        return consider(state, clock.instant());
    }

    /**
     * Work out whether this alarm state is worth a message at {@code now}.
     *
     * <p>Empty when the right answer is silence. Clearing is reported for anything that was reported
     * going up, even when it sits under the floor now, so a chat channel does not fill up with faults
     * that never come back down.
     */
    public synchronized Optional<Notice> consider(AlarmState state, Instant now) {
        Notice.Key key = new Notice.Key(state.printerId(), state.alarm());
        Seen before = seen.get(key);

        if (!state.openNow()) {
            if (before == null || !before.open()) {
                return Optional.empty();
            }
            seen.put(key, new Seen(before.severity(), now, false));
            return Optional.of(notice(state, before.severity(), Change.CLEARED, now));
        }

        if (state.severity().rank() < floor.rank()) {
            stats.countSuppressed();
            return Optional.empty();
        }

        Optional<Change> change = changeFor(before, state.severity(), state.acknowledged(), now);
        if (change.isEmpty()) {
            stats.countSuppressed();
            return Optional.empty();
        }

        seen.put(key, new Seen(state.severity(), now, true));
        return Optional.of(notice(state, state.severity(), change.get(), now));
    }

    /**
     * Hand one notice to every sink, and count what got through.
     *
     * <p>Sinks are independent. One that fails does not stop the others, and nothing here fails,
     * because the caller is a poll loop.
     *
     * @return how many sinks carried it
     */
    public int send(Notice notice) throws InterruptedException {
        int delivered = 0;
        for (Sink sink : sinks) {
            try {
                sink.deliver(notice);
            } catch (IOException | RuntimeException error) {
                stats.countFailed();
                LOG.warning(
                        "could not deliver a notice",
                        "sink",
                        sink.name(),
                        "alarm",
                        notice.alarm(),
                        "error",
                        String.valueOf(error.getMessage()));
                continue;
            }
            delivered++;
        }
        if (delivered > 0) {
            stats.countSent();
        }
        return delivered;
    }

    /** Consider a state now and send it in one call. */
    public Optional<Notice> announce(AlarmState state) throws InterruptedException {
        return announce(state, clock.instant());
    }

    /** Consider a state at {@code now} and send it in one call. */
    public Optional<Notice> announce(AlarmState state, Instant now) throws InterruptedException {
        Optional<Notice> notice = consider(state, now);
        if (notice.isPresent()) {
            send(notice.get());
        }
        return notice;
    }

    /**
     * Drop what is remembered about one machine.
     *
     * <p>Called when a machine leaves the registry. Without it the notifier keeps suppressing messages
     * about something that is not there any more, which matters the day it comes back.
     *
     * @return how many alarms were forgotten
     */
    public synchronized int forget(String printerId) {
        List<Notice.Key> gone = new ArrayList<>();
        for (Notice.Key key : seen.keySet()) {
            if (key.printerId().equals(printerId)) {
                gone.add(key);
            }
        }
        gone.forEach(seen::remove);
        return gone.size();
    }

    /** Which kind of change this is, or empty for nothing worth saying. */
    private Optional<Change> changeFor(Seen before, Severity severity, boolean acknowledged, Instant now) {
        if (before == null || !before.open()) {
            return Optional.of(Change.RAISED);
        }
        if (severity.rank() > before.severity().rank()) {
            return Optional.of(Change.WORSENED);
        }
        Duration since = Duration.between(before.sentAt(), now);
        if (since.compareTo(quiet) < 0) {
            return Optional.empty();
        }
        boolean stale = since.compareTo(reminder) >= 0;
        if (severity == Severity.STOP && !acknowledged && stale) {
            return Optional.of(Change.REMINDER);
        }
        return Optional.empty();
    }

    private Notice notice(AlarmState state, Severity severity, Change change, Instant now) {
        return new Notice(
                siteId, state.printerId(), state.alarm(), severity, change, now, state.line(), state.detail());
    }

    /** Assembles a {@link Notifier}. */
    public static final class Builder {
        private final String siteId;
        private final List<Sink> sinks = new ArrayList<>();
        private Severity floor = Severity.WARNING;
        private Duration quiet = DEFAULT_QUIET;
        private Duration reminder = DEFAULT_REMINDER;
        private Clock clock = Clock.systemUTC();

        private Builder(String siteId) {
            this.siteId = siteId;
        }

        /** Add these sinks, delivered to in order. */
        public Builder sinks(Sink... values) {
            sinks.addAll(List.of(values));
            return this;
        }

        /** Add these sinks, delivered to in order. */
        public Builder sinks(List<? extends Sink> values) {
            sinks.addAll(values);
            return this;
        }

        /** The least severity that is ever sent. */
        public Builder floor(Severity value) {
            floor = value;
            return this;
        }

        /** How long the same alarm stays quiet after a message. */
        public Builder quiet(Duration value) {
            quiet = value;
            return this;
        }

        /** How long an unacknowledged stop waits before it is sent again. */
        public Builder reminder(Duration value) {
            reminder = value;
            return this;
        }

        /** Where the time comes from when none is given. */
        public Builder clock(Clock value) {
            clock = value;
            return this;
        }

        /** The notifier. */
        public Notifier build() {
            return new Notifier(this);
        }
    }
}
