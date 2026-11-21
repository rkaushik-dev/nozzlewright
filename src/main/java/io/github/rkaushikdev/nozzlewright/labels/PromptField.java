package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A value the operator types at the start of a run, such as a lot number.
 *
 * <p>A required prompt has no default and refuses to render until it is answered. An optional one
 * renders its default when nobody answered. Either way an answer longer than the limit is refused.
 */
public final class PromptField extends Field {

    private final String defaultValue;
    private final int maxLen;
    private final boolean required;

    /** A required prompt taking up to 32 characters. */
    public PromptField(String name) {
        this(name, "", 32, true);
    }

    /**
     * A prompt with every setting given.
     *
     * @param defaultValue what an optional prompt renders when nobody answered
     * @param maxLen the longest answer it takes
     * @param required whether it has to be answered
     */
    public PromptField(String name, String defaultValue, int maxLen, boolean required) {
        super(name);
        this.defaultValue = defaultValue;
        this.maxLen = maxLen;
        this.required = required;
    }

    /** What an optional prompt renders when nobody answered. */
    public String defaultValue() {
        return defaultValue;
    }

    /** The longest answer it takes. */
    public int maxLen() {
        return maxLen;
    }

    /** Whether it has to be answered. */
    public boolean required() {
        return required;
    }

    /** The same prompt with a default of {@code value}. */
    public PromptField withDefault(String value) {
        return new PromptField(name(), value, maxLen, required);
    }

    /** The same prompt taking up to {@code value} characters. */
    public PromptField withMaxLen(int value) {
        return new PromptField(name(), defaultValue, value, required);
    }

    /** The same prompt, required or not. */
    public PromptField withRequired(boolean value) {
        return new PromptField(name(), defaultValue, maxLen, value);
    }

    @Override
    public boolean interactive() {
        return true;
    }

    @Override
    public void validate() {
        if (maxLen < 1) {
            throw new LabelError("prompt " + name() + " has no room for a value");
        }
        if (Text.length(defaultValue) > maxLen) {
            throw new LabelError("prompt " + name() + " has a default longer than its limit");
        }
        if (required && !defaultValue.isEmpty()) {
            throw new LabelError("prompt " + name() + " is required and also has a default, pick one");
        }
    }

    @Override
    public String render(FieldContext context) {
        Optional<String> given = context.prompt(name());
        if (given.isEmpty()) {
            if (required) {
                throw new LabelError("prompt " + name() + " has not been answered");
            }
            return defaultValue;
        }
        int length = Text.length(given.get());
        if (length > maxLen) {
            throw new LabelError("prompt " + name() + " was given " + length + " characters and takes " + maxLen);
        }
        return given.get();
    }

    @Override
    public String toSource() {
        List<String> parts = new ArrayList<>();
        parts.add("FIELD " + name() + " PROMPT");
        if (maxLen != 32) {
            parts.add("max=" + maxLen);
        }
        if (!required) {
            parts.add("required=0");
        }
        if (!defaultValue.isEmpty()) {
            parts.add("default=" + defaultValue);
        }
        return String.join(" ", parts);
    }

    @Override
    public Map<String, Object> attributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("default", defaultValue);
        attributes.put("max_len", maxLen);
        attributes.put("required", required);
        return Collections.unmodifiableMap(attributes);
    }
}
