package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ProtocolError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The alarm bits a machine has raised, as one value.
 *
 * <p>The firmware never clears a bit on its own except the two that track the print run, so a
 * handheld that reads the word once and caches it will happily show a healthy machine that has been
 * stopped for an hour. Read it fresh. Bits nobody has named are dropped, so a newer firmware raising
 * one cannot make a machine look stranger than it is.
 *
 * @param word the raised bits, one per {@link Alarm}
 */
public record Alarms(int word) {

    private static final int ALL_BITS = allBits();

    /** Nothing raised. */
    public static final Alarms NONE = new Alarms(0);

    private static final Pattern HEX = Pattern.compile("[+-]?(?:0[xX]_?)?[0-9a-fA-F](?:_?[0-9a-fA-F])*");

    /** Keeps only the bits that name an alarm. */
    public Alarms {
        word &= ALL_BITS;
    }

    /** These alarms raised together. */
    public static Alarms of(Alarm... alarms) {
        int word = 0;
        for (Alarm alarm : alarms) {
            word |= alarm.bit();
        }
        return new Alarms(word);
    }

    /**
     * Turn the hexadecimal ALARMS field into a set of alarms.
     *
     * <p>Blank means nothing raised. A {@code 0x} prefix and either case are accepted, and bits that
     * name no alarm are dropped.
     *
     * @throws ProtocolError when the field is not hexadecimal or is negative
     */
    public static Alarms decode(String raw) {
        String text = raw.strip();
        if (text.isEmpty()) {
            return NONE;
        }
        if (text.toLowerCase(Locale.ROOT).startsWith("0x")) {
            text = text.substring(2);
        }
        if (!HEX.matcher(text).matches()) {
            throw new ProtocolError("alarm word " + Repr.of(raw) + " is not hexadecimal");
        }
        boolean negative = text.startsWith("-");
        String digits =
                text.replaceFirst("^[+-]", "").replaceFirst("^0[xX]", "").replace("_", "");
        BigInteger value = new BigInteger(digits, 16);
        if (negative && value.signum() != 0) {
            throw new ProtocolError("alarm word " + Repr.of(raw) + " is negative");
        }
        return new Alarms(value.and(BigInteger.valueOf(ALL_BITS)).intValue());
    }

    /** True when nothing is raised. */
    public boolean isEmpty() {
        return word == 0;
    }

    /** True when {@code alarm} is raised. */
    public boolean contains(Alarm alarm) {
        return (word & alarm.bit()) != 0;
    }

    /** These alarms with {@code alarm} raised as well. */
    public Alarms with(Alarm alarm) {
        return new Alarms(word | alarm.bit());
    }

    /** Every raised alarm, one entry each, in bit order. */
    public List<Alarm> active() {
        List<Alarm> raised = new ArrayList<>();
        for (Alarm alarm : Alarm.values()) {
            if (contains(alarm)) {
                raised.add(alarm);
            }
        }
        return List.copyOf(raised);
    }

    /** Readable names for the raised alarms, in bit order. */
    public List<String> names() {
        return active().stream().map(Alarm::name).toList();
    }

    /** The worst severity among the raised alarms; {@link Severity#INFO} when none is raised. */
    public Severity severity() {
        Severity worst = Severity.INFO;
        for (Alarm alarm : active()) {
            if (alarm.severity().rank() > worst.rank()) {
                worst = alarm.severity();
            }
        }
        return worst;
    }

    @Override
    public String toString() {
        return isEmpty() ? "NONE" : String.join("|", names());
    }

    private static int allBits() {
        int bits = 0;
        for (Alarm alarm : Alarm.values()) {
            bits |= alarm.bit();
        }
        return bits;
    }
}
