package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Where labels live on the handheld: one directory of {@code .lbl} files plus a JSON index.
 *
 * <p>The index is not the truth, the files are, and the index can be rebuilt from them at any time.
 * That is deliberate: a handheld gets its battery pulled mid write more often than anyone would
 * like, and a corrupt index that can be regenerated is an inconvenience where a corrupt database is a
 * trip to the office.
 *
 * <p>Every save records a content hash and a revision. The hash is what tells us whether the label
 * on the machine is the label we think it is, which matters because an operator can edit a label on
 * the machine display and nothing tells the handheld about it.
 *
 * <p>The store has to be {@link #open opened} before use. It is safe to share between threads.
 * File system failures surface as {@link UncheckedIOException}.
 */
public final class LabelStore {

    /** The index file's name inside the store directory. */
    public static final String INDEX_NAME = "index.json";

    /** The suffix every label file carries. */
    public static final String LABEL_SUFFIX = ".lbl";

    /** The longest label name the machine store takes. */
    public static final int MAX_NAME = 24;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx");

    private final Path root;
    private final Clock clock;
    private Map<String, LabelRecord> index = new HashMap<>();
    private boolean loaded;

    /** A store over {@code root}, stamping entries from the system clock. */
    public LabelStore(Path root) {
        this(root, Clock.systemUTC());
    }

    /** A store over {@code root}, stamping entries from {@code clock}. */
    public LabelStore(Path root, Clock clock) {
        this.root = root;
        this.clock = clock;
    }

    /**
     * A short stable hash of label text: the first sixteen hex digits of its SHA-256.
     *
     * <p>Leading and trailing white space is stripped, trailing white space on every line too, and
     * line endings are normalised first, so a label that has been round tripped through a Windows
     * editor still matches the one on the machine.
     */
    public static String contentHash(String source) {
        String normalised =
                Text.lines(Text.strip(source)).stream().map(Text::stripTrailing).collect(Collectors.joining("\n"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalised.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("this platform has no SHA-256", error);
        }
    }

    /** The store directory. */
    public Path root() {
        return root;
    }

    /** Where the index file sits. */
    public Path indexPath() {
        return root.resolve(INDEX_NAME);
    }

    /**
     * Where one label sits.
     *
     * @throws LabelError when the name is empty, longer than {@link #MAX_NAME}, or holds anything but
     *     letters, digits, {@code -}, {@code _} and {@code .}
     */
    public Path pathFor(String name) {
        return root.resolve(checkName(name) + LABEL_SUFFIX);
    }

    /** Create the directory and read the index, rebuilding it if it is gone or cannot be read. */
    public synchronized void open() {
        createRoot();
        Path path = indexPath();
        if (!Files.exists(path)) {
            rebuild();
            return;
        }
        Optional<Map<String, LabelRecord>> read;
        try {
            read = LabelIndex.read(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException error) {
            read = Optional.empty();
        }
        if (read.isEmpty()) {
            rebuild();
            return;
        }
        index = new HashMap<>(read.get());
        loaded = true;
    }

    /**
     * Read every label file and build the index from scratch.
     *
     * <p>A file that cannot be read or does not parse is skipped. A label is indexed under the name
     * its LABEL line gives, and keeps the revision the index already had for it.
     */
    public synchronized void rebuild() {
        createRoot();
        Map<String, LabelRecord> found = new HashMap<>();
        for (Path path : labelFiles()) {
            String source;
            LabelTemplate template;
            try {
                source = Files.readString(path, StandardCharsets.UTF_8);
                template = LabelTemplate.parse(source);
            } catch (IOException | LabelError error) {
                continue;
            }
            LabelRecord previous = index.get(template.name());
            found.put(template.name(), entryFor(template, source, previous == null ? 1 : previous.revision()));
        }
        index = found;
        loaded = true;
        writeIndex();
    }

    /** Every label held, sorted. */
    public synchronized List<String> names() {
        requireOpen();
        return List.copyOf(new TreeSet<>(index.keySet()));
    }

    /** Every index entry, sorted by name. */
    public synchronized List<LabelRecord> records() {
        requireOpen();
        return new TreeSet<>(index.keySet()).stream().map(index::get).toList();
    }

    /**
     * One index entry.
     *
     * @throws LabelError when the store holds no label of that name
     */
    public synchronized LabelRecord record(String name) {
        requireOpen();
        LabelRecord found = index.get(name);
        if (found == null) {
            throw noSuchLabel(name);
        }
        return found;
    }

    /** True when the store holds this label. */
    public synchronized boolean has(String name) {
        requireOpen();
        return index.containsKey(name);
    }

    /**
     * Read one label off the disk.
     *
     * @throws LabelError when there is no such file or it does not parse
     */
    public synchronized LabelTemplate load(String name) {
        requireOpen();
        Path path = pathFor(name);
        if (!Files.exists(path)) {
            throw noSuchLabel(name);
        }
        try {
            return LabelTemplate.parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /**
     * Write a label and update the index.
     *
     * <p>The revision only moves when the content actually changes. Saving the same label twice is
     * what happens every time a screen is closed, and a revision that climbs on every close tells
     * nobody anything, so an unchanged label is not written again and its entry comes back as it was.
     *
     * @throws LabelError when the template does not validate or its name is not one the store takes
     */
    public synchronized LabelRecord save(LabelTemplate template) {
        requireOpen();
        template.validate();
        String source = template.toSource();
        LabelRecord previous = index.get(template.name());
        String digest = contentHash(source);
        if (previous != null && previous.digest().equals(digest)) {
            return previous;
        }
        int revision = previous == null ? 1 : previous.revision() + 1;
        Path path = pathFor(template.name());
        write(path, source);
        LabelRecord entry = entryFor(template, source, revision);
        index.put(template.name(), entry);
        writeIndex();
        return entry;
    }

    /**
     * Remove a label and its index entry.
     *
     * @throws LabelError when the store holds no label of that name
     */
    public synchronized void delete(String name) {
        requireOpen();
        if (!index.containsKey(name)) {
            throw noSuchLabel(name);
        }
        try {
            Files.deleteIfExists(pathFor(name));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        index.remove(name);
        writeIndex();
    }

    /** Labels held here that the machine does not have, sorted. */
    public synchronized List<String> missingFrom(Collection<String> machineNames) {
        requireOpen();
        TreeSet<String> missing = new TreeSet<>(index.keySet());
        missing.removeAll(machineNames);
        return List.copyOf(missing);
    }

    /** Labels the machine has that are not held here, sorted. */
    public synchronized List<String> extraOn(Collection<String> machineNames) {
        requireOpen();
        TreeSet<String> extra = new TreeSet<>(machineNames);
        extra.removeAll(index.keySet());
        return List.copyOf(extra);
    }

    private LabelRecord entryFor(LabelTemplate template, String source, int revision) {
        String stamp = OffsetDateTime.now(clock)
                .withOffsetSameInstant(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.SECONDS)
                .format(STAMP);
        return new LabelRecord(
                template.name(),
                revision,
                contentHash(source),
                template.heightDots(),
                stamp,
                !template.interactiveFields().isEmpty());
    }

    /** Every file in the store whose name ends in the label suffix, sorted by name. */
    private List<Path> labelFiles() {
        try (Stream<Path> entries = Files.list(root)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(LABEL_SUFFIX))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void writeIndex() {
        write(indexPath(), LabelIndex.write(records()));
    }

    private void createRoot() {
        try {
            Files.createDirectories(root);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void write(Path path, String text) {
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void requireOpen() {
        if (!loaded) {
            throw new LabelError("the label store has not been opened");
        }
    }

    private static LabelError noSuchLabel(String name) {
        return new LabelError("no label called " + Repr.of(name) + " in the store");
    }

    private static String checkName(String name) {
        String cleaned = Text.strip(name);
        if (cleaned.isEmpty()) {
            throw new LabelError("a label name cannot be empty");
        }
        if (Text.length(cleaned) > MAX_NAME) {
            throw new LabelError(
                    "label name " + Repr.of(name) + " is longer than the " + MAX_NAME + " the machine store allows");
        }
        if (!cleaned.codePoints().allMatch(LabelStore::allowedInName)) {
            throw new LabelError("label name " + Repr.of(name) + " has characters the machine store rejects");
        }
        return cleaned;
    }

    /** A letter or a number in any script, or one of {@code - _ .}. */
    private static boolean allowedInName(int point) {
        if (point == '-' || point == '_' || point == '.') {
            return true;
        }
        if (Character.isLetter(point)) {
            return true;
        }
        int type = Character.getType(point);
        return type == Character.DECIMAL_DIGIT_NUMBER
                || type == Character.LETTER_NUMBER
                || type == Character.OTHER_NUMBER;
    }
}
