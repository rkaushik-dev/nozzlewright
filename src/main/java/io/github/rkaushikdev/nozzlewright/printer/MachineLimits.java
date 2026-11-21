package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.UnknownCommand;
import io.github.rkaushikdev.nozzlewright.protocol.ArgKind;
import io.github.rkaushikdev.nozzlewright.protocol.ArgSpec;
import io.github.rkaushikdev.nozzlewright.protocol.Catalogue;
import io.github.rkaushikdev.nozzlewright.protocol.CommandSpec;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Everything one machine said, in its MINMAX reply, it would accept.
 *
 * <p>The catalogue carries the range a verb takes on any machine. MINMAX carries the range this
 * machine takes, which is narrower and sometimes surprising: a head with the low voltage option
 * refuses anything over eleven volts, and an HVXLCP board with the slow encoder input tops out at four
 * thousand pulses. Checking against the machine rather than the catalogue is the difference between
 * finding out at the desk and finding out on the line, and the only way a rollout can say honestly
 * that a configuration will apply.
 *
 * <p>Limits are read once per session and cached with it. They do not change while a machine is
 * powered.
 *
 * @param limits the range per verb, keyed by the upper case verb, in the order the machine sent them
 */
public record MachineLimits(Map<String, Limit> limits) {

    /** Copies the limits, keeping their order. */
    public MachineLimits {
        limits = Collections.unmodifiableMap(new LinkedHashMap<>(limits));
    }

    /** True when the machine reported a range for this verb, in any case. */
    public boolean has(String verb) {
        return limits.containsKey(verb.toUpperCase(Locale.ROOT));
    }

    /** The range for one verb, in any case; empty when the machine did not say. */
    public Optional<Limit> get(String verb) {
        return Optional.ofNullable(limits.get(verb.toUpperCase(Locale.ROOT)));
    }

    /**
     * Refuse unless this machine would accept the value.
     *
     * <p>A verb the machine said nothing about passes. Silence from MINMAX means the setting has no
     * narrower range than the catalogue, not that it is forbidden, and treating silence as a refusal
     * would block every setting on a revision one machine.
     *
     * @throws ConfigError when the value is outside the machine's range
     */
    public void check(String verb, int value) {
        Optional<Limit> found = get(verb);
        if (found.isEmpty()) {
            return;
        }
        Limit limit = found.get();
        if (!limit.holds(value)) {
            throw new ConfigError(verb, value, "this machine takes " + limit.low() + " to " + limit.high());
        }
    }

    /**
     * Verbs where this machine is stricter than the protocol allows, sorted.
     *
     * <p>Each limit is compared with the first whole number argument of its verb in the catalogue.
     * Worth showing during commissioning: it is usually a build option, and knowing which one saves a
     * phone call. A limit on a verb the catalogue does not carry is skipped.
     */
    public List<String> narrowerThanCatalogue() {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, Limit> entry : limits.entrySet()) {
            CommandSpec spec;
            try {
                spec = Catalogue.lookup(entry.getKey());
            } catch (UnknownCommand unknown) {
                continue;
            }
            Limit limit = entry.getValue();
            for (ArgSpec arg : spec.args()) {
                if (arg.kind() != ArgKind.INT
                        || arg.low().isEmpty()
                        || arg.high().isEmpty()) {
                    continue;
                }
                if (limit.low() > arg.low().getAsInt()
                        || limit.high() < arg.high().getAsInt()) {
                    found.add(entry.getKey());
                }
                break;
            }
        }
        Collections.sort(found);
        return List.copyOf(found);
    }

    /**
     * Every setting in a configuration this machine would refuse, as the refusal messages.
     *
     * <p>Returns the reasons rather than failing, because a rollout wants the whole list and not just
     * the first thing that would have gone wrong.
     */
    public List<String> checkConfig(PrinterConfig config) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Integer> setting : valuesIn(config)) {
            try {
                check(setting.getKey(), setting.getValue());
            } catch (ConfigError error) {
                problems.add(error.getMessage());
            }
        }
        return List.copyOf(problems);
    }

    /**
     * Turn a MINMAX table into limits, dropping anything unreadable.
     *
     * <p>A row without a name or without readable ends is skipped. Some builds send the ends the
     * wrong way round; swapping them is closer to what the machine meant than dropping the row.
     */
    public static MachineLimits parse(List<Map<String, String>> rows) {
        Map<String, Limit> limits = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String name = row.getOrDefault("NAME", "").strip().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            String rawLow = row.get("MIN");
            String rawHigh = row.get("MAX");
            if (rawLow == null || rawHigh == null) {
                continue;
            }
            int low;
            int high;
            try {
                low = Math.toIntExact(Numbers.parseLong(rawLow));
                high = Math.toIntExact(Numbers.parseLong(rawHigh));
            } catch (NumberFormatException | ArithmeticException error) {
                continue;
            }
            if (low > high) {
                int swap = low;
                low = high;
                high = swap;
            }
            limits.put(name, new Limit(name, low, high));
        }
        return new MachineLimits(limits);
    }

    /** The settings in a configuration that MINMAX has an opinion about, in a fixed order. */
    private static List<Map.Entry<String, Integer>> valuesIn(PrinterConfig config) {
        List<Map.Entry<String, Integer>> found = new ArrayList<>();
        found.add(Map.entry("HORZRES", config.head().horizontalDpi()));
        for (FiringProfile profile : config.firing().profiles()) {
            found.add(Map.entry("FIRINGVOL", profile.millivolts()));
            found.add(Map.entry("PULSELEN", profile.pulseNs()));
            found.add(Map.entry("PULSEWARM", profile.warmNs()));
        }
        config.encoder().ifPresent(encoder -> found.add(Map.entry("SHAFTENC", encoder.pulsesPerRev())));
        found.add(Map.entry("PHOTOSEL", config.photocell().channel()));
        found.add(Map.entry("PHOTOMASK", config.photocell().maskDots()));
        found.add(Map.entry("AUTOSPEED", config.autospeedMmPerMin()));
        return found;
    }
}
