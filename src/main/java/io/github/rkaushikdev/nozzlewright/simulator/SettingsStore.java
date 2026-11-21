package io.github.rkaushikdev.nozzlewright.simulator;

import io.github.rkaushikdev.nozzlewright.printer.Fieldbus;
import io.github.rkaushikdev.nozzlewright.printer.FiringProfile;
import io.github.rkaushikdev.nozzlewright.printer.FiringTable;
import io.github.rkaushikdev.nozzlewright.printer.GroupGeometry;
import io.github.rkaushikdev.nozzlewright.printer.Orientation;
import io.github.rkaushikdev.nozzlewright.printer.PhotocellMode;
import io.github.rkaushikdev.nozzlewright.printer.PrintMode;
import io.github.rkaushikdev.nozzlewright.printer.PrinterConfig;
import io.github.rkaushikdev.nozzlewright.printer.ShaftEncoder;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The part of the simulated machine that remembers what it was told.
 *
 * <p>Without this the simulated machine accepts every setting verb and forgets it, which makes a
 * rollout test look green while proving nothing. Reading a configuration back and getting the one
 * that was pushed is the whole point of the config verbs, so the model holds it.
 *
 * <p>Each handler takes the argument list as the wire delivers it, strings and all, and returns the
 * new configuration. Anything it cannot read is a {@link BadArgument}, which the machine answers as a
 * bad parameter rather than failing, because that is what the firmware does.
 */
final class SettingsStore {

    /** One setting verb applied to a configuration. */
    @FunctionalInterface
    interface Handler {

        /** The configuration once the verb has been applied with {@code args}. */
        PrinterConfig apply(PrinterConfig config, List<String> args) throws BadArgument;
    }

    private static final Set<String> OFF_WORDS = Set.of("0", "", "off", "OFF");

    private static final Map<String, Handler> SETTING_VERBS = handlers();

    private SettingsStore() {}

    /**
     * Apply one setting verb.
     *
     * @return the new configuration, or empty when the verb is not a setting
     * @throws BadArgument when the arguments make no sense for the verb
     */
    static Optional<PrinterConfig> apply(PrinterConfig config, String verb, List<String> args) throws BadArgument {
        Handler handler = SETTING_VERBS.get(verb);
        if (handler == null) {
            return Optional.empty();
        }
        return Optional.of(handler.apply(config, args));
    }

    private static Map<String, Handler> handlers() {
        Map<String, Handler> made = new LinkedHashMap<>();
        made.put("HORZRES", (config, args) -> config.toBuilder()
                .head(config.head().withHorizontalDpi(integer(args, 0)))
                .build());
        made.put("GROUPORIEN", (config, args) -> {
            Orientation orientation;
            try {
                orientation = Orientation.valueOf(text(args, 1));
            } catch (IllegalArgumentException error) {
                throw new BadArgument("unknown orientation");
            }
            return replaceGroup(config, integer(args, 0), group -> group.withOrientation(orientation));
        });
        made.put("GROUPDIR", (config, args) -> {
            boolean reverse = flag(args, 1);
            return replaceGroup(config, integer(args, 0), group -> group.withReverse(reverse));
        });
        made.put("GROUPOFF", (config, args) -> {
            int offset = integer(args, 1);
            return replaceGroup(config, integer(args, 0), group -> group.withOffsetDots(offset));
        });
        made.put("GROUPSPACE", (config, args) -> {
            int spacing = integer(args, 1);
            return replaceGroup(config, integer(args, 0), group -> group.withSpacingDots(spacing));
        });
        made.put("FIRINGVOL", (config, args) -> {
            int millivolts = integer(args, 1);
            return replaceProfile(config, integer(args, 0), profile -> profile.withVoltage(millivolts));
        });
        made.put("PULSELEN", (config, args) -> {
            int pulse = integer(args, 1);
            return replaceProfile(config, integer(args, 0), profile -> profile.withPulse(pulse));
        });
        made.put("PULSEWARM", (config, args) -> {
            int warm = integer(args, 1);
            return replaceProfile(config, integer(args, 0), profile -> profile.withWarmNs(warm));
        });
        made.put("SHAFTENC", (config, args) -> config.toBuilder()
                .encoder(new ShaftEncoder(integer(args, 0), integer(args, 1)))
                .build());
        made.put("PHOTOSEL", (config, args) -> config.toBuilder()
                .photocell(config.photocell().withChannel(integer(args, 0)))
                .build());
        made.put("PHOTOMODE", (config, args) -> {
            PhotocellMode mode;
            try {
                mode = PhotocellMode.parse(text(args, 0));
            } catch (IllegalArgumentException error) {
                throw new BadArgument("unknown photocell mode");
            }
            return config.toBuilder()
                    .photocell(config.photocell().withMode(mode))
                    .build();
        });
        made.put("PHOTOMASK", (config, args) -> config.toBuilder()
                .photocell(config.photocell().withMaskDots(integer(args, 0)))
                .build());
        made.put("PRINTMODE", (config, args) -> {
            PrintMode mode;
            try {
                mode = PrintMode.parse(text(args, 0));
            } catch (IllegalArgumentException error) {
                throw new BadArgument("unknown print mode");
            }
            return config.withMode(mode);
        });
        made.put("PROTOCOL", (config, args) -> {
            Fieldbus bus;
            try {
                bus = Fieldbus.parse(text(args, 0));
            } catch (IllegalArgumentException error) {
                throw new BadArgument("unknown fieldbus");
            }
            return config.toBuilder().fieldbus(bus).build();
        });
        made.put("AUTOSPEED", (config, args) -> config.toBuilder()
                .autospeedMmPerMin(integer(args, 0))
                .build());
        made.put(
                "HFIRE",
                (config, args) -> config.toBuilder().highFire(flag(args, 0)).build());
        return Collections.unmodifiableMap(made);
    }

    private static int integer(List<String> args, int index) throws BadArgument {
        if (index >= args.size()) {
            throw new BadArgument("argument " + index + " is not a number");
        }
        try {
            return Math.toIntExact(Numbers.parseLong(args.get(index)));
        } catch (NumberFormatException | ArithmeticException error) {
            throw new BadArgument("argument " + index + " is not a number");
        }
    }

    private static boolean flag(List<String> args, int index) throws BadArgument {
        if (index >= args.size()) {
            throw new BadArgument("argument " + index + " is missing");
        }
        return !OFF_WORDS.contains(args.get(index).strip());
    }

    private static String text(List<String> args, int index) throws BadArgument {
        if (index >= args.size()) {
            throw new BadArgument("argument " + index + " is missing");
        }
        return args.get(index).strip().toUpperCase(Locale.ROOT);
    }

    /** Swap one group for a changed copy of itself. */
    private static PrinterConfig replaceGroup(PrinterConfig config, int index, UnaryOperator<GroupGeometry> change)
            throws BadArgument {
        List<GroupGeometry> groups = new ArrayList<>();
        boolean found = false;
        for (GroupGeometry group : config.head().groups()) {
            if (group.index() == index) {
                groups.add(change.apply(group));
                found = true;
            } else {
                groups.add(group);
            }
        }
        if (!found) {
            throw new BadArgument("no group " + index);
        }
        return config.toBuilder().head(config.head().withGroups(groups)).build();
    }

    /** Swap one firing profile for a changed copy of itself. */
    private static PrinterConfig replaceProfile(PrinterConfig config, int pen, UnaryOperator<FiringProfile> change)
            throws BadArgument {
        List<FiringProfile> profiles = new ArrayList<>();
        boolean found = false;
        for (FiringProfile profile : config.firing().profiles()) {
            if (profile.pen() == pen) {
                profiles.add(change.apply(profile));
                found = true;
            } else {
                profiles.add(profile);
            }
        }
        if (!found) {
            throw new BadArgument("no pen " + pen);
        }
        return config.toBuilder().firing(new FiringTable(profiles)).build();
    }
}
