package io.github.rkaushikdev.nozzlewright.cli;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.fleet.CostModel;
import io.github.rkaushikdev.nozzlewright.fleet.Costing;
import io.github.rkaushikdev.nozzlewright.labels.DotMatrix;
import io.github.rkaushikdev.nozzlewright.labels.Element;
import io.github.rkaushikdev.nozzlewright.labels.Field;
import io.github.rkaushikdev.nozzlewright.labels.FieldContext;
import io.github.rkaushikdev.nozzlewright.labels.FontStore;
import io.github.rkaushikdev.nozzlewright.labels.LabelDiff;
import io.github.rkaushikdev.nozzlewright.labels.LabelRecord;
import io.github.rkaushikdev.nozzlewright.labels.LabelStore;
import io.github.rkaushikdev.nozzlewright.labels.LabelTemplate;
import io.github.rkaushikdev.nozzlewright.labels.LintReport;
import io.github.rkaushikdev.nozzlewright.labels.Proof;
import io.github.rkaushikdev.nozzlewright.labels.ProofOptions;
import io.github.rkaushikdev.nozzlewright.labels.Raster;
import io.github.rkaushikdev.nozzlewright.labels.ShiftWindow;
import io.github.rkaushikdev.nozzlewright.printer.CartridgeSpec;
import io.github.rkaushikdev.nozzlewright.printer.InkFamily;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code nozzlewright label}: work on labels without a machine.
 *
 * <p>None of these touch a machine. Checking and proofing a label is what an engineer does at a desk
 * before a change goes anywhere near a line, and needing a printer to do it is the reason label
 * mistakes get found on the line instead. The store commands work on the {@code labels} directory of
 * the state directory, which is what the handheld carries and what a site keeps in version control.
 */
@Command(
        name = "label",
        description = "Work on labels without a machine.",
        subcommands = {
            LabelCommands.Check.class,
            LabelCommands.ProofCommand.class,
            LabelCommands.Cost.class,
            LabelCommands.Format.class,
            LabelCommands.ListLabels.class,
            LabelCommands.Save.class,
            LabelCommands.Diff.class,
            LabelCommands.StoredDiff.class,
            LabelCommands.Show.class,
            LabelCommands.Delete.class
        })
final class LabelCommands extends Group {

    /** The shift pattern most sites run, which a proof uses when nobody has said otherwise. */
    static final List<ShiftWindow> DEFAULT_SHIFTS = List.of(
            new ShiftWindow("A", LocalTime.of(6, 0), LocalTime.of(14, 0)),
            new ShiftWindow("B", LocalTime.of(14, 0), LocalTime.of(22, 0)),
            new ShiftWindow("C", LocalTime.of(22, 0), LocalTime.of(6, 0)));

    /** What an interactive field is answered with when a command renders a label on its own. */
    static final String STAND_IN_ANSWER = "X".repeat(4);

    /** The fonts a machine ships with. */
    static FontStore defaultFonts() {
        return FontStore.stock();
    }

    /**
     * The moment a label is rendered at: now when nothing was given, or the wall clock time typed.
     *
     * @throws LabelError when the text is not a date and time in ISO form
     */
    static LocalDateTime whenOrNow(String at) {
        if (at.isEmpty()) {
            return LocalDateTime.now();
        }
        return IsoTimes.parse(at)
                .map(IsoTimes.Typed::wall)
                .orElseThrow(() -> new LabelError(Repr.of(at) + " is not a date and time in ISO form"));
    }

    /** The label store in the state directory, opened. */
    static LabelStore store(Context context) {
        LabelStore store = new LabelStore(context.stateDir().resolve("labels"));
        store.open();
        return store;
    }

    /** Every field of a label set to {@code counter}, for rendering it at one counter value. */
    static Map<String, Long> counters(LabelTemplate template, long counter) {
        Map<String, Long> counters = new LinkedHashMap<>();
        template.fields().forEach(item -> counters.put(item.name(), counter));
        return counters;
    }

    /** Every interactive field of a label answered with a stand in. */
    static Map<String, String> standIns(LabelTemplate template) {
        Map<String, String> answers = new LinkedHashMap<>();
        template.interactiveFields().forEach(item -> answers.put(item.name(), STAND_IN_ANSWER));
        return answers;
    }

    /** The site's shift pattern, or the usual three. */
    static List<ShiftWindow> shiftsOf(Context context) {
        return context.site().shifts().isEmpty()
                ? DEFAULT_SHIFTS
                : context.site().shifts();
    }

    /**
     * Repeated {@code name=value} answers as a mapping.
     *
     * @throws LabelError when one has no {@code =}
     */
    static Map<String, String> prompts(List<String> values) {
        Map<String, String> answers = new LinkedHashMap<>();
        for (String item : values) {
            int separator = item.indexOf('=');
            if (separator < 0) {
                throw new LabelError("prompt answers look like name=value, got " + Repr.of(item));
            }
            answers.put(item.substring(0, separator).strip(), item.substring(separator + 1));
        }
        return answers;
    }

    private static LabelTemplate parseFile(Path source) {
        return LabelTemplate.parse(Leaf.readText(source));
    }

    /** Parse a label and say what is in it, and with {@code --lint} whether it will print. */
    @Command(name = "check", description = "Parse a label and say what is in it.")
    static final class Check extends Leaf {

        @Parameters(index = "0", paramLabel = "SOURCE", description = "A label file.")
        private Path source;

        @Mixin
        private DeskLint lint;

        @Option(names = "--json", description = "Print the lint report as JSON.")
        private boolean asJson;

        @Override
        public Integer call() {
            LabelTemplate template = parseFile(source);
            if (!lint.enabled) {
                describe(template);
                return Main.OK;
            }
            LintReport report = lint.check(template);
            if (asJson) {
                Output.emit(out(), report.asMap(), true);
            } else {
                describe(template);
                out().println(report.render());
            }
            return report.ok() ? Main.OK : Main.REFUSED;
        }

        private void describe(LabelTemplate template) {
            out().println(template.name() + " is " + template.heightDots() + " dots high");
            for (Field field : template.fields()) {
                out().println("  field " + field.name() + " " + field.getClass().getSimpleName());
            }
            for (Element element : template.elements()) {
                out().println("  element " + element.name() + " at "
                        + element.placement().toSource());
            }
            List<Field> interactive = template.interactiveFields();
            if (!interactive.isEmpty()) {
                out().println("  needs answers for: "
                        + interactive.stream().map(Field::name).collect(Collectors.joining(", ")));
            }
        }
    }

    /** Draw a label as it would print. */
    @Command(name = "proof", description = "Draw a label as it would print.")
    static final class ProofCommand extends Leaf {

        @Parameters(index = "0", paramLabel = "SOURCE", description = "A label file.")
        private Path source;

        @Option(names = "--at", defaultValue = "", description = "Render as if it were this date and time.")
        private String at;

        @Option(names = "--dpi", defaultValue = "300", description = "Firing rate to measure the width against.")
        private int dpi;

        @Option(names = "--answer", description = "Answer a prompt, as name=value.")
        private List<String> answer = new ArrayList<>();

        @Option(names = "--counter", defaultValue = "1", description = "Value to render counters at.")
        private long counter;

        @Option(names = "--plain", description = "No border around the picture.")
        private boolean plain;

        @Override
        public Integer call() {
            LabelTemplate template = parseFile(source);
            FieldContext context = FieldContext.builder(whenOrNow(at))
                    .counters(counters(template, counter))
                    .prompts(prompts(answer))
                    .shifts(DEFAULT_SHIFTS)
                    .build();
            Proof proof = Proof.make(
                    template, defaultFonts(), context, ProofOptions.defaults().withHorizontalDpi(dpi));
            out().println(proof.summary());
            out().println(proof.picture("#", ".", !plain));
            return Main.OK;
        }
    }

    /**
     * What one message costs to run, per thousand codes.
     *
     * <p>The message is rendered first, because the cost is the dot count and there is no other
     * honest way to get it. A label with a counter in it is rendered at one value, which is close
     * enough: the digits change and the dot count barely does.
     */
    @Command(name = "cost", description = "What one message costs to run, per thousand codes.")
    static final class Cost extends Leaf {

        @Parameters(index = "0", paramLabel = "SOURCE", description = "A label file.")
        private Path source;

        @Option(names = "--price", defaultValue = "0.0", description = "Cartridge price, overriding the site file.")
        private double price;

        @Option(
                names = "--purges",
                defaultValue = "-1.0",
                description = "Purges per thousand codes, overriding the site file.")
        private double purges;

        @Option(names = "--at", defaultValue = "", description = "Render as if it were this date and time.")
        private String at;

        @Option(names = "--counter", defaultValue = "1", description = "Value to render counters at.")
        private long counter;

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() {
            Context context = context();
            Optional<CostModel> model = context.site().costing();
            if (price > 0) {
                model = Optional.of(priced(model, price));
            }
            if (model.isEmpty()) {
                throw Output.fail("no cartridge price, put one in the site file or pass --price");
            }
            CostModel chosen = purges >= 0 ? model.get().withPurgesPerThousand(purges) : model.get();

            LabelTemplate template = parseFile(source);
            FieldContext fields = FieldContext.builder(whenOrNow(at))
                    .counters(counters(template, counter))
                    .prompts(standIns(template))
                    .shifts(shiftsOf(context))
                    .build();
            DotMatrix matrix = Raster.render(template.resolve(fields), defaultFonts());
            Output.emit(
                    out(), Costing.costOfLabel(template.name(), matrix, chosen).asMap(), asJson);
            return Main.OK;
        }

        /** A cost model at a price given on the command line. */
        private static CostModel priced(Optional<CostModel> model, double price) {
            if (model.isPresent()) {
                CostModel found = model.get();
                return new CostModel(price, found.spec(), found.dropVolumePl(), found.purgesPerThousand());
            }
            return new CostModel(price, new CartridgeSpec("unpriced", InkFamily.MEK));
        }
    }

    /** Normalise a label file, the way the store would save it. */
    @Command(name = "format", description = "Normalise a label file, the way the store would save it.")
    static final class Format extends Leaf {

        @Parameters(index = "0", paramLabel = "SOURCE", description = "A label file.")
        private Path source;

        @Option(names = "--write", description = "Rewrite the file in place.")
        private boolean write;

        @Override
        public Integer call() {
            String text = parseFile(source).toSource();
            if (write) {
                writeText(source, text);
                out().println("rewrote " + source);
                return Main.OK;
            }
            out().print(text);
            return Main.OK;
        }
    }

    /** The labels this site holds. */
    @Command(name = "list", description = "The labels this site holds.")
    static final class ListLabels extends Leaf {

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (LabelRecord record : store(context()).records()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", record.name());
                row.put("revision", record.revision());
                row.put("height", record.heightDots());
                row.put("interactive", record.interactive());
                row.put("digest", record.digest());
                rows.add(row);
            }
            Output.emit(out(), rows, asJson);
            return Main.OK;
        }
    }

    /** Put a label in the site store. */
    @Command(name = "save", description = "Put a label in the site store.")
    static final class Save extends Leaf {

        @Parameters(index = "0", paramLabel = "SOURCE", description = "A label file to store.")
        private Path source;

        @Mixin
        private DeskLint lint;

        @Override
        public Integer call() {
            LabelTemplate template = parseFile(source);
            if (lint.enabled) {
                lint.check(template).requirePrintable();
            }
            LabelRecord record = store(context()).save(template);
            out().println("saved " + record.name() + " at revision " + record.revision());
            return Main.OK;
        }
    }

    /**
     * Compare two label files and say what actually changed.
     *
     * <p>Exits one when there is anything a quality person has to approve, so a change control script
     * can gate on it.
     */
    @Command(name = "diff", description = "Compare two label files and say what actually changed.")
    static final class Diff extends Leaf {

        @Parameters(index = "0", paramLabel = "LEFT", description = "The revision that is running now.")
        private Path left;

        @Parameters(index = "1", paramLabel = "RIGHT", description = "The revision somebody wants to run.")
        private Path right;

        @Option(names = "--significant", description = "Hide the changes nobody has to sign off.")
        private boolean significantOnly;

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() {
            LabelDiff result = LabelDiff.between(parseFile(left), parseFile(right));
            List<Map<String, Object>> rows = result.asRows();
            if (significantOnly) {
                rows = rows.stream()
                        .filter(row -> Boolean.TRUE.equals(row.get("significant")))
                        .toList();
            }
            if (rows.isEmpty()) {
                out().println(significantOnly ? "nothing that needs signing off" : result.summary());
                return Main.OK;
            }
            Output.emit(out(), rows, asJson);
            if (result.needsSignoff()) {
                err().println(result.summary());
                return Main.REFUSED;
            }
            return Main.OK;
        }
    }

    /** Compare a stored label with a file, without storing the file. */
    @Command(name = "stored-diff", description = "Compare a stored label with a file, without storing the file.")
    static final class StoredDiff extends Leaf {

        @Parameters(index = "0", paramLabel = "NAME", description = "Label in the site store.")
        private String name;

        @Parameters(index = "1", paramLabel = "AGAINST", description = "A label file to compare it with.")
        private Path against;

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() {
            LabelTemplate before = store(context()).load(name);
            LabelTemplate after = parseFile(against);
            if (!after.name().equals(name)) {
                throw Output.fail("that file is " + after.name() + ", not " + name);
            }
            LabelDiff result = LabelDiff.between(before, after);
            if (!result.hasChanges()) {
                out().println(result.summary());
                return Main.OK;
            }
            Output.emit(out(), result.asRows(), asJson);
            return Main.OK;
        }
    }

    /** Print a stored label. */
    @Command(name = "show", description = "Print a stored label.")
    static final class Show extends Leaf {

        @Parameters(index = "0", paramLabel = "NAME", description = "Label to print.")
        private String name;

        @Override
        public Integer call() {
            out().print(store(context()).load(name).toSource());
            return Main.OK;
        }
    }

    /** Remove a label from the site store. */
    @Command(name = "delete", description = "Remove a label from the site store.")
    static final class Delete extends Leaf {

        @Parameters(index = "0", paramLabel = "NAME", description = "Label to remove.")
        private String name;

        @Override
        public Integer call() {
            store(context()).delete(name);
            out().println("deleted " + name);
            return Main.OK;
        }
    }
}
