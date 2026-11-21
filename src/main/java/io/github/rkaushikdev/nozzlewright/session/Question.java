package io.github.rkaushikdev.nozzlewright.session;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.List;

/**
 * One field the machine is waiting on, as the operator is asked it.
 *
 * @param name the field name the machine uses
 * @param prompt what the operator sees
 * @param maxLen the longest answer it takes, in characters
 * @param defaultValue what is sent when the operator gives nothing
 * @param required whether a blank answer is refused
 */
public record Question(String name, String prompt, int maxLen, String defaultValue, boolean required) {

    private static final List<String> WIRE_SEPARATORS = List.of(",", ";", ":");

    /** A required question with no prompt text, taking up to 32 characters. */
    public Question(String name) {
        this(name, "", 32, "", true);
    }

    /**
     * Check one answer before it goes on the wire.
     *
     * @throws LabelError when a required answer is blank, it is too long, or it holds a character the
     *     wire uses as a separator
     */
    public void check(String value) {
        if (required && blank(value)) {
            throw new LabelError(name + " needs an answer");
        }
        int length = value.codePointCount(0, value.length());
        if (length > maxLen) {
            throw new LabelError(name + " takes " + maxLen + " characters and was given " + length);
        }
        for (String illegal : WIRE_SEPARATORS) {
            if (value.contains(illegal)) {
                throw new LabelError(name + " must not contain " + Repr.of(illegal));
            }
        }
    }

    private static boolean blank(String value) {
        return value.codePoints().allMatch(ch -> Character.isWhitespace(ch) || Character.isSpaceChar(ch));
    }
}
