package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeSet;

/**
 * One reading of the counters a machine keeps for itself.
 *
 * <p>STATISTICS is the machine's own life story: how long it has been powered, how long it has been
 * printing, how many products it has coded, how many purges it has done and how many faults it has
 * seen. None of it resets, which is what makes it useful and also what makes it awkward: a raw total
 * answers nothing. Nobody wants to know that a machine has printed eleven million products; they
 * want to know what it did this week, which is the difference between two readings. So a reading is
 * kept as it came, and everything anybody asks is a comparison, see {@link #since}.
 *
 * @param counters every counter the machine sent, keyed by its upper case name
 */
public record Statistics(Map<String, Long> counters) {

    /**
     * The counters whose meaning is known, with what they count. Anything else is kept and shown,
     * because a newer build adds counters, and a machine knowing more about itself than the
     * controller does is not a reason to throw the number away.
     */
    public static final Map<String, String> KNOWN_COUNTERS = known();

    /** Copies the counters, keeping their order. */
    public Statistics {
        counters = Collections.unmodifiableMap(new LinkedHashMap<>(counters));
    }

    /** One counter, in any case, or nought. */
    public long get(String name) {
        return get(name, 0);
    }

    /** One counter, in any case, or a default. */
    public long get(String name, long fallback) {
        return counters.getOrDefault(name.toUpperCase(Locale.ROOT), fallback);
    }

    /** Products coded over the machine's life. */
    public long prints() {
        return get("PRINTS");
    }

    /** Hours the machine has been switched on. */
    public long poweredHours() {
        return get("HOURS");
    }

    /** Hours the machine has been coding. */
    public long printingHours() {
        return get("PRINTHOURS");
    }

    /**
     * Fraction of powered time that was spent printing, at most one.
     *
     * <p>A machine at three percent duty is a machine somebody leaves switched on over the weekend,
     * which is a cartridge decap problem waiting to happen.
     */
    public double duty() {
        if (poweredHours() <= 0) {
            return 0.0;
        }
        return Math.min((double) printingHours() / poweredHours(), 1.0);
    }

    /** Average throughput while actually printing. */
    public double printsPerHour() {
        if (printingHours() <= 0) {
            return 0.0;
        }
        return (double) prints() / printingHours();
    }

    /**
     * How often somebody purges, per thousand products.
     *
     * <p>The number that starts an argument. A purge costs about a thousand labels worth of ink, so
     * anything over one here means the machine is spending more on maintenance than on product.
     */
    public double purgesPerThousandPrints() {
        if (prints() <= 0) {
            return 0.0;
        }
        return (double) (get("PURGES") * 1000) / prints();
    }

    /** Counters this build reported that have no known meaning, sorted. */
    public List<String> unknown() {
        TreeSet<String> names = new TreeSet<>(counters.keySet());
        names.removeAll(KNOWN_COUNTERS.keySet());
        return List.copyOf(names);
    }

    /** What changed between an earlier reading and this one. */
    public StatisticsDelta since(Statistics earlier) {
        return StatisticsDelta.between(earlier, this);
    }

    /** Turn a STATISTICS table into counters, dropping any row without a name or a readable value. */
    public static Statistics parse(List<Map<String, String>> rows) {
        Map<String, Long> counters = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String name = row.getOrDefault("NAME", "").strip().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            String value = row.get("VALUE");
            if (value != null) {
                readCounter(value).ifPresent(number -> counters.put(name, number));
            }
        }
        return new Statistics(counters);
    }

    /** A counter value, empty when the machine garbled it, so it is left out rather than read as nought. */
    private static OptionalLong readCounter(String text) {
        try {
            return OptionalLong.of(Numbers.parseLong(text));
        } catch (NumberFormatException error) {
            return OptionalLong.empty();
        }
    }

    private static Map<String, String> known() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("PRINTS", "products coded");
        names.put("HOURS", "hours powered");
        names.put("PRINTHOURS", "hours printing");
        names.put("PURGES", "purge cycles");
        names.put("FAULTS", "faults logged");
        names.put("CARTRIDGES", "cartridges fitted");
        names.put("STARTS", "print runs started");
        return Collections.unmodifiableMap(names);
    }
}
