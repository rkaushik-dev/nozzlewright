package io.github.rkaushikdev.nozzlewright.labels;

import java.time.LocalTime;
import java.util.Map;

/**
 * The code for whichever shift is running.
 *
 * <p>The context's shift table is searched in order and the first window covering the clock wins.
 * When none does, the fallback prints, {@code ?} unless the label says otherwise.
 */
public final class ShiftField extends Field {

    private final String fallback;

    /** A shift field falling back to {@code ?}. */
    public ShiftField(String name) {
        this(name, "?");
    }

    /** A shift field falling back to {@code fallback}. */
    public ShiftField(String name, String fallback) {
        super(name);
        this.fallback = fallback;
    }

    /** What prints when no shift is running. */
    public String fallback() {
        return fallback;
    }

    @Override
    public String render(FieldContext context) {
        LocalTime moment = context.now().toLocalTime();
        for (ShiftWindow window : context.shifts()) {
            if (window.covers(moment)) {
                return window.code();
            }
        }
        return fallback;
    }

    @Override
    public String toSource() {
        String line = "FIELD " + name() + " SHIFT";
        return fallback.equals("?") ? line : line + " fallback=" + fallback;
    }

    @Override
    public Map<String, Object> attributes() {
        return Map.of("fallback", fallback);
    }
}
