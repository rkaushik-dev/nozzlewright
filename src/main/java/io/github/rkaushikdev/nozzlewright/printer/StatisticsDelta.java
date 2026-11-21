package io.github.rkaushikdev.nozzlewright.printer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The difference between two readings of a machine's counters.
 *
 * @param counters each counter's change, keyed by its upper case name
 * @param reset true when a counter went backwards in between, so the board was swapped or cleared
 */
public record StatisticsDelta(Map<String, Long> counters, boolean reset) {

    private static final double SECONDS_PER_HOUR = 3600.0;

    /** Copies the counters, keeping their order. */
    public StatisticsDelta {
        counters = Collections.unmodifiableMap(new LinkedHashMap<>(counters));
    }

    /** A plain difference with no reset in between. */
    public StatisticsDelta(Map<String, Long> counters) {
        this(counters, false);
    }

    /**
     * Work out what changed, allowing for a board swap.
     *
     * <p>A counter that went backwards means the board was replaced or its memory was cleared.
     * Everything after the reset is the new board's whole life, so the later reading is the
     * difference, and the flag says why the number looks the way it does. Counters are listed by
     * name, and one present in only one reading counts as nought in the other.
     */
    public static StatisticsDelta between(Statistics earlier, Statistics later) {
        boolean reset =
                earlier.counters().entrySet().stream().anyMatch(entry -> later.get(entry.getKey()) < entry.getValue());
        TreeMap<String, Long> changes = new TreeMap<>();
        for (String name : earlier.counters().keySet()) {
            changes.put(name, 0L);
        }
        for (String name : later.counters().keySet()) {
            changes.put(name, 0L);
        }
        changes.replaceAll((name, ignored) -> reset ? later.get(name) : later.get(name) - earlier.get(name));
        return new StatisticsDelta(changes, reset);
    }

    /** One counter's change, in any case, or nought. */
    public long get(String name) {
        return get(name, 0);
    }

    /** One counter's change, in any case, or a default. */
    public long get(String name, long fallback) {
        return counters.getOrDefault(name.toUpperCase(Locale.ROOT), fallback);
    }

    /** Products coded between the readings. */
    public long prints() {
        return get("PRINTS");
    }

    /** Products coded per hour of wall clock between the readings; nought over no time. */
    public double ratePerHour(double elapsedSeconds) {
        if (elapsedSeconds <= 0) {
            return 0.0;
        }
        return prints() / elapsedSeconds * SECONDS_PER_HOUR;
    }

    /** One line for a report. */
    public String summary() {
        String text = prints() + " coded, " + get("PURGES") + " purges, " + get("FAULTS") + " faults";
        if (reset) {
            text += " (counters were reset in between)";
        }
        return text;
    }
}
