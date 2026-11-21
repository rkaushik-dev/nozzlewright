package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A directory of profiles, one {@code <name>.profile.json} file each.
 *
 * <p>Files are written sorted and indented the same way every time. IO failures come out as
 * {@link UncheckedIOException}.
 */
public final class ProfileStore {

    /** What every profile file name ends with. */
    public static final String PROFILE_SUFFIX = ".profile.json";

    private final Path root;

    /** A store in {@code root}, which need not exist until the first save. */
    public ProfileStore(Path root) {
        this.root = root;
    }

    /** The directory. */
    public Path root() {
        return root;
    }

    /**
     * Where one profile sits.
     *
     * @throws ConfigError when the name is blank or holds anything but letters, digits, dash and
     *     underscore
     */
    public Path pathFor(String name) {
        String cleaned = name.strip();
        boolean usable = !cleaned.isEmpty()
                && cleaned.codePoints().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '-' || ch == '_');
        if (!usable) {
            throw new ConfigError("profile.name", name, "names hold letters, digits, dash, underscore");
        }
        return root.resolve(cleaned + PROFILE_SUFFIX);
    }

    /** Every profile in the directory, sorted; none when the directory does not exist. */
    public List<String> names() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> listing = Files.list(root)) {
            return listing.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(file -> file.endsWith(PROFILE_SUFFIX))
                    .map(file -> file.substring(0, file.length() - PROFILE_SUFFIX.length()))
                    .sorted()
                    .toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /**
     * Read one profile.
     *
     * @throws ConfigError when there is no such profile or its file is not readable JSON
     */
    public Profile load(String name) {
        Path path = pathFor(name);
        if (!Files.exists(path)) {
            throw new ConfigError("profile", name, "no such profile");
        }
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        Object raw;
        try {
            raw = JsonText.read(text);
        } catch (IllegalArgumentException error) {
            throw new ConfigError("profile", name, "the file is not readable json");
        }
        if (!(raw instanceof Map<?, ?> fields)) {
            throw new ConfigError("profile", name, "the file is not readable json");
        }
        Map<String, Object> keyed = new LinkedHashMap<>();
        fields.forEach((key, value) -> keyed.put(String.valueOf(key), value));
        return Profile.fromMap(keyed);
    }

    /**
     * Write one profile out, after checking it, making the directory if it is missing.
     *
     * @return where it was written
     * @throws ConfigError when the profile does not validate or its name is unusable
     */
    public Path save(Profile profile) {
        profile.validate();
        Path path = pathFor(profile.name());
        try {
            Files.createDirectories(root);
            Files.writeString(path, JsonText.write(profile.asMap()), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return path;
    }

    /**
     * Remove one profile.
     *
     * @throws ConfigError when there is no such profile
     */
    public void delete(String name) {
        Path path = pathFor(name);
        if (!Files.exists(path)) {
            throw new ConfigError("profile", name, "no such profile");
        }
        try {
            Files.delete(path);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** Every profile, in name order, skipping any that no longer reads. */
    public List<Profile> all() {
        List<Profile> found = new ArrayList<>();
        for (String name : names()) {
            try {
                found.add(load(name));
            } catch (ConfigError unreadable) {
                continue;
            }
        }
        return List.copyOf(found);
    }

    /**
     * The profile that claims one machine, or empty when none does.
     *
     * <p>Two profiles claiming the same machine is a mistake rather than a precedence rule, so it is
     * refused rather than resolved.
     *
     * @throws ConfigError when more than one profile claims the machine
     */
    public Optional<Profile> forPrinter(PrinterRecord record) {
        List<Profile> claims =
                all().stream().filter(profile -> profile.matches(record)).toList();
        if (claims.isEmpty()) {
            return Optional.empty();
        }
        if (claims.size() > 1) {
            throw new ConfigError(
                    "profile",
                    record.printerId(),
                    "claimed by " + claims.stream().map(Profile::name).sorted().collect(Collectors.joining(", ")));
        }
        return Optional.of(claims.get(0));
    }
}
