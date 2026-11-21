package io.github.rkaushikdev.nozzlewright.siteconfig;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.fasterxml.jackson.dataformat.toml.TomlReadFeature;
import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.fleet.CostModel;
import io.github.rkaushikdev.nozzlewright.fleet.Costing;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.labels.ShiftWindow;
import io.github.rkaushikdev.nozzlewright.printer.CartridgeSpec;
import io.github.rkaushikdev.nozzlewright.printer.InkFamily;
import io.github.rkaushikdev.nozzlewright.settings.InvalidSettings;
import io.github.rkaushikdev.nozzlewright.settings.NotifySettings;
import io.github.rkaushikdev.nozzlewright.storage.Retention;
import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reading a site file: TOML in, a checked {@link SiteConfig} out. */
final class SiteFile {

    /** Sections the top level table is allowed to carry. */
    static final Set<String> TOP_LEVEL = Set.of("site", "shifts", "notify", "retention", "printers", "api", "costing");

    private static final EventLog LOG = EventLog.get("nozzlewright.siteconfig");

    private static final TomlMapper TOML =
            TomlMapper.builder().enable(TomlReadFeature.PARSE_JAVA_TIME).build();

    private static final Pattern CLOCK = Pattern.compile("(\\d{2})(?::?(\\d{2})(?::?(\\d{2})(?:[.,](\\d{1,6}))?)?)?");

    private SiteFile() {}

    static SiteConfig load(Path path) {
        if (!Files.exists(path)) {
            return SiteConfig.empty(path);
        }
        Map<String, Object> raw;
        try {
            raw = TOML.readValue(Files.readString(path, StandardCharsets.UTF_8), new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            throw new ConfigError("site file", path.toString(), "is not valid TOML: " + error.getOriginalMessage());
        } catch (IOException error) {
            throw new ConfigError("site file", path.toString(), "could not be read: " + error.getMessage());
        }
        if (raw == null) {
            raw = Map.of();
        }
        TreeSet<String> unknown = new TreeSet<>(raw.keySet());
        unknown.removeAll(TOP_LEVEL);
        if (!unknown.isEmpty()) {
            throw new ConfigError("site file", String.join(",", unknown), "is not a section this build knows");
        }
        Object site = raw.getOrDefault("site", Map.of());
        String siteId = site instanceof Map<?, ?> table
                ? text(table.containsKey("id") ? table.get("id") : "").strip().toLowerCase(Locale.ROOT)
                : "";
        SiteConfig config = new SiteConfig(
                siteId,
                shifts(raw.getOrDefault("shifts", List.of())),
                notify(raw.getOrDefault("notify", Map.of())),
                retention(raw.getOrDefault("retention", Map.of())),
                keys(raw.getOrDefault("api", Map.of())),
                printers(raw.getOrDefault("printers", List.of())),
                costing(raw.getOrDefault("costing", Map.of())),
                Optional.of(path));
        config.validate();
        LOG.info(
                "site file loaded",
                "path",
                path.toString(),
                "machines",
                config.printers().size(),
                "shifts",
                config.shifts().size());
        return config;
    }

    private static List<ShiftWindow> shifts(Object rows) {
        if (!(rows instanceof List<?> list)) {
            throw new ConfigError("shifts", typeName(rows), "must be a list of tables");
        }
        List<ShiftWindow> made = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) {
                throw new ConfigError("shifts", typeName(item), "must be a list of tables");
            }
            String code = text(row.containsKey("code") ? row.get("code") : "").strip();
            if (code.isEmpty()) {
                throw new ConfigError("shifts.code", "", "every shift needs a code");
            }
            made.add(new ShiftWindow(code, time(code, row.get("start")), time(code, row.get("end"))));
        }
        return made;
    }

    /**
     * One clock time out of the file. TOML has a real time type, so {@code 06:00} arrives already
     * read; a file that quotes it arrives as text, which people do, so both are taken.
     */
    private static LocalTime time(String code, Object value) {
        if (value instanceof LocalTime time) {
            return time;
        }
        if (value instanceof String text) {
            Matcher clock = CLOCK.matcher(text);
            if (clock.matches()) {
                try {
                    int micros =
                            clock.group(4) == null ? 0 : Integer.parseInt((clock.group(4) + "00000").substring(0, 6));
                    return LocalTime.of(
                            Integer.parseInt(clock.group(1)),
                            clock.group(2) == null ? 0 : Integer.parseInt(clock.group(2)),
                            clock.group(3) == null ? 0 : Integer.parseInt(clock.group(3)),
                            micros * 1000);
                } catch (java.time.DateTimeException error) {
                    throw new ConfigError("shifts." + code, text, "is not a time");
                }
            }
            throw new ConfigError("shifts." + code, text, "is not a time");
        }
        throw new ConfigError("shifts." + code, text(value), "needs a start and an end");
    }

    private static NotifySettings notify(Object table) {
        if (!(table instanceof Map<?, ?> map)) {
            throw new ConfigError("notify", typeName(table), "must be a table");
        }
        NotifySettings.Builder made = NotifySettings.builder();
        try {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object value = entry.getValue();
                switch (String.valueOf(entry.getKey())) {
                    case "webhook_url" -> made.webhookUrl(string("webhook_url", value));
                    case "webhook_token" -> made.webhookToken(string("webhook_token", value));
                    case "webhook_timeout_s" -> made.webhookTimeoutSeconds(real("webhook_timeout_s", value));
                    case "floor" -> made.floor(string("floor", value));
                    case "quiet_minutes" -> made.quietMinutes(real("quiet_minutes", value));
                    case "reminder_minutes" -> made.reminderMinutes(real("reminder_minutes", value));
                    default -> {
                        // Keys this build does not know are ignored, as the environment's are.
                    }
                }
            }
            return made.build();
        } catch (InvalidSettings error) {
            throw new ConfigError("notify", Repr.text(Repr.of(map)), error.getMessage());
        }
    }

    private static String string(String field, Object value) {
        if (value instanceof String text) {
            return text;
        }
        throw new InvalidSettings(field, "input should be a valid string");
    }

    private static double real(String field, Object value) {
        if (value instanceof Number number && !(value instanceof Boolean)) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Numbers.parseDouble(text);
            } catch (NumberFormatException error) {
                throw new InvalidSettings(field, "input should be a valid number");
            }
        }
        throw new InvalidSettings(field, "input should be a valid number");
    }

    /** The retention section, written in days. */
    private static Retention retention(Object table) {
        if (!(table instanceof Map<?, ?> map)) {
            throw new ConfigError("retention", typeName(table), "must be a table");
        }
        Retention made = Retention.defaults();
        Duration[] fields = {made.samples(), made.alarms(), made.runs(), made.audit()};
        boolean any = false;
        String[] names = {"samples", "alarms", "runs", "audit"};
        for (int index = 0; index < names.length; index++) {
            if (!map.containsKey(names[index])) {
                continue;
            }
            Object value = map.get(names[index]);
            long days;
            try {
                days = whole(value);
            } catch (IllegalArgumentException error) {
                throw new ConfigError("retention." + names[index], text(value), "must be days");
            }
            fields[index] = Duration.ofDays(days);
            any = true;
        }
        if (!any) {
            return made;
        }
        return new Retention(fields[0], fields[1], fields[2], fields[3]);
    }

    /** A whole number read the forgiving way: a number, a fraction cut to its whole part, or digits. */
    private static long whole(Object value) {
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        if (value instanceof Double || value instanceof Float) {
            double real = ((Number) value).doubleValue();
            if (!Double.isFinite(real)) {
                throw new IllegalArgumentException("not a whole number");
            }
            return (long) real;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            return Numbers.parseLong(text);
        }
        throw new IllegalArgumentException("not a whole number");
    }

    /** The API keys, which are the one secret this file is allowed to hold. */
    private static Set<String> keys(Object table) {
        if (!(table instanceof Map<?, ?> map)) {
            throw new ConfigError("api", typeName(table), "must be a table");
        }
        Object raw = map.containsKey("keys") ? map.get("keys") : List.of();
        Collection<?> items;
        if (raw instanceof String text) {
            items = Arrays.stream(text.split(",", -1)).map(String::strip).toList();
        } else if (raw instanceof List<?> list) {
            items = list;
        } else {
            throw new ConfigError("api.keys", text(raw), "must be a list or a comma separated string");
        }
        Set<String> keys = new TreeSet<>();
        for (Object key : items) {
            String clean = text(key).strip();
            if (!clean.isEmpty()) {
                keys.add(clean);
            }
        }
        return keys;
    }

    /** The machine table. */
    private static List<PrinterRecord> printers(Object rows) {
        if (!(rows instanceof List<?> list)) {
            throw new ConfigError("printers", typeName(rows), "must be a list of tables");
        }
        List<PrinterRecord> made = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) {
                throw new ConfigError("printers", typeName(item), "must be a list of tables");
            }
            if (!row.containsKey("id") || !row.containsKey("endpoint")) {
                throw new ConfigError("printers", Repr.of(row), "every machine needs an id and an endpoint");
            }
            List<String> tags = new ArrayList<>();
            Object rawTags = row.containsKey("tags") ? row.get("tags") : List.of();
            if (rawTags instanceof Collection<?> collection) {
                collection.forEach(tag -> tags.add(text(tag)));
            } else if (rawTags instanceof String text) {
                text.chars().forEach(ch -> tags.add(String.valueOf((char) ch)));
            }
            made.add(new PrinterRecord(
                    text(row.get("id")),
                    text(row.get("endpoint")),
                    kind(row.containsKey("kind") ? row.get("kind") : "tcp"),
                    text(row.containsKey("line") ? row.get("line") : ""),
                    text(row.containsKey("description") ? row.get("description") : ""),
                    !row.containsKey("enabled") || truthy(row.get("enabled")),
                    tags));
        }
        return made;
    }

    /**
     * The costing section, when a site has priced its ink. A site with no prices gets nothing rather
     * than a default: a made up cartridge price produces a cost per thousand that looks
     * authoritative and is fiction, and somebody would put it in a tender.
     */
    private static Optional<CostModel> costing(Object table) {
        if (!(table instanceof Map<?, ?> map)) {
            throw new ConfigError("costing", typeName(table), "must be a table");
        }
        if (map.isEmpty()) {
            return Optional.empty();
        }
        if (!map.containsKey("cartridge_price")) {
            throw new ConfigError("costing", Repr.of(map), "needs a cartridge_price");
        }
        String family = text(map.containsKey("ink") ? map.get("ink") : "MEK").toUpperCase(Locale.ROOT);
        InkFamily ink;
        try {
            ink = InkFamily.valueOf(family);
        } catch (IllegalArgumentException error) {
            TreeSet<String> names = new TreeSet<>();
            for (InkFamily known : InkFamily.values()) {
                names.add(known.value());
            }
            throw new ConfigError("costing.ink", family, "must be one of " + String.join(", ", names));
        }
        CartridgeSpec spec = new CartridgeSpec(
                        text(map.containsKey("part_number") ? map.get("part_number") : "unpriced"), ink)
                .withNominalMl(number(map, "cartridge_ml", 42.0));
        CostModel made = new CostModel(
                number(map, "cartridge_price", 0.0),
                spec,
                number(map, "drop_volume_pl", Costing.NOMINAL_DROP_PL),
                number(map, "purges_per_thousand", 0.0));
        made.validate();
        return Optional.of(made);
    }

    private static double number(Map<?, ?> map, String key, double fallback) {
        if (!map.containsKey(key)) {
            return fallback;
        }
        Object value = map.get(key);
        if (value instanceof Boolean flag) {
            return flag ? 1.0 : 0.0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Numbers.parseDouble(text(value));
    }

    /** One transport name out of the file. */
    private static String kind(Object value) {
        String text = text(value).strip().toLowerCase(Locale.ROOT);
        if (!Set.of("tcp", "rfcomm", "loopback").contains(text)) {
            throw new ConfigError("printers.kind", text(value), "must be tcp, rfcomm or loopback");
        }
        return text;
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0;
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    private static String text(Object value) {
        return Repr.text(value);
    }

    /** What a value is called in a message: table, array, string, integer, float, boolean, time. */
    static String typeName(Object value) {
        if (value instanceof Map<?, ?>) {
            return "dict";
        }
        if (value instanceof List<?>) {
            return "list";
        }
        if (value instanceof String) {
            return "str";
        }
        if (value instanceof Boolean) {
            return "bool";
        }
        if (value instanceof Double || value instanceof Float) {
            return "float";
        }
        if (value instanceof Number) {
            return "int";
        }
        if (value instanceof LocalTime) {
            return "time";
        }
        if (value instanceof LocalDate) {
            return "date";
        }
        if (value instanceof LocalDateTime || value instanceof OffsetDateTime) {
            return "datetime";
        }
        return value == null ? "NoneType" : value.getClass().getSimpleName();
    }
}
