package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

/**
 * A number that advances once per print.
 *
 * <p>It renders right aligned in a fixed width, padded with its pad character. A value too long for
 * the width loses digits from the left, so a six wide serial goes from {@code 999999} to {@code
 * 000000} rather than growing a column, and a negative value keeps its sign.
 */
public final class CounterField extends Field {

    /** The widest a counter may print. */
    public static final int MAX_WIDTH = 12;

    /**
     * How the stored format writes a space pad.
     *
     * <p>The format is white space split, so a space padded counter has to say so by name. This is
     * the only escape the format carries and it exists only for this.
     */
    public static final String PAD_SPACE = "SP";

    private final long start;
    private final long step;
    private final int width;
    private final String pad;
    private final OptionalLong wrapAt;
    private final Reset reset;

    /**
     * When a counter goes back to its start on its own.
     *
     * <p>A serial runs for the life of the label. A lot sequence restarts every production day, so the
     * first case of the day is 001 whatever yesterday finished on, and the production day is the
     * shift's, so the night shift's sequence carries on past midnight.
     */
    public enum Reset {
        /** Only when somebody resets it. */
        NEVER,
        /** At the first print of each production day. */
        DAY;

        /** The word the stored format writes after {@code reset=}, lower case. */
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * The reset a stored word names.
         *
         * @throws LabelError when the word is not never or day
         */
        public static Reset parse(String text) {
            for (Reset item : values()) {
                if (item.word().equals(text.toLowerCase(Locale.ROOT))) {
                    return item;
                }
            }
            throw new LabelError("reset=" + Repr.of(text) + " is not never or day");
        }
    }

    /** A counter from one, by one, six wide, zero padded, never wrapping. */
    public CounterField(String name) {
        this(name, 1, 1, 6, "0", OptionalLong.empty());
    }

    /**
     * A counter with every setting given.
     *
     * @param start the value before anything has printed
     * @param step how far it moves per print, negative to count down
     * @param width how many characters it prints
     * @param pad the character it is padded with on the left
     * @param wrapAt the value it never reaches, wrapping back towards {@code start} instead
     */
    public CounterField(String name, long start, long step, int width, String pad, OptionalLong wrapAt) {
        this(name, start, step, width, pad, wrapAt, Reset.NEVER);
    }

    /** A counter with every setting given, including when it restarts on its own. */
    public CounterField(String name, long start, long step, int width, String pad, OptionalLong wrapAt, Reset reset) {
        super(name);
        this.start = start;
        this.step = step;
        this.width = width;
        this.pad = pad;
        this.wrapAt = wrapAt;
        this.reset = reset;
    }

    /** The value before anything has printed. */
    public long start() {
        return start;
    }

    /** How far the counter moves per print. */
    public long step() {
        return step;
    }

    /** How many characters it prints. */
    public int width() {
        return width;
    }

    /** The pad character. */
    public String pad() {
        return pad;
    }

    /** The value it wraps at, when it wraps. */
    public OptionalLong wrapAt() {
        return wrapAt;
    }

    /** When it restarts on its own. */
    public Reset reset() {
        return reset;
    }

    /** The same counter starting at {@code value}. */
    public CounterField withStart(long value) {
        return new CounterField(name(), value, step, width, pad, wrapAt, reset);
    }

    /** The same counter moving {@code value} per print. */
    public CounterField withStep(long value) {
        return new CounterField(name(), start, value, width, pad, wrapAt, reset);
    }

    /** The same counter printing {@code value} characters. */
    public CounterField withWidth(int value) {
        return new CounterField(name(), start, step, value, pad, wrapAt, reset);
    }

    /** The same counter padded with {@code value}. */
    public CounterField withPad(String value) {
        return new CounterField(name(), start, step, width, value, wrapAt, reset);
    }

    /** The same counter wrapping at {@code value}. */
    public CounterField withWrapAt(long value) {
        return new CounterField(name(), start, step, width, pad, OptionalLong.of(value), reset);
    }

    /** The same counter restarting by {@code value}. */
    public CounterField withReset(Reset value) {
        return new CounterField(name(), start, step, width, pad, wrapAt, value);
    }

    @Override
    public void validate() {
        if (step == 0) {
            throw new LabelError("counter " + name() + " never moves");
        }
        if (width < 1 || width > MAX_WIDTH) {
            throw new LabelError("counter " + name() + " has a width of " + width + ", the limit is " + MAX_WIDTH);
        }
        if (Text.length(pad) != 1) {
            throw new LabelError("counter " + name() + " needs a single pad character");
        }
        if (wrapAt.isPresent() && wrapAt.getAsLong() <= start) {
            throw new LabelError(
                    "counter " + name() + " wraps at " + wrapAt.getAsLong() + " which is at or below its start");
        }
    }

    @Override
    public String render(FieldContext context) {
        return format(current(context));
    }

    /**
     * The value that prints now: the stored one, or the start when nothing is stored yet or when a
     * daily counter was last moved on another production day.
     */
    public long current(FieldContext context) {
        boolean stale = reset == Reset.DAY
                && context.counterDay(name())
                        .filter(day -> !day.equals(context.productionDay()))
                        .isPresent();
        return stale ? start : context.counter(name(), start);
    }

    /** Record one print: store the value after the one that printed, dated by the production day. */
    public void printed(FieldContext context) {
        context.bump(name(), advance(current(context)), context.productionDay());
    }

    /**
     * One value with the padding this counter uses.
     *
     * @throws IllegalArgumentException when the pad is not exactly one character
     */
    public String format(long value) {
        String digits = Long.toString(value);
        String sign = "";
        if (value < 0) {
            sign = "-";
            digits = digits.substring(1);
        }
        int room = width - sign.length();
        if (digits.length() > room) {
            digits = digits.substring(keepFrom(digits.length(), room));
        }
        if (Text.length(pad) != 1) {
            throw new IllegalArgumentException("the fill character must be exactly one character long");
        }
        int missing = room - digits.length();
        return sign + (missing > 0 ? pad.repeat(missing) : "") + digits;
    }

    /**
     * Where the last {@code room} of {@code length} digits start.
     *
     * <p>A room of nought or less is read as an offset from the left, so a counter with no room at
     * all keeps every digit rather than printing nothing.
     */
    private static int keepFrom(int length, int room) {
        int offset = -room;
        if (offset < 0) {
            return Math.max(offset + length, 0);
        }
        return Math.min(offset, length);
    }

    /** The next value after {@code value}, wrapping if this counter wraps. */
    public long advance(long value) {
        long next = value + step;
        if (wrapAt.isPresent() && next >= wrapAt.getAsLong()) {
            long span = wrapAt.getAsLong() - start;
            next = start + Math.floorMod(next - start, span);
        }
        return next;
    }

    @Override
    public String toSource() {
        List<String> parts = new ArrayList<>();
        parts.add("FIELD " + name() + " COUNTER");
        if (start != 1) {
            parts.add("start=" + start);
        }
        if (step != 1) {
            parts.add("step=" + step);
        }
        if (width != 6) {
            parts.add("width=" + width);
        }
        if (!pad.equals("0")) {
            parts.add("pad=" + (pad.equals(" ") ? PAD_SPACE : pad));
        }
        if (wrapAt.isPresent()) {
            parts.add("wrap=" + wrapAt.getAsLong());
        }
        if (reset != Reset.NEVER) {
            parts.add("reset=" + reset.word());
        }
        return String.join(" ", parts);
    }

    @Override
    public Map<String, Object> attributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("start", start);
        attributes.put("step", step);
        attributes.put("width", width);
        attributes.put("pad", pad);
        attributes.put("wrap_at", wrapAt);
        attributes.put("reset", reset.word());
        return Collections.unmodifiableMap(attributes);
    }
}
