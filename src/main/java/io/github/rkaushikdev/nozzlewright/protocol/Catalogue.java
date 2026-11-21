package io.github.rkaushikdev.nozzlewright.protocol;

import static io.github.rkaushikdev.nozzlewright.protocol.ArgSpec.choice;
import static io.github.rkaushikdev.nozzlewright.protocol.ArgSpec.flag;
import static io.github.rkaushikdev.nozzlewright.protocol.ArgSpec.integer;
import static io.github.rkaushikdev.nozzlewright.protocol.ArgSpec.text;

import io.github.rkaushikdev.nozzlewright.UnknownCommand;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The command catalogue.
 *
 * <p>Every verb the firmware understands is declared here once, with the argument shapes, the
 * operator level it needs, the firmware revision that introduced it and whether it may be sent
 * while the line is running. Nothing else in the controller hands raw text to the transport, which
 * means a typo in a verb is a failing test rather than a machine that quietly ignores the command.
 *
 * <p>The levels come from the Halvex user manual, section 4.2: 0 operator, may print and read; 1
 * supervisor, may edit labels and switch jobs; 2 engineer, may change head geometry and firing; 3
 * factory, may touch calibration constants.
 */
public final class Catalogue {

    /** May print and read. */
    public static final int LEVEL_OPERATOR = 0;

    /** May edit labels and switch jobs. */
    public static final int LEVEL_SUPERVISOR = 1;

    /** May change head geometry and firing. */
    public static final int LEVEL_ENGINEER = 2;

    /** May touch calibration constants. */
    public static final int LEVEL_FACTORY = 3;

    private static final String[] TIJ = {"HVX4PEN", "HVX8PEN"};

    private static final Map<String, CommandSpec> ENTRIES = build();

    private Catalogue() {}

    /** Every verb, in catalogue order, keyed by its verb. */
    public static Map<String, CommandSpec> entries() {
        return ENTRIES;
    }

    /**
     * The spec for {@code verb}.
     *
     * @throws UnknownCommand when the catalogue carries no such verb
     */
    public static CommandSpec lookup(String verb) {
        CommandSpec spec = ENTRIES.get(verb);
        if (spec == null) {
            throw new UnknownCommand("no catalogue entry for " + Repr.of(verb));
        }
        return spec;
    }

    /** Every verb an operator at {@code level} is allowed to send, sorted. */
    public static List<String> verbsForLevel(int level) {
        return ENTRIES.values().stream()
                .filter(spec -> spec.level() <= level)
                .map(CommandSpec::verb)
                .sorted()
                .toList();
    }

    private static Map<String, CommandSpec> build() {
        Map<String, CommandSpec> made = new LinkedHashMap<>();
        for (Entry entry : List.of(
                // Session.
                spec("LOGIN", LEVEL_OPERATOR, ReplyShape.RECORD, text("user", 16), text("pin", 12))
                        .secret("pin"),
                spec("LOGOUT", LEVEL_OPERATOR, ReplyShape.EMPTY),
                spec("PING", LEVEL_OPERATOR, ReplyShape.EMPTY),
                spec("VERSION", LEVEL_OPERATOR, ReplyShape.RECORD),
                spec("STATUS", LEVEL_OPERATOR, ReplyShape.RECORD),
                spec("STATISTICS", LEVEL_OPERATOR, ReplyShape.TABLE),
                spec("GETTIME", LEVEL_OPERATOR, ReplyShape.RECORD),
                spec(
                        "SETTIME",
                        LEVEL_SUPERVISOR,
                        ReplyShape.SCALAR,
                        integer("year", 2000, 2099),
                        integer("month", 1, 12),
                        integer("day", 1, 31),
                        integer("hour", 0, 23),
                        integer("minute", 0, 59),
                        integer("second", 0, 59)),
                spec("MINMAX", LEVEL_OPERATOR, ReplyShape.TABLE),
                // Bulk configuration read. Forty round trips over a radio is a minute of an
                // operator standing still, so revision 2 dumps the whole thing at once.
                spec("CONFIG", LEVEL_SUPERVISOR, ReplyShape.TABLE).revision(2),
                // Print control.
                spec("STARTPRINT", LEVEL_OPERATOR, ReplyShape.EMPTY),
                spec("STOPPRINT", LEVEL_OPERATOR, ReplyShape.EMPTY),
                spec("PAUSEPRINT", LEVEL_OPERATOR, ReplyShape.EMPTY),
                spec(
                                "PRINTMODE",
                                LEVEL_SUPERVISOR,
                                ReplyShape.SCALAR,
                                choice("mode", "STAND", "REPEAT", "SIMUL", "BAR", "DRIVER", "TRACK", "PROTO"))
                        .idleOnly(),
                spec(
                                "MODEREPEAT",
                                LEVEL_SUPERVISOR,
                                ReplyShape.EMPTY,
                                integer("count", 1, 999),
                                integer("gap_mm", 0, 9999))
                        .idleOnly(),
                spec("MODESIMUL", LEVEL_SUPERVISOR, ReplyShape.EMPTY, integer("heads", 1, 8))
                        .families(TIJ)
                        .idleOnly(),
                // Users.
                spec(
                                "ADDUSER",
                                LEVEL_SUPERVISOR,
                                ReplyShape.EMPTY,
                                text("user", 16),
                                text("pin", 12),
                                integer("level", 0, 3))
                        .secret("pin"),
                spec(
                                "UPDATEUSER",
                                LEVEL_SUPERVISOR,
                                ReplyShape.EMPTY,
                                text("user", 16),
                                text("pin", 12),
                                integer("level", 0, 3))
                        .secret("pin"),
                spec("DELETEUSER", LEVEL_SUPERVISOR, ReplyShape.EMPTY, text("user", 16)),
                spec("ENUMUSERS", LEVEL_SUPERVISOR, ReplyShape.TABLE),
                // Labels.
                spec("ENUMLABELS", LEVEL_OPERATOR, ReplyShape.TABLE),
                spec("LABEL", LEVEL_SUPERVISOR, ReplyShape.SCALAR, text("name", 24)),
                spec(
                                "LABELPUT",
                                LEVEL_SUPERVISOR,
                                ReplyShape.SCALAR,
                                text("name", 24),
                                integer("chunk", 0, 255),
                                integer("total", 1, 256),
                                text("payload", 1024))
                        .revision(2)
                        .idleOnly(),
                spec("LABELDEL", LEVEL_SUPERVISOR, ReplyShape.SCALAR, text("name", 24))
                        .revision(2)
                        .idleOnly(),
                spec("ASKINTER", LEVEL_OPERATOR, ReplyShape.TABLE).revision(2),
                spec("SENDINTER", LEVEL_OPERATOR, ReplyShape.EMPTY, text("field", 16), text("value", 64))
                        .revision(2),
                // Geometry.
                spec("HORZRES", LEVEL_ENGINEER, ReplyShape.SCALAR, integer("dpi", 75, 600))
                        .idleOnly(),
                spec("VERTRES", LEVEL_ENGINEER, ReplyShape.SCALAR, integer("dpi", 75, 600))
                        .idleOnly(),
                spec("HEADNUM", LEVEL_ENGINEER, ReplyShape.SCALAR, integer("heads", 1, 8))
                        .families(TIJ)
                        .idleOnly(),
                spec("GROUPNUM", LEVEL_ENGINEER, ReplyShape.SCALAR, integer("groups", 1, 4))
                        .idleOnly(),
                spec(
                                "GROUPORIEN",
                                LEVEL_ENGINEER,
                                ReplyShape.SCALAR,
                                integer("group", 1, 4),
                                choice("orientation", "UP", "DOWN", "LEFT", "RIGHT"))
                        .idleOnly(),
                spec("GROUPDIR", LEVEL_ENGINEER, ReplyShape.SCALAR, integer("group", 1, 4), flag("reverse"))
                        .idleOnly(),
                spec(
                                "GROUPOFF",
                                LEVEL_ENGINEER,
                                ReplyShape.SCALAR,
                                integer("group", 1, 4),
                                integer("offset_dots", 0, 32767))
                        .idleOnly(),
                spec(
                                "GROUPVERT",
                                LEVEL_ENGINEER,
                                ReplyShape.SCALAR,
                                integer("group", 1, 4),
                                integer("offset_dots", -512, 512))
                        .idleOnly(),
                spec(
                                "GROUPSPACE",
                                LEVEL_ENGINEER,
                                ReplyShape.SCALAR,
                                integer("group", 1, 4),
                                integer("spacing_dots", 0, 4096))
                        .idleOnly(),
                spec(
                                "PENHORZOFF",
                                LEVEL_ENGINEER,
                                ReplyShape.SCALAR,
                                integer("pen", 1, 8),
                                integer("offset_dots", -2048, 2048))
                        .families(TIJ)
                        .idleOnly(),
                spec("NOZZLEROW", LEVEL_FACTORY, ReplyShape.SCALAR, integer("pen", 1, 8), integer("rows", 1, 4))
                        .families(TIJ)
                        .idleOnly(),
                // Firing.
                spec(
                                "FIRINGVOL",
                                LEVEL_FACTORY,
                                ReplyShape.SCALAR,
                                integer("pen", 1, 8),
                                integer("millivolts", 6000, 14000))
                        .idleOnly(),
                spec(
                                "PULSELEN",
                                LEVEL_FACTORY,
                                ReplyShape.SCALAR,
                                integer("pen", 1, 8),
                                integer("nanoseconds", 400, 3200))
                        .idleOnly(),
                spec(
                                "PULSEWARM",
                                LEVEL_FACTORY,
                                ReplyShape.SCALAR,
                                integer("pen", 1, 8),
                                integer("nanoseconds", 0, 1600))
                        .idleOnly(),
                spec("HFIRE", LEVEL_ENGINEER, ReplyShape.SCALAR, flag("enabled")),
                // Encoder.
                spec(
                                "SHAFTENC",
                                LEVEL_ENGINEER,
                                ReplyShape.SCALAR,
                                integer("pulses_per_rev", 1, 20000),
                                integer("wheel_mm", 1, 2000))
                        .idleOnly(),
                spec("SENCDIRECT", LEVEL_ENGINEER, ReplyShape.SCALAR, flag("reverse"))
                        .idleOnly(),
                spec("AUTOSPEED", LEVEL_SUPERVISOR, ReplyShape.SCALAR, integer("mm_per_min", 0, 200000)),
                spec("PHOTOSEL", LEVEL_ENGINEER, ReplyShape.SCALAR, integer("channel", 1, 4))
                        .idleOnly(),
                spec("PHOTOMODE", LEVEL_ENGINEER, ReplyShape.SCALAR, choice("mode", "EDGE", "LEVEL", "GATED"))
                        .idleOnly(),
                spec("PHOTOMASK", LEVEL_ENGINEER, ReplyShape.SCALAR, integer("mask_dots", 0, 65535))
                        .revision(2),
                // Cartridge.
                spec("INK", LEVEL_OPERATOR, ReplyShape.TABLE),
                spec("INK2", LEVEL_OPERATOR, ReplyShape.TABLE).revision(2),
                spec("CTRLDATA", LEVEL_FACTORY, ReplyShape.EMPTY, integer("pen", 1, 8), text("payload", 256)),
                spec("NEEDDATA", LEVEL_FACTORY, ReplyShape.SCALAR),
                // Maintenance.
                spec("NOZTEST", LEVEL_SUPERVISOR, ReplyShape.TABLE, integer("pen", 0, 8))
                        .families(TIJ)
                        .idleOnly(),
                spec("PURGE", LEVEL_SUPERVISOR, ReplyShape.SCALAR, integer("pen", 0, 8), integer("drops", 1, 2000))
                        .families(TIJ)
                        .idleOnly(),
                spec("WIPE", LEVEL_ENGINEER, ReplyShape.SCALAR, integer("pen", 0, 8))
                        .families(TIJ)
                        .revision(2)
                        .idleOnly(),
                // Network.
                spec("WIFISCAN", LEVEL_SUPERVISOR, ReplyShape.EMPTY).idleOnly(),
                spec("WIFILIST", LEVEL_SUPERVISOR, ReplyShape.TABLE),
                spec(
                                "PROTOCOL",
                                LEVEL_ENGINEER,
                                ReplyShape.SCALAR,
                                choice("name", "NONE", "MODBUS", "ETHERNETIP", "PROFINET"))
                        .idleOnly(),
                spec(
                                "EXTDEV",
                                LEVEL_ENGINEER,
                                ReplyShape.SCALAR,
                                integer("slot", 1, 4),
                                choice("kind", "NONE", "SCANNER", "SCALE", "PLC"))
                        .idleOnly(),
                spec("TRACKCAM", LEVEL_ENGINEER, ReplyShape.SCALAR, flag("enabled"))
                        .revision(2)
                        .idleOnly())) {
            CommandSpec spec = entry.build();
            made.put(spec.verb(), spec);
        }
        return Collections.unmodifiableMap(made);
    }

    private static Entry spec(String verb, int level, ReplyShape reply, ArgSpec... args) {
        return new Entry(verb, level, reply, List.of(args));
    }

    /** One catalogue line while it is being written down. */
    private static final class Entry {
        private final String verb;
        private final int level;
        private final ReplyShape reply;
        private final List<ArgSpec> args;
        private int minRevision = 1;
        private Set<String> families = Set.of();
        private boolean whilePrinting = true;
        private Set<String> secretArgs = Set.of();

        Entry(String verb, int level, ReplyShape reply, List<ArgSpec> args) {
            this.verb = verb;
            this.level = level;
            this.reply = reply;
            this.args = args;
        }

        Entry revision(int revision) {
            minRevision = revision;
            return this;
        }

        Entry families(String... names) {
            families = Set.of(names);
            return this;
        }

        Entry idleOnly() {
            whilePrinting = false;
            return this;
        }

        Entry secret(String... names) {
            secretArgs = Set.of(names);
            return this;
        }

        CommandSpec build() {
            return new CommandSpec(verb, level, reply, args, minRevision, families, whilePrinting, secretArgs);
        }
    }
}
