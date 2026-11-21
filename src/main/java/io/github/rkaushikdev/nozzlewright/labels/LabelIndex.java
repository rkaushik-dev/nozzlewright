package io.github.rkaushikdev.nozzlewright.labels;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The text of the label store's {@code index.json}.
 *
 * <p>The file is written in one fixed form: two space indentation, keys sorted, every character
 * outside printable ASCII escaped as {@code \}{@code uXXXX} in lower case hex, and a closing line
 * feed. An index written by an earlier build of the handheld reads back the same.
 */
final class LabelIndex {

    /** The index format version. */
    static final int VERSION = 1;

    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private LabelIndex() {}

    /** The whole index file for these entries, in the order given. */
    static String write(List<LabelRecord> records) {
        StringBuilder out = new StringBuilder("{\n  \"labels\": [");
        for (int index = 0; index < records.size(); index++) {
            out.append(index == 0 ? "\n" : ",\n").append("    {\n");
            Map<String, Object> sorted = new TreeMap<>(records.get(index).asMap());
            int written = 0;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                out.append("      ");
                string(out, entry.getKey());
                out.append(": ");
                value(out, entry.getValue());
                out.append(++written < sorted.size() ? ",\n" : "\n");
            }
            out.append("    }");
        }
        out.append(records.isEmpty() ? "]" : "\n  ]");
        return out.append(",\n  \"version\": ").append(VERSION).append("\n}\n").toString();
    }

    /**
     * The entries an index file holds, by name, or nothing when the file is not a readable index.
     *
     * <p>Entries that are not objects, or have no name, are skipped. An entry with a name but a
     * missing or mistyped setting makes the whole file unreadable, so the store rebuilds it.
     */
    static Optional<Map<String, LabelRecord>> read(String text) {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (JsonProcessingException error) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        Map<String, LabelRecord> found = new LinkedHashMap<>();
        JsonNode labels = root.path("labels");
        if (!labels.isArray()) {
            return Optional.of(found);
        }
        for (JsonNode entry : labels) {
            if (!entry.isObject() || !entry.has("name")) {
                continue;
            }
            Optional<LabelRecord> record = record(entry);
            if (record.isEmpty()) {
                return Optional.empty();
            }
            found.put(record.get().name(), record.get());
        }
        return Optional.of(found);
    }

    private static Optional<LabelRecord> record(JsonNode entry) {
        JsonNode name = entry.get("name");
        JsonNode revision = entry.get("revision");
        JsonNode digest = entry.get("digest");
        JsonNode height = entry.get("height_dots");
        JsonNode updated = entry.get("updated_at");
        JsonNode interactive = entry.path("interactive");
        boolean complete = name.isTextual()
                && revision != null
                && revision.canConvertToInt()
                && revision.isIntegralNumber()
                && digest != null
                && digest.isTextual()
                && height != null
                && height.canConvertToInt()
                && height.isIntegralNumber()
                && updated != null
                && updated.isTextual()
                && (interactive.isMissingNode() || interactive.isBoolean());
        if (!complete) {
            return Optional.empty();
        }
        return Optional.of(new LabelRecord(
                name.asText(),
                revision.asInt(),
                digest.asText(),
                height.asInt(),
                updated.asText(),
                interactive.asBoolean(false)));
    }

    private static void value(StringBuilder out, Object value) {
        if (value instanceof String text) {
            string(out, text);
        } else {
            out.append(value);
        }
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (ch < 0x20 || ch > 0x7E) {
                        out.append("\\u")
                                .append(HEX[(ch >> 12) & 0xF])
                                .append(HEX[(ch >> 8) & 0xF])
                                .append(HEX[(ch >> 4) & 0xF])
                                .append(HEX[ch & 0xF]);
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        out.append('"');
    }
}
