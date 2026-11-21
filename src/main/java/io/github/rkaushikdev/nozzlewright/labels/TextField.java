package io.github.rkaushikdev.nozzlewright.labels;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed text. */
public final class TextField extends Field {

    private final String value;

    /** A field that always renders {@code value}. */
    public TextField(String name, String value) {
        super(name);
        this.value = value;
    }

    /** The text. */
    public String value() {
        return value;
    }

    @Override
    public String render(FieldContext context) {
        return value;
    }

    @Override
    public String toSource() {
        return "FIELD " + name() + " TEXT " + value;
    }

    @Override
    public Map<String, Object> attributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("value", value);
        return Collections.unmodifiableMap(attributes);
    }
}
