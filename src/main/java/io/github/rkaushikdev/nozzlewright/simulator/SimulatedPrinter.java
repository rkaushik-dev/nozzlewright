package io.github.rkaushikdev.nozzlewright.simulator;

import io.github.rkaushikdev.nozzlewright.UnknownCommand;
import io.github.rkaushikdev.nozzlewright.printer.Alarm;
import io.github.rkaushikdev.nozzlewright.printer.Alarms;
import io.github.rkaushikdev.nozzlewright.printer.GroupGeometry;
import io.github.rkaushikdev.nozzlewright.printer.Head;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PenGeometry;
import io.github.rkaushikdev.nozzlewright.printer.PrinterConfig;
import io.github.rkaushikdev.nozzlewright.printer.Snapshot;
import io.github.rkaushikdev.nozzlewright.protocol.Catalogue;
import io.github.rkaushikdev.nozzlewright.protocol.Codec;
import io.github.rkaushikdev.nozzlewright.protocol.CommandSpec;
import io.github.rkaushikdev.nozzlewright.protocol.Framing;
import io.github.rkaushikdev.nozzlewright.protocol.Status;
import io.github.rkaushikdev.nozzlewright.transport.Responder;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * A printer that is not there: one model of the firmware conversation.
 *
 * <p>Every test above the transport used to script its own replies, which meant the tests agreed
 * with each other and not necessarily with a machine. This is the firmware written once, for the
 * session tests, the fleet tests and the desk demo. It is not a simulation of ink physics. It is a
 * simulation of the conversation: what the machine accepts, in what order, at what operator level,
 * and what it refuses. That is the part that breaks, and the part a bench machine is never available
 * to check.
 *
 * <p>Wire it to a {@link io.github.rkaushikdev.nozzlewright.transport.LoopbackTransport} and it looks
 * exactly like a printer to everything above:
 *
 * <pre>{@code
 * SimulatedPrinter machine = SimulatedPrinter.builder().config(config).label("carton", source).build();
 * PrinterSession session = PrinterSession.builder("line4", () -> new LoopbackTransport(machine, "sim"))
 *         .credentials(new Credentials("factory", "9999"))
 *         .build();
 * }</pre>
 *
 * <p>The machine starts READY with three users, {@code mueller}/{@code 4417} at level 2,
 * {@code kraft}/{@code 0000} at level 0 and {@code factory}/{@code 9999} at level 3, unless the
 * builder is given others. Its state can be read and changed from a test while a session is talking
 * to it: every method is safe to call from any thread, and a change is seen by the next command.
 */
public final class SimulatedPrinter implements Responder {

    private static final Set<String> OPEN_VERBS = Set.of("LOGIN", "PING", "VERSION");

    /** A reply before it is framed. */
    private record Reply(int status, String payload) {

        static Reply ok() {
            return new Reply(Status.OK.code(), "");
        }

        static Reply ok(String payload) {
            return new Reply(Status.OK.code(), payload);
        }

        static Reply of(Status status) {
            return new Reply(status.code(), "");
        }
    }

    /** Which nozzle row of which pen. */
    private record NozzleRow(int pen, int row) {}

    private final String family;
    private final int revision;
    private final String firmwareBuild;
    private final Clock clock;
    private final Map<String, SimulatedUser> users;
    private final Map<String, String> labels;
    private final Map<String, String> answered = new LinkedHashMap<>();
    private final Map<Integer, String> cartridgeSerials = new LinkedHashMap<>();
    private final Map<String, Map<Integer, String>> partialLabels = new LinkedHashMap<>();
    private final Map<NozzleRow, List<Integer>> blockedNozzles = new LinkedHashMap<>();
    private final Map<NozzleRow, List<Integer>> burntNozzles = new LinkedHashMap<>();
    private final List<String> seen = new ArrayList<>();
    private PrinterConfig config;
    private MachineState state;
    private Alarms alarms = Alarms.NONE;
    private long printed;
    private String activeLabel = "";
    private double headTemperatureC = 32.5;
    private double lineSpeedMmPerMin;
    private int printRate;
    private List<String> pendingFields;
    private int poweredHours = 812;
    private int purges = 14;
    private int wipes = 3;
    private int faults = 2;
    private int starts;
    private Duration clockOffset = Duration.ZERO;
    private SimulatedUser sessionUser;
    private boolean checksumReplies;

    private SimulatedPrinter(Builder builder) {
        this.family = builder.family;
        this.revision = builder.revision;
        this.firmwareBuild = builder.firmwareBuild;
        this.clock = builder.clock;
        this.config = builder.config;
        this.state = builder.state;
        this.printRate = builder.printRate;
        this.pendingFields = List.copyOf(builder.pendingFields);
        this.checksumReplies = builder.checksumReplies;
        this.labels = new LinkedHashMap<>(builder.labels);
        this.users = new LinkedHashMap<>();
        List<SimulatedUser> given = builder.users.isEmpty()
                ? List.of(
                        new SimulatedUser("mueller", "4417", 2),
                        new SimulatedUser("kraft", "0000", 0),
                        new SimulatedUser("factory", "9999", 3))
                : builder.users;
        for (SimulatedUser user : given) {
            users.put(user.name(), user);
        }
    }

    /** A revision 2 eight pen machine with no configuration loaded and no labels. */
    public SimulatedPrinter() {
        this(builder());
    }

    /** A revision 2 eight pen machine holding {@code config}, with no labels. */
    public SimulatedPrinter(PrinterConfig config) {
        this(builder().config(config));
    }

    /** A builder for a machine with every default. */
    public static Builder builder() {
        return new Builder();
    }

    // The conversation.

    /**
     * Answer one framed command. This is the transport responder.
     *
     * <p>Blank space around the frame and a trailing checksum are removed; bytes outside ASCII read as
     * U+FFFD. An empty frame gets no reply at all.
     *
     * @throws io.github.rkaushikdev.nozzlewright.FrameError when the frame's checksum does not match
     */
    @Override
    public synchronized byte[] respond(byte[] data) {
        byte[] body = Framing.stripChecksum(stripBlank(data));
        StringBuilder decoded = new StringBuilder(body.length);
        for (byte b : body) {
            decoded.append(b >= 0 ? (char) b : '�');
        }
        String text = decoded.toString();
        if (text.isEmpty()) {
            return new byte[0];
        }
        seen.add(text);
        int colon = text.indexOf(':');
        String verb = colon < 0 ? text : text.substring(0, colon);
        String argument = colon < 0 ? "" : text.substring(colon + 1);
        List<String> args = argument.isEmpty() ? List.of() : Arrays.asList(argument.split(",", -1));
        Reply reply = dispatch(verb.toUpperCase(Locale.ROOT), args);
        return Framing.encodeFrame(reply.status() + ":" + reply.payload(), checksumReplies);
    }

    private Reply dispatch(String verb, List<String> args) {
        CommandSpec spec;
        try {
            spec = Catalogue.lookup(verb);
        } catch (UnknownCommand unknown) {
            return Reply.of(Status.UNKNOWN_CMD);
        }
        if (spec.minRevision() > revision || !spec.supportsFamily(family)) {
            return Reply.of(Status.UNKNOWN_CMD);
        }
        Optional<Reply> refused = gate(verb, spec);
        if (refused.isPresent()) {
            return refused.get();
        }
        return switch (verb) {
            case "LOGIN" -> login(args);
            case "LOGOUT" -> logout();
            case "VERSION" -> version();
            case "STATUS" -> status();
            case "STATISTICS" -> statistics();
            case "STARTPRINT" -> startPrint();
            case "STOPPRINT" -> stopPrint();
            case "PAUSEPRINT" -> pausePrint();
            case "ASKINTER" -> askInter();
            case "SENDINTER" -> sendInter(args);
            case "ENUMLABELS" -> enumLabels();
            case "LABEL" -> label(args);
            case "GETTIME" -> getTime();
            case "SETTIME" -> setTime(args);
            case "LABELPUT" -> labelPut(args);
            case "LABELDEL" -> labelDel(args);
            case "CONFIG" -> configTable();
            case "INK" -> ink();
            case "INK2" -> ink2();
            case "NOZTEST" -> nozzleTest(args);
            case "PURGE" -> purge(args);
            case "WIPE" -> wipe(args);
            case "MINMAX" -> minmax();
            default -> setting(verb, args);
        };
    }

    /** The checks the firmware runs before any handler sees a command. */
    private Optional<Reply> gate(String verb, CommandSpec spec) {
        if (!OPEN_VERBS.contains(verb) && sessionUser == null) {
            return Optional.of(Reply.of(Status.NOT_LOGGED_IN));
        }
        if (sessionUser != null && sessionUser.level() < spec.level()) {
            return Optional.of(Reply.of(Status.NO_PERMISSION));
        }
        if (!spec.whilePrinting() && state == MachineState.PRINTING) {
            return Optional.of(Reply.of(Status.CONFIG_LOCKED));
        }
        return Optional.empty();
    }

    /** Remember a setting verb, so a config read gives back what was pushed. */
    private Reply setting(String verb, List<String> args) {
        if (config == null) {
            return Reply.ok();
        }
        try {
            SettingsStore.apply(config, verb, args).ifPresent(updated -> config = updated);
        } catch (BadArgument error) {
            return Reply.of(Status.BAD_PARAM);
        }
        return Reply.ok();
    }

    // Session verbs.

    private Reply login(List<String> args) {
        if (args.size() != 2) {
            return Reply.of(Status.BAD_PARAM);
        }
        SimulatedUser user = users.get(args.get(0));
        if (user == null || !user.pin().equals(args.get(1))) {
            return Reply.of(Status.NOT_FOUND);
        }
        sessionUser = user;
        return Reply.ok("USER=" + user.name() + ",LEVEL=" + user.level());
    }

    private Reply logout() {
        sessionUser = null;
        return Reply.ok();
    }

    private Reply version() {
        return Reply.ok("FAM=" + family + ",REV=" + revision + ",BUILD=" + firmwareBuild);
    }

    private Reply status() {
        if (state == MachineState.PRINTING) {
            printed += printRate;
        }
        return Reply.ok("STATE=" + state.value()
                + ",ALARMS=" + Integer.toHexString(alarms.word()).toUpperCase(Locale.ROOT)
                + ",PRINTED=" + printed
                + ",TEMP=" + Numbers.fixed(headTemperatureC, 1)
                + ",SPEED=" + Numbers.fixed(lineSpeedMmPerMin, 0)
                + ",LABEL=" + activeLabel);
    }

    private Reply statistics() {
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("PRINTS", printed);
        counters.put("HOURS", (long) poweredHours);
        counters.put("PRINTHOURS", Math.max(Math.floorDiv(poweredHours, 3), 0L));
        counters.put("PURGES", (long) purges);
        counters.put("FAULTS", (long) faults);
        counters.put("STARTS", (long) starts);
        return Reply.ok(counters.entrySet().stream()
                .map(entry -> "NAME=" + entry.getKey() + ",VALUE=" + entry.getValue())
                .collect(Collectors.joining(";")));
    }

    // Print control.

    private Reply startPrint() {
        if (activeLabel.isEmpty()) {
            return Reply.of(Status.NOT_FOUND);
        }
        if (state == MachineState.FAULT) {
            return Reply.of(Status.HEAD_FAULT);
        }
        state = MachineState.PRINTING;
        starts++;
        return Reply.ok();
    }

    private Reply stopPrint() {
        state = MachineState.READY;
        return Reply.ok();
    }

    private Reply pausePrint() {
        if (state != MachineState.PRINTING) {
            return Reply.of(Status.BAD_PARAM);
        }
        state = MachineState.PAUSED;
        return Reply.ok();
    }

    // Interactive fields.

    private Reply askInter() {
        List<String> waiting = pendingFields.stream()
                .filter(name -> !answered.containsKey(name))
                .toList();
        if (waiting.isEmpty()) {
            return Reply.ok();
        }
        return Reply.ok(waiting.stream().map(name -> "NAME=" + name).collect(Collectors.joining(";")));
    }

    private Reply sendInter(List<String> args) {
        if (args.size() != 2) {
            return Reply.of(Status.BAD_PARAM);
        }
        if (!pendingFields.contains(args.get(0))) {
            return Reply.of(Status.NOT_FOUND);
        }
        answered.put(args.get(0), args.get(1));
        return Reply.ok();
    }

    // Labels.

    private Reply enumLabels() {
        if (labels.isEmpty()) {
            return Reply.ok();
        }
        return Reply.ok(new TreeSet<>(labels.keySet())
                .stream().map(name -> "NAME=" + name).collect(Collectors.joining(";")));
    }

    private Reply label(List<String> args) {
        if (args.size() != 1) {
            return Reply.of(Status.BAD_PARAM);
        }
        if (!labels.containsKey(args.get(0))) {
            return Reply.of(Status.NOT_FOUND);
        }
        activeLabel = args.get(0);
        // Selecting a label clears whatever was typed for the last one. Carrying answers across a
        // label change is how a carton gets last week's lot code.
        answered.clear();
        return Reply.ok(args.get(0));
    }

    /**
     * Take one chunk of a label. The last one commits it.
     *
     * <p>Chunks are held aside until the whole message has arrived, so a transfer that stops half way
     * leaves the machine holding whatever it had before rather than half a label. A real controller
     * does the same for the same reason: an operator selecting a truncated label at two in the
     * morning is worse than an operator finding it missing. A chunk numbered nought starts the
     * transfer again.
     */
    private Reply labelPut(List<String> args) {
        if (args.size() != 4) {
            return Reply.of(Status.BAD_PARAM);
        }
        String name = args.get(0);
        long chunk;
        long total;
        try {
            chunk = Numbers.parseLong(args.get(1));
            total = Numbers.parseLong(args.get(2));
        } catch (NumberFormatException error) {
            return Reply.of(Status.BAD_PARAM);
        }
        if (chunk < 0 || chunk >= total) {
            return Reply.of(Status.BAD_PARAM);
        }
        Map<Integer, String> held = partialLabels.computeIfAbsent(name, key -> new TreeMap<>());
        if (chunk == 0) {
            held.clear();
        }
        held.put((int) chunk, args.get(3));
        if (held.size() < total) {
            return Reply.ok("HELD=" + held.size());
        }
        labels.put(name, Codec.unescapeArgument(String.join("", held.values())));
        partialLabels.remove(name);
        return Reply.ok("STORED=" + name);
    }

    /** Remove a label. Refuses the one that is selected. */
    private Reply labelDel(List<String> args) {
        if (args.size() != 1) {
            return Reply.of(Status.BAD_PARAM);
        }
        String name = args.get(0);
        if (!labels.containsKey(name)) {
            return Reply.of(Status.NOT_FOUND);
        }
        if (name.equals(activeLabel)) {
            return Reply.of(Status.CONFIG_LOCKED);
        }
        labels.remove(name);
        partialLabels.remove(name);
        return Reply.ok(name);
    }

    // Configuration and the clock.

    /** What the machine thinks the time is, drift included. */
    private Reply getTime() {
        LocalDateTime now = LocalDateTime.now(clock).plus(clockOffset);
        return Reply.ok("YEAR=" + now.getYear() + ",MONTH=" + now.getMonthValue() + ",DAY=" + now.getDayOfMonth()
                + ",HOUR=" + now.getHour() + ",MIN=" + now.getMinute() + ",SEC=" + now.getSecond());
    }

    /** Take a time and remember how far it is from the controller's, to the second. */
    private Reply setTime(List<String> args) {
        if (args.size() != 6) {
            return Reply.of(Status.BAD_PARAM);
        }
        LocalDateTime wanted;
        try {
            int[] parts = new int[6];
            for (int index = 0; index < parts.length; index++) {
                parts[index] = Math.toIntExact(Numbers.parseLong(args.get(index)));
            }
            if (parts[0] < 1 || parts[0] > 9999) {
                return Reply.of(Status.BAD_PARAM);
            }
            wanted = LocalDateTime.of(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]);
        } catch (NumberFormatException | ArithmeticException | DateTimeException error) {
            return Reply.of(Status.BAD_PARAM);
        }
        clockOffset = Duration.between(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS), wanted);
        return Reply.ok("OK");
    }

    private Reply configTable() {
        if (config == null) {
            return Reply.of(Status.NOT_FOUND);
        }
        return Reply.ok(Snapshot.renderConfigTable(config));
    }

    private Reply minmax() {
        return Reply.ok("NAME=HORZRES,MIN=75,MAX=600;NAME=FIRINGVOL,MIN=6000,MAX=14000");
    }

    // Ink and nozzles.

    private Reply ink() {
        List<String> rows = new ArrayList<>();
        for (int pen = 1; pen <= penCount(); pen++) {
            rows.add("PEN=" + pen + ",LVL=" + level(pen));
        }
        return Reply.ok(String.join(";", rows));
    }

    /** The revision two table, which carries the cartridge serial. */
    private Reply ink2() {
        List<String> rows = new ArrayList<>();
        for (int pen = 1; pen <= penCount(); pen++) {
            String serial = cartridgeSerials.getOrDefault(pen, String.format(Locale.ROOT, "SIM%05d", pen * 1111));
            rows.add("PEN=" + pen + ",LVL=" + level(pen) + ",SER=" + serial + ",PART=SLX-2580,INK=MEK");
        }
        return Reply.ok(String.join(";", rows));
    }

    /** A plausible level per pen, falling with the pen number. */
    private static int level(int pen) {
        return Math.max(100 - pen * 7, 0);
    }

    private int penCount() {
        return config == null ? 1 : config.head().penCount();
    }

    /** Answer with a row word per pen, with the blocked nozzles cleared; pen nought means every pen. */
    private Reply nozzleTest(List<String> args) {
        if (args.size() != 1) {
            return Reply.of(Status.BAD_PARAM);
        }
        long wanted;
        try {
            wanted = Numbers.parseLong(args.get(0));
        } catch (NumberFormatException error) {
            return Reply.of(Status.BAD_PARAM);
        }
        int pens = penCount();
        if (wanted != 0 && wanted > pens) {
            return Reply.of(Status.NOT_FOUND);
        }
        List<String> rows = new ArrayList<>();
        for (int pen = 1; pen <= pens; pen++) {
            if (wanted != 0 && pen != wanted) {
                continue;
            }
            for (int row = 0; row < rowsOn(pen); row++) {
                List<Integer> blocked = blockedNozzles.getOrDefault(new NozzleRow(pen, row), List.of());
                rows.add(String.format(Locale.ROOT, "PEN=%d,ROW=%d,BITS=%016X", pen, row, rowWord(blocked)));
            }
        }
        return Reply.ok(String.join(";", rows));
    }

    /**
     * Spit ink through the nozzles, which frees the ones that are close.
     *
     * <p>A purge shifts dried ink and nothing else. A nozzle whose resistor has gone open circuit
     * answers a purge exactly the way it answers everything, which is why the simulator keeps two sets
     * and only clears one of them. Pen nought purges every pen.
     */
    private Reply purge(List<String> args) {
        if (args.size() != 2) {
            return Reply.of(Status.BAD_PARAM);
        }
        long pen;
        long drops;
        try {
            pen = Numbers.parseLong(args.get(0));
            drops = Numbers.parseLong(args.get(1));
        } catch (NumberFormatException error) {
            return Reply.of(Status.BAD_PARAM);
        }
        purges++;
        int freed = 0;
        for (Map.Entry<NozzleRow, List<Integer>> entry : blockedNozzles.entrySet()) {
            if (pen != 0 && entry.getKey().pen() != pen) {
                continue;
            }
            List<Integer> burnt = burntNozzles.getOrDefault(entry.getKey(), List.of());
            List<Integer> surviving =
                    entry.getValue().stream().filter(burnt::contains).toList();
            freed += entry.getValue().size() - surviving.size();
            entry.setValue(surviving);
        }
        return Reply.ok("FREED=" + freed + ",DROPS=" + drops);
    }

    /** Run the wiper over the plate. Counts, and frees nothing on its own. */
    private Reply wipe(List<String> args) {
        if (args.size() != 1) {
            return Reply.of(Status.BAD_PARAM);
        }
        wipes++;
        return Reply.ok("OK");
    }

    /** How many nozzle rows a cartridge has: two unless the configuration says otherwise. */
    private int rowsOn(int pen) {
        if (config == null) {
            return 2;
        }
        for (GroupGeometry group : config.head().groups()) {
            for (PenGeometry geometry : group.pens()) {
                if (geometry.index() == pen) {
                    return geometry.rows();
                }
            }
        }
        return 2;
    }

    /** A nozzle row word with the blocked nozzles cleared. */
    private static long rowWord(List<Integer> blocked) {
        long word = (1L << Head.NOZZLES_PER_ROW) - 1;
        for (int nozzle : blocked) {
            word &= ~(1L << nozzle);
        }
        return word;
    }

    /** {@code data} without the ASCII blank space a frame may carry around it. */
    private static byte[] stripBlank(byte[] data) {
        int start = 0;
        int end = data.length;
        while (start < end && isBlank(data[start])) {
            start++;
        }
        while (end > start && isBlank(data[end - 1])) {
            end--;
        }
        return Arrays.copyOfRange(data, start, end);
    }

    private static boolean isBlank(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r' || b == 0x0B || b == 0x0C;
    }

    // What a test can read and change.

    /** The machine family it reports at VERSION. */
    public String family() {
        return family;
    }

    /** The firmware revision it reports at VERSION. */
    public int revision() {
        return revision;
    }

    /** The build string it reports at VERSION. */
    public String firmwareBuild() {
        return firmwareBuild;
    }

    /** The configuration it holds, when one was loaded. */
    public synchronized Optional<PrinterConfig> config() {
        return Optional.ofNullable(config);
    }

    /** Load a configuration, as a technician restoring a machine would. */
    public synchronized void config(PrinterConfig value) {
        config = value;
    }

    /** What the machine is doing. */
    public synchronized MachineState state() {
        return state;
    }

    /** Put the machine in a state, as the line or the operator would. */
    public synchronized void state(MachineState value) {
        state = value;
    }

    /** The raised alarm bits. */
    public synchronized Alarms alarms() {
        return alarms;
    }

    /** Set the alarm bits exactly, without changing the state. */
    public synchronized void alarms(Alarms value) {
        alarms = value;
    }

    /** Set an alarm bit, as a fault on the machine would. A head fault or an empty pen faults it. */
    public synchronized void raiseAlarm(Alarm alarm) {
        alarms = alarms.with(alarm);
        if (alarm == Alarm.HEAD_FAULT || alarm == Alarm.INK_OUT) {
            state = MachineState.FAULT;
        }
    }

    /** Clear every bit and come back to ready if the machine was in fault. */
    public synchronized void clearAlarms() {
        alarms = Alarms.NONE;
        if (state == MachineState.FAULT) {
            state = MachineState.READY;
        }
    }

    /** The print counter. */
    public synchronized long printed() {
        return printed;
    }

    /** Set the print counter. */
    public synchronized void printed(long value) {
        printed = value;
    }

    /**
     * Products coded between two STATUS reads while the line runs. A real machine counts on the
     * photocell; this counts on being asked, which is close enough for anything above the wire.
     */
    public synchronized int printRate() {
        return printRate;
    }

    /** Change how many products each STATUS read adds while printing. */
    public synchronized void printRate(int value) {
        printRate = value;
    }

    /** The selected label, empty when there is none. */
    public synchronized String activeLabel() {
        return activeLabel;
    }

    /** Select a label without a command, as the machine display would. */
    public synchronized void activeLabel(String value) {
        activeLabel = value;
    }

    /** Set the head temperature STATUS reports. */
    public synchronized void headTemperatureC(double value) {
        headTemperatureC = value;
    }

    /** Set the line speed STATUS reports. */
    public synchronized void lineSpeedMmPerMin(double value) {
        lineSpeedMmPerMin = value;
    }

    /** The field names the machine waits on for the active label. */
    public synchronized List<String> pendingFields() {
        return pendingFields;
    }

    /** Change the field names the machine waits on. */
    public synchronized void pendingFields(List<String> value) {
        pendingFields = List.copyOf(value);
    }

    /** The prompt answers typed for the active label. */
    public synchronized Map<String, String> answered() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(answered));
    }

    /** The label store, by name, in the order labels arrived. */
    public synchronized Map<String, String> labels() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(labels));
    }

    /** Store a label without a transfer. */
    public synchronized void putLabel(String name, String source) {
        labels.put(name, source);
    }

    /** Chunks of labels that have not finished arriving, by label name and chunk number. */
    public synchronized Map<String, Map<Integer, String>> partialLabels() {
        Map<String, Map<Integer, String>> copy = new LinkedHashMap<>();
        partialLabels.forEach((name, chunks) -> copy.put(name, Collections.unmodifiableMap(new TreeMap<>(chunks))));
        return Collections.unmodifiableMap(copy);
    }

    /** Give one pen the cartridge serial the revision two ink table reports. */
    public synchronized void cartridgeSerial(int pen, String serial) {
        cartridgeSerials.put(pen, serial);
    }

    /** Mark these nozzles on one row as dried up, replacing any marked before. A purge frees them. */
    public synchronized void blockNozzles(int pen, int row, int... nozzles) {
        blockedNozzles.put(new NozzleRow(pen, row), boxed(nozzles));
    }

    /** The nozzles on one row that are not firing, empty when none are marked. */
    public synchronized List<Integer> blockedNozzles(int pen, int row) {
        return blockedNozzles.getOrDefault(new NozzleRow(pen, row), List.of());
    }

    /** Mark these nozzles on one row as burnt out, replacing any marked before. Nothing frees them. */
    public synchronized void burnNozzles(int pen, int row, int... nozzles) {
        burntNozzles.put(new NozzleRow(pen, row), boxed(nozzles));
    }

    /** The machine's own power on hours. */
    public synchronized int poweredHours() {
        return poweredHours;
    }

    /** Set the power on hours. */
    public synchronized void poweredHours(int value) {
        poweredHours = value;
    }

    /** Purges the machine has done, which a PURGE adds to. */
    public synchronized int purges() {
        return purges;
    }

    /** Wipes the machine has done, which a WIPE adds to. */
    public synchronized int wipes() {
        return wipes;
    }

    /** Faults the machine has logged. */
    public synchronized int faults() {
        return faults;
    }

    /** Set the fault counter. */
    public synchronized void faults(int value) {
        faults = value;
    }

    /** Print runs started, which a STARTPRINT adds to. */
    public synchronized int starts() {
        return starts;
    }

    /** How far the machine clock is from the controller's; a flat battery is a big one. */
    public synchronized Duration clockOffset() {
        return clockOffset;
    }

    /** Put the machine clock this far from the controller's. */
    public synchronized void clockOffset(Duration value) {
        clockOffset = value;
    }

    /** The user table, by name. */
    public synchronized Map<String, SimulatedUser> users() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(users));
    }

    /** Who is logged in, if anyone. */
    public synchronized Optional<SimulatedUser> sessionUser() {
        return Optional.ofNullable(sessionUser);
    }

    /** True while a session is open. */
    public synchronized boolean loggedIn() {
        return sessionUser != null;
    }

    /** True when replies carry a checksum. */
    public synchronized boolean checksumReplies() {
        return checksumReplies;
    }

    /** Turn the checksum on replies on or off. */
    public synchronized void checksumReplies(boolean value) {
        checksumReplies = value;
    }

    /** Every command text received, checksum removed, in order. */
    public synchronized List<String> seen() {
        return List.copyOf(seen);
    }

    private static List<Integer> boxed(int... nozzles) {
        return Arrays.stream(nozzles).boxed().toList();
    }

    @Override
    public synchronized String toString() {
        return "SimulatedPrinter[" + family + " rev " + revision + ", " + state.value() + "]";
    }

    /** Assembles a {@link SimulatedPrinter}. */
    public static final class Builder {
        private String family = "HVX8PEN";
        private int revision = 2;
        private String firmwareBuild = "2.4.118";
        private PrinterConfig config;
        private final List<SimulatedUser> users = new ArrayList<>();
        private final Map<String, String> labels = new LinkedHashMap<>();
        private MachineState state = MachineState.READY;
        private int printRate = 1;
        private List<String> pendingFields = List.of();
        private boolean checksumReplies = true;
        private Clock clock = Clock.systemDefaultZone();

        private Builder() {}

        /** The machine family, {@code HVX8PEN} unless set. */
        public Builder family(String value) {
            family = value;
            return this;
        }

        /** The firmware revision, 2 unless set. */
        public Builder revision(int value) {
            revision = value;
            return this;
        }

        /** The build string, {@code 2.4.118} unless set. */
        public Builder firmwareBuild(String value) {
            firmwareBuild = value;
            return this;
        }

        /** The configuration the machine holds; without one, CONFIG answers not found. */
        public Builder config(PrinterConfig value) {
            config = value;
            return this;
        }

        /** Add a user. Once any is added, the three default users are not. */
        public Builder user(SimulatedUser value) {
            users.add(value);
            return this;
        }

        /** Store these labels, by name. */
        public Builder labels(Map<String, String> value) {
            labels.putAll(value);
            return this;
        }

        /** Store one label. */
        public Builder label(String name, String source) {
            labels.put(name, source);
            return this;
        }

        /** The state the machine starts in, READY unless set. */
        public Builder state(MachineState value) {
            state = value;
            return this;
        }

        /** Products each STATUS read adds while printing, one unless set. */
        public Builder printRate(int value) {
            printRate = value;
            return this;
        }

        /** The field names the machine waits on for the active label. */
        public Builder pendingFields(String... names) {
            pendingFields = List.of(names);
            return this;
        }

        /** Whether replies carry a checksum, true unless set. */
        public Builder checksumReplies(boolean value) {
            checksumReplies = value;
            return this;
        }

        /** The controller's clock the machine clock is offset from, the system clock unless set. */
        public Builder clock(Clock value) {
            clock = value;
            return this;
        }

        /** The machine. */
        public SimulatedPrinter build() {
            return new SimulatedPrinter(this);
        }
    }
}
