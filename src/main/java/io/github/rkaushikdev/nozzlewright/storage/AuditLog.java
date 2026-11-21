package io.github.rkaushikdev.nozzlewright.storage;

import io.github.rkaushikdev.nozzlewright.printer.ConfigDiff;
import io.github.rkaushikdev.nozzlewright.printer.FieldChange;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import io.github.rkaushikdev.nozzlewright.util.Times;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Writing down what people did.
 *
 * <p>Everything that changes a machine goes through here, and the list is meant to stay short:
 * settings pushed, labels selected or stored, runs started and stopped, alarms acknowledged,
 * machines added and removed. What the machine did on its own is deliberately not audited; a head
 * fault is not somebody's action, and putting it here would bury the handful of rows an auditor
 * wants under a shift of noise.
 *
 * <p>Values are trimmed rather than refused. An audit row that failed to write because a field was
 * too long is the worst possible outcome: the change still happened and now nothing says so. A
 * trimmed value ends in an ellipsis.
 */
public final class AuditLog {

    private static final int FIELD = 255;
    private static final int SUBJECT = 64;
    private static final int NAME = 32;
    private static final String ELLIPSIS = "…";

    private final UnitOfWork unit;
    private final String actor;

    /** The audit table, written by nobody in particular. */
    public AuditLog(UnitOfWork unit) {
        this(unit, "");
    }

    /** The audit table, written by {@code actor} unless an entry names someone else. */
    public AuditLog(UnitOfWork unit, String actor) {
        this.unit = unit;
        this.actor = actor;
    }

    /** Write one row of {@code action} now, about nothing in particular. */
    public AuditEntry record(String action) {
        return entry(action).write();
    }

    /** Start a row of {@code action}; fill in what it is about and {@link Draft#write} it. */
    public Draft entry(String action) {
        return new Draft(action);
    }

    /** One row per setting a configuration push changed, by this log's actor. */
    public List<AuditEntry> recordDiff(String printerId, ConfigDiff diff) {
        return recordDiff(printerId, diff, actor);
    }

    /**
     * One row per setting a configuration push changed.
     *
     * <p>A row per field rather than a row per push. An auditor asking who changed the firing voltage
     * wants to find it by searching for the field, not by opening every push that happened that week.
     * The command that made the change goes in the detail.
     */
    public List<AuditEntry> recordDiff(String printerId, ConfigDiff diff, String by) {
        List<AuditEntry> entries = new ArrayList<>();
        for (FieldChange change : diff.changes()) {
            entries.add(entry(AuditAction.CONFIG_APPLIED)
                    .printerId(printerId)
                    .subject(change.path())
                    .before(change.before())
                    .after(change.after())
                    .detail(change.verb())
                    .actor(by)
                    .write());
        }
        return List.copyOf(entries);
    }

    /** The newest hundred entries, newest first. */
    public List<AuditEntry> recent() {
        return recent(100);
    }

    /** The newest entries, newest first; entries at the same moment newest written first. */
    public List<AuditEntry> recent(int limit) {
        return unit.entities()
                .createQuery("select a from AuditEntry a order by a.at desc, a.id desc", AuditEntry.class)
                .setMaxResults(limit)
                .getResultList();
    }

    /** The newest hundred entries about one machine, newest first. */
    public List<AuditEntry> forPrinter(String printerId) {
        return forPrinter(printerId, 100);
    }

    /** Entries about one machine, newest first. They outlive the machine. */
    public List<AuditEntry> forPrinter(String printerId, int limit) {
        return newestWhere("a.printerId = :value", printerId, limit);
    }

    /** The newest hundred entries by one person, newest first. */
    public List<AuditEntry> byActor(String who) {
        return byActor(who, 100);
    }

    /** Entries by one person, newest first. */
    public List<AuditEntry> byActor(String who, int limit) {
        return newestWhere("a.actor = :value", who, limit);
    }

    /** The newest hundred entries of one kind, newest first. */
    public List<AuditEntry> ofAction(String action) {
        return ofAction(action, 100);
    }

    /** Entries of one kind, newest first. */
    public List<AuditEntry> ofAction(String action, int limit) {
        return newestWhere("a.action = :value", action, limit);
    }

    /** Entries inside a window, both ends included, oldest first. */
    public List<AuditEntry> between(Instant start, Instant end) {
        return unit.entities()
                .createQuery(
                        "select a from AuditEntry a where a.at >= :start and a.at <= :end order by a.at, a.id",
                        AuditEntry.class)
                .setParameter("start", start)
                .setParameter("end", end)
                .getResultList();
    }

    private List<AuditEntry> newestWhere(String condition, String value, int limit) {
        return unit.entities()
                .createQuery(
                        "select a from AuditEntry a where " + condition + " order by a.at desc, a.id desc",
                        AuditEntry.class)
                .setParameter("value", value)
                .setMaxResults(limit)
                .getResultList();
    }

    /**
     * One value as stored text: nothing as empty, anything else as it reads, cut to {@code limit}
     * characters with an ellipsis as the last when it had to be cut.
     */
    static String trim(Object value, int limit) {
        String text = textOf(value);
        if (text.codePointCount(0, text.length()) <= limit) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, limit - 1)) + ELLIPSIS;
    }

    private static String textOf(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(AuditLog::textOf).orElse("");
        }
        return Repr.text(value);
    }

    /** One audit row being filled in. Nothing is written until {@link #write}. */
    public final class Draft {

        private final String action;
        private String printerId = "";
        private String subject = "";
        private Object before = "";
        private Object after = "";
        private Object detail = "";
        private Optional<String> by = Optional.empty();
        private Optional<Instant> at = Optional.empty();

        private Draft(String action) {
            this.action = action;
        }

        /** The machine it was done to. */
        public Draft printerId(String value) {
            printerId = value;
            return this;
        }

        /** What on the machine it touched. */
        public Draft subject(String value) {
            subject = value;
            return this;
        }

        /** The value before; nothing is stored as empty. */
        public Draft before(Object value) {
            before = value;
            return this;
        }

        /** The value after; nothing is stored as empty. */
        public Draft after(Object value) {
            after = value;
            return this;
        }

        /** Anything else worth keeping. */
        public Draft detail(Object value) {
            detail = value;
            return this;
        }

        /** Who did it, in place of the log's own actor. */
        public Draft actor(String value) {
            by = Optional.of(value);
            return this;
        }

        /** When it happened, in place of now. */
        public Draft at(Instant value) {
            at = Optional.of(value);
            return this;
        }

        /** Write the row now. */
        public AuditEntry write() {
            AuditEntry entry = new AuditEntry(trim(action, NAME));
            entry.setAt(at.orElseGet(Times::now));
            entry.setActor(trim(by.orElse(actor), NAME));
            entry.setPrinterId(trim(printerId, NAME));
            entry.setSubject(trim(subject, SUBJECT));
            entry.setBefore(trim(before, FIELD));
            entry.setAfter(trim(after, FIELD));
            entry.setDetail(trim(detail, FIELD));
            unit.add(entry);
            unit.flush();
            return entry;
        }
    }
}
