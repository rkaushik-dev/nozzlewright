package io.github.rkaushikdev.nozzlewright.doctor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing that was looked at.
 *
 * @param name what was looked at, {@code disk space}
 * @param grade how bad it is
 * @param detail what was found, in a few words
 */
public record Check(String name, Grade grade, String detail) {

    /** A finding with nothing more to say. */
    public Check(String name, Grade grade) {
        this(name, grade, "");
    }

    /** True when nothing needs doing. */
    public boolean ok() {
        return grade == Grade.OK;
    }

    /** One line for the console. */
    public String render() {
        return String.format("%-5s %-18s %s", grade.value(), name, detail);
    }

    /** A plain mapping for the console and the API: name, grade, detail and ok. */
    public Map<String, Object> asMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("grade", grade.value());
        out.put("detail", detail);
        out.put("ok", ok());
        return out;
    }
}
