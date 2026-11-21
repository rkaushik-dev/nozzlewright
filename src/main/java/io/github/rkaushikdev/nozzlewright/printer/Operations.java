package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.CommandRejected;
import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.PrinterBusy;
import io.github.rkaushikdev.nozzlewright.protocol.Codec;
import io.github.rkaushikdev.nozzlewright.protocol.Dialect;
import io.github.rkaushikdev.nozzlewright.protocol.Response;
import io.github.rkaushikdev.nozzlewright.protocol.Status;
import io.github.rkaushikdev.nozzlewright.session.PrinterSession;
import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a technician actually does to a machine.
 *
 * <p>The layers below know how to send one command. This is the layer that knows what a job looks
 * like: read the whole configuration, compare it against another machine, push the difference,
 * switch a label, start a run, read the ink.
 *
 * <p>Every operation takes a {@link PrinterSession} and does its own checking first. That ordering
 * matters. Pushing a configuration is a sequence of commands with no transaction behind them, so a
 * change that is going to be refused half way through has to be caught before the first one goes
 * out, or the machine is left in a state that is neither the old one nor the new one.
 */
public final class Operations {

    /**
     * How much label source goes in one frame. The frame limit is four kilobytes and the verb, the
     * name and the counters take some of it.
     */
    public static final int LABEL_CHUNK_BYTES = 1024;

    /** The chunk counter is one byte on the wire. */
    public static final int MAX_LABEL_CHUNKS = 256;

    private static final int DEFAULT_PURGE_DROPS = 200;

    private static final EventLog LOG = EventLog.get("nozzlewright.operations");

    private Operations() {}

    /** Read and decode STATUS. */
    public static PrinterStatus readStatus(PrinterSession session) throws InterruptedException {
        Response response = session.command("STATUS");
        return PrinterStatus.parse(response.record());
    }

    /**
     * Read the whole configuration off the machine and check it.
     *
     * @throws ConfigError when the machine has not identified itself, or what it holds does not
     *     validate
     * @throws io.github.rkaushikdev.nozzlewright.DialectMismatch when its firmware has no CONFIG
     */
    public static PrinterConfig readConfig(PrinterSession session) throws InterruptedException {
        Dialect dialect = session.dialect()
                .orElseThrow(
                        () -> new ConfigError("session", session.printerId(), "the machine has not identified itself"));
        Response response = session.command("CONFIG");
        PrinterConfig config = Snapshot.parseConfigTable(response.table(), dialect.family());
        config.validate();
        return config;
    }

    /**
     * Read the ink table.
     *
     * <p>Uses INK2 where the machine has it, because that is the one that carries the cartridge
     * serial, and falls back to INK where it does not. The readings come back the same shape either
     * way, with the serial simply absent.
     */
    public static List<InkReading> readInk(PrinterSession session) throws InterruptedException {
        boolean second = session.dialect().map(found -> found.supports("INK2")).orElse(false);
        Response response = session.command(second ? "INK2" : "INK");
        return InkReading.parseRows(response.table());
    }

    /** Run a nozzle test of every cartridge and decode it. */
    public static HeadHealth readNozzles(PrinterSession session) throws InterruptedException {
        return readNozzles(session, 0);
    }

    /**
     * Run a nozzle test and decode it.
     *
     * <p>Pen nought means every cartridge, which is what the firmware does with it and what an
     * operator standing at the machine means by the word test.
     *
     * @throws ConfigError when the machine has no nozzle test
     */
    public static HeadHealth readNozzles(PrinterSession session, int pen) throws InterruptedException {
        Optional<Dialect> dialect = session.dialect();
        if (dialect.isPresent() && !dialect.get().supports("NOZTEST")) {
            throw new ConfigError("session", session.printerId(), "this machine has no nozzle test");
        }
        Response response = session.command("NOZTEST", pen);
        return HeadHealth.parse(response.table());
    }

    /** Recover every cartridge with a 200 drop purge, and test again afterwards. */
    public static HeadRecovery recoverHead(PrinterSession session) throws InterruptedException {
        return recoverHead(session, 0, DEFAULT_PURGE_DROPS, true);
    }

    /**
     * Do whatever the nozzle test says the head needs, then look again.
     *
     * <p>Returns the reading it started from and the reading after, so the caller can say whether
     * the purge actually helped. A head that is fine is left alone. A cartridge past saving is not
     * purged at all: spitting two hundred drops through a dead row wastes ink and stains the
     * deflector for nothing, and the answer is a new cartridge. A gap wide enough to want cleaning
     * gets the wiper after the purge, on a machine that has one.
     *
     * @param pen the pen to test and purge, nought for every pen, in which case the worst one is
     *     purged
     * @param drops drops each nozzle throws in the purge
     * @param retest whether to run the nozzle test again afterwards
     */
    public static HeadRecovery recoverHead(PrinterSession session, int pen, int drops, boolean retest)
            throws InterruptedException {
        HeadHealth before = readNozzles(session, pen);
        Advice advice = before.advice();
        if (advice == Advice.NONE || advice == Advice.REPLACE) {
            return new HeadRecovery(before, Optional.empty());
        }

        int target = pen == 0 ? before.worstPen() : pen;
        session.command("PURGE", target, drops);
        boolean wiper = session.dialect().map(found -> found.supports("WIPE")).orElse(true);
        if (advice == Advice.CLEAN && wiper) {
            session.command("WIPE", target);
        }
        LOG.info(
                "head recovery run",
                "printer",
                session.printerId(),
                "pen",
                target,
                "advice",
                advice.value(),
                "drops",
                drops);
        if (!retest) {
            return new HeadRecovery(before, Optional.empty());
        }
        return new HeadRecovery(before, Optional.of(readNozzles(session, pen)));
    }

    /** Read the machine clock against this computer's clock and work out the drift. */
    public static ClockReading readClock(PrinterSession session) throws InterruptedException {
        return readClock(session, Clock.systemDefaultZone());
    }

    /**
     * Read the machine clock and work out the drift.
     *
     * <p>The local time is taken twice, either side of the command, and the midpoint is what the
     * machine time is compared against. On a fast link the two are the same to a millisecond and it
     * costs nothing; on a slow radio it is the difference between a real reading and a second of
     * imaginary drift. Local times are taken to the microsecond.
     */
    public static ClockReading readClock(PrinterSession session, Clock clock) throws InterruptedException {
        LocalDateTime before = now(clock);
        Response response = session.command("GETTIME");
        LocalDateTime after = now(clock);
        Duration roundTrip = Duration.between(before, after);
        LocalDateTime middle = before.plus(half(roundTrip));
        return ClockReading.parse(session.printerId(), response.record(), middle)
                .withRoundTrip(roundTrip);
    }

    /** Set the machine clock from this computer's local time and read it back. */
    public static ClockReading setClock(PrinterSession session) throws InterruptedException {
        return setClock(session, Clock.systemDefaultZone());
    }

    /** Set the machine clock from {@code clock}'s local time and read it back against it. */
    public static ClockReading setClock(PrinterSession session, Clock clock) throws InterruptedException {
        return setClock(session, now(clock), clock);
    }

    /** Set the machine clock to {@code when} and read it back against this computer's clock. */
    public static ClockReading setClock(PrinterSession session, LocalDateTime when) throws InterruptedException {
        return setClock(session, when, Clock.systemDefaultZone());
    }

    /**
     * Set the machine clock to {@code when} and read it back against {@code clock}.
     *
     * <p>Reading back is the point. A SETTIME that the firmware accepted and then quietly rounded, or
     * applied to the wrong field, looks exactly like one that worked, and the only way to tell is to
     * ask.
     */
    public static ClockReading setClock(PrinterSession session, LocalDateTime when, Clock clock)
            throws InterruptedException {
        session.command("SETTIME", ClockReading.formatSetArgs(when).toArray());
        LOG.info("clock set", "printer", session.printerId(), "at", ClockReading.isoFormat(when));
        return readClock(session, clock);
    }

    /** Send one label to a machine in chunks of {@link #LABEL_CHUNK_BYTES}, and say how many it took. */
    public static int pushLabel(PrinterSession session, String name, String source) throws InterruptedException {
        return pushLabel(session, name, source, LABEL_CHUNK_BYTES);
    }

    /**
     * Send one label to a machine, in chunks, and say how many it took.
     *
     * <p>The frame limit is four kilobytes and a label with a lot of elements gets close to it, so
     * this splits rather than hoping. Chunks go one at a time and in order, which the channel
     * underneath guarantees, and the machine only commits the label when the last one lands.
     * Splitting is on characters of the escaped source and not on lines: a label whose source has
     * one very long line is unusual and legal, and a splitter that assumed lines would silently
     * produce a frame the machine drops.
     *
     * @throws ConfigError when the label has no name or no source, the machine cannot take labels,
     *     or the label needs more chunks than the protocol carries
     * @throws IllegalArgumentException when the chunk size is not positive
     */
    public static int pushLabel(PrinterSession session, String name, String source, int chunkBytes)
            throws InterruptedException {
        if (name.isBlank()) {
            throw new ConfigError("label", name, "a label needs a name");
        }
        if (source.isBlank()) {
            throw new ConfigError("label", name, "has no source to send");
        }
        Optional<Dialect> dialect = session.dialect();
        if (dialect.isPresent() && !dialect.get().supports("LABELPUT")) {
            throw new ConfigError("session", session.printerId(), "this machine cannot be sent labels");
        }
        if (chunkBytes <= 0) {
            throw new IllegalArgumentException("chunk size must be positive, got " + chunkBytes);
        }

        String body = Codec.escapeArgument(source);
        List<String> chunks = new ArrayList<>();
        for (int at = 0; at < body.length(); at += chunkBytes) {
            chunks.add(body.substring(at, Math.min(body.length(), at + chunkBytes)));
        }
        if (chunks.size() > MAX_LABEL_CHUNKS) {
            throw new ConfigError(
                    "label", name, "is " + chunks.size() + " chunks and the protocol carries " + MAX_LABEL_CHUNKS);
        }

        for (int index = 0; index < chunks.size(); index++) {
            session.command("LABELPUT", name, index, chunks.size(), chunks.get(index));
        }
        LOG.info("label sent", "printer", session.printerId(), "label", name, "chunks", chunks.size());
        return chunks.size();
    }

    /**
     * Remove a label from a machine.
     *
     * @return false when it was not there
     * @throws CommandRejected when the machine refuses for any other reason, such as the label being
     *     the selected one
     */
    public static boolean deleteLabel(PrinterSession session, String name) throws InterruptedException {
        try {
            session.command("LABELDEL", name);
        } catch (CommandRejected error) {
            if (error.status() == Status.NOT_FOUND.code()) {
                return false;
            }
            throw error;
        }
        LOG.info("label deleted", "printer", session.printerId(), "label", name);
        return true;
    }

    /** Bring the machine in line with {@code target}, refusing if it is printing. */
    public static ApplyReport applyConfig(PrinterSession session, PrinterConfig target) throws InterruptedException {
        return applyConfig(session, target, false, false);
    }

    /**
     * Bring the machine in line with {@code target}.
     *
     * <p>Reads what is there, works out the difference, checks every command against the dialect,
     * and only then starts sending. A verb the machine does not carry is skipped rather than
     * attempted, because half of a geometry change is worse than none of it.
     *
     * @param dryRun work out and report the commands without sending any
     * @param stopFirst stop a machine that is printing instead of refusing
     * @throws ConfigError when the target does not validate
     * @throws PrinterBusy when the machine is printing and {@code stopFirst} is false
     */
    public static ApplyReport applyConfig(
            PrinterSession session, PrinterConfig target, boolean dryRun, boolean stopFirst)
            throws InterruptedException {
        target.validate();
        PrinterStatus status = readStatus(session);
        if (status.state() == MachineState.PRINTING) {
            if (!stopFirst) {
                throw new PrinterBusy(session.printerId() + " is printing, stop the line or pass stop_first");
            }
            session.command("STOPPRINT");
        }

        PrinterConfig current = readConfig(session);
        ConfigDiff diff = current.diff(target);
        return push(session, session.dialect(), diff, dryRun);
    }

    /**
     * Send the commands a diff asks for, skipping what {@code dialect} says the machine lacks.
     *
     * <p>Without a dialect every command is attempted. A refusal is recorded and the rest still go.
     */
    static ApplyReport push(PrinterSession session, Optional<Dialect> dialect, ConfigDiff diff, boolean dryRun)
            throws InterruptedException {
        List<String> applied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<ApplyReport.Failure> failed = new ArrayList<>();

        for (FieldChange change : diff.changes()) {
            if (dialect.isPresent() && !dialect.get().supports(change.verb())) {
                skipped.add(change.path());
                LOG.info(
                        "skipped, the machine has no such verb", "printer", session.printerId(), "verb", change.verb());
                continue;
            }
            if (dryRun) {
                applied.add(change.path());
                continue;
            }
            try {
                session.command(change.verb(), change.args().toArray());
            } catch (CommandRejected error) {
                failed.add(new ApplyReport.Failure(change.path(), error.getMessage()));
                LOG.warning(
                        "setting refused",
                        "printer",
                        session.printerId(),
                        "path",
                        change.path(),
                        "status",
                        error.status());
                continue;
            }
            applied.add(change.path());
        }

        return new ApplyReport(applied, skipped, failed);
    }

    private static LocalDateTime now(Clock clock) {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /** Half a span, to the microsecond, an exact half rounding to the even microsecond. */
    private static Duration half(Duration span) {
        long micros = span.toNanos() / 1000;
        long halved = Math.floorDiv(micros, 2);
        if (Math.floorMod(micros, 2) == 1 && Math.floorMod(halved, 2) == 1) {
            halved++;
        }
        return Duration.of(halved, ChronoUnit.MICROS);
    }
}
