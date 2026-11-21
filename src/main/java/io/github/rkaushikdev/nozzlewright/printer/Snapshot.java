package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ProtocolError;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reading a whole configuration back off a machine.
 *
 * <p>Every setting verb in the catalogue writes. Reading them back one at a time would be forty
 * round trips over a radio that manages maybe eight a second, so revision 2 firmware grew one bulk
 * verb, CONFIG, that dumps the lot as a table. Each row carries a {@code SEC} field saying what kind
 * of row it is:
 *
 * <pre>
 *     SEC=HEAD    the machine as a whole
 *     SEC=GROUP   one print group
 *     SEC=PEN     one cartridge position
 *     SEC=FIRING  one firing profile
 *     SEC=ENC     the shaft encoder, absent when none is fitted
 *     SEC=CELL    the product detect input
 *     SEC=MODE    print mode and everything that hangs off it
 * </pre>
 *
 * <p>Rows may arrive in any order and older builds leave out sections they do not have, so the
 * parser collects first and assembles afterwards. Anything it cannot make sense of is an error
 * rather than a default, because a silently defaulted firing voltage is a cooked print head.
 */
public final class Snapshot {

    private static final String PEN_LIST_SEPARATOR = "|";

    private Snapshot() {}

    /**
     * Assemble a configuration from the rows CONFIG returned, for a machine of {@code family}.
     *
     * <p>Groups and firing profiles are sorted by their number, so two snapshots of the same machine
     * compare equal whatever order the firmware walked its own tables in.
     *
     * @throws ProtocolError when a row is missing, short, unknown or carries a value that cannot be read
     */
    public static PrinterConfig parseConfigTable(List<Map<String, String>> rows, String family) {
        Map<String, String> headRow = null;
        Map<String, String> modeRow = null;
        Map<String, String> encoderRow = null;
        Map<String, String> cellRow = null;
        List<Map<String, String>> groupRows = new ArrayList<>();
        Map<Integer, Map<String, String>> penRows = new HashMap<>();
        List<Map<String, String>> firingRows = new ArrayList<>();

        for (Map<String, String> row : rows) {
            String section = row.getOrDefault("SEC", "").strip().toUpperCase(Locale.ROOT);
            switch (section) {
                case "HEAD" -> headRow = row;
                case "MODE" -> modeRow = row;
                case "ENC" -> encoderRow = row;
                case "CELL" -> cellRow = row;
                case "GROUP" -> groupRows.add(row);
                case "PEN" -> penRows.put(needInt(row, "IDX"), row);
                case "FIRING" -> firingRows.add(row);
                case "" -> throw new ProtocolError("CONFIG row has no SEC field");
                default -> throw new ProtocolError("CONFIG row section " + Repr.of(section) + " is not one we know");
            }
        }

        if (headRow == null) {
            throw new ProtocolError("CONFIG reply carries no HEAD row");
        }
        if (groupRows.isEmpty()) {
            throw new ProtocolError("CONFIG reply carries no GROUP rows");
        }
        if (firingRows.isEmpty()) {
            throw new ProtocolError("CONFIG reply carries no FIRING rows");
        }

        List<GroupGeometry> groups = new ArrayList<>();
        for (Map<String, String> row : groupRows) {
            groups.add(buildGroup(row, penRows));
        }
        groups.sort(Comparator.comparingInt(GroupGeometry::index));
        HeadGeometry head = new HeadGeometry(family, needInt(headRow, "HDPI"), needInt(headRow, "VDPI"), groups);
        List<FiringProfile> profiles = new ArrayList<>();
        for (Map<String, String> row : firingRows) {
            profiles.add(buildProfile(row));
        }
        profiles.sort(Comparator.comparingInt(FiringProfile::pen));
        FiringTable firing = new FiringTable(profiles);
        Optional<ShaftEncoder> encoder = buildEncoder(encoderRow);
        Photocell photocell = buildPhotocell(cellRow);
        return buildConfig(head, firing, encoder, photocell, modeRow);
    }

    /**
     * Turn a configuration back into the payload CONFIG would have returned.
     *
     * <p>Used by the simulator and by the round trip test. Keeping the writer next to the reader is
     * the only way the two stay in step.
     */
    public static String renderConfigTable(PrinterConfig config) {
        List<String> rows = new ArrayList<>();
        HeadGeometry head = config.head();
        rows.add("SEC=HEAD,HDPI=" + head.horizontalDpi() + ",VDPI=" + head.verticalDpi() + ",GROUPS="
                + head.groups().size());
        for (GroupGeometry group : head.groups()) {
            String pens = group.pens().stream()
                    .map(pen -> String.valueOf(pen.index()))
                    .collect(Collectors.joining(PEN_LIST_SEPARATOR));
            rows.add("SEC=GROUP,IDX=" + group.index() + ",ORIEN="
                    + group.orientation().value() + ",REV="
                    + flag(group.reverse()) + ",OFF=" + group.offsetDots() + ",SPACE=" + group.spacingDots()
                    + ",PENS=" + pens);
            for (PenGeometry pen : group.pens()) {
                rows.add("SEC=PEN,IDX=" + pen.index() + ",ROWS=" + pen.rows() + ",VOFF=" + pen.verticalOffsetDots()
                        + ",HOFF=" + pen.horizontalOffsetDots());
            }
        }
        for (FiringProfile profile : config.firing().profiles()) {
            rows.add("SEC=FIRING,PEN=" + profile.pen() + ",MV=" + profile.millivolts() + ",PULSE=" + profile.pulseNs()
                    + ",WARM=" + profile.warmNs() + ",ROHM=" + profile.resistanceMohm());
        }
        config.encoder()
                .ifPresent(encoder -> rows.add("SEC=ENC,PPR=" + encoder.pulsesPerRev() + ",WHEEL=" + encoder.wheelMm()
                        + ",REV=" + flag(encoder.reverse())));
        Photocell cell = config.photocell();
        rows.add("SEC=CELL,CH=" + cell.channel() + ",MODE=" + cell.mode().value() + ",MASK=" + cell.maskDots()
                + ",DELAY=" + cell.delayDots());
        rows.add("SEC=MODE,MODE=" + config.printMode().value() + ",BUS="
                + config.fieldbus().value() + ",SPEED="
                + config.autospeedMmPerMin() + ",HFIRE=" + flag(config.highFire()) + ",REPEAT=" + config.repeatCount()
                + ",GAP=" + config.repeatGapMm());
        return String.join(";", rows);
    }

    /** Pull a field out of a row or say which row was short. */
    private static String need(Map<String, String> row, String key) {
        String value = row.get(key);
        if (value == null || value.isBlank()) {
            throw new ProtocolError(
                    "CONFIG row " + Repr.of(row.getOrDefault("SEC", "?")) + " has no " + key + " field");
        }
        return value.strip();
    }

    private static int needInt(Map<String, String> row, String key) {
        String raw = need(row, key);
        try {
            return Math.toIntExact(Numbers.parseLong(raw));
        } catch (NumberFormatException | ArithmeticException error) {
            throw new ProtocolError("CONFIG field " + key + "=" + Repr.of(raw) + " is not a number");
        }
    }

    private static boolean flag(Map<String, String> row, String key) {
        String raw = row.getOrDefault(key, "").strip().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return false;
        }
        return raw.equals("1") || raw.equals("on") || raw.equals("yes") || raw.equals("true");
    }

    private static int flag(boolean value) {
        return value ? 1 : 0;
    }

    private static GroupGeometry buildGroup(Map<String, String> row, Map<Integer, Map<String, String>> penRows) {
        int index = needInt(row, "IDX");
        String penField = need(row, "PENS");
        List<PenGeometry> pens = new ArrayList<>();
        for (String token : penField.split(Pattern.quote(PEN_LIST_SEPARATOR), -1)) {
            String text = token.strip();
            if (text.isEmpty()) {
                continue;
            }
            int penIndex;
            try {
                penIndex = Math.toIntExact(Numbers.parseLong(text));
            } catch (NumberFormatException | ArithmeticException error) {
                throw new ProtocolError("CONFIG pen list " + Repr.of(penField) + " is not numeric");
            }
            Map<String, String> penRow = penRows.get(penIndex);
            if (penRow == null) {
                throw new ProtocolError("group " + index + " names pen " + penIndex + " and no PEN row follows");
            }
            pens.add(new PenGeometry(
                    penIndex, needInt(penRow, "ROWS"), needInt(penRow, "VOFF"), needInt(penRow, "HOFF")));
        }
        if (pens.isEmpty()) {
            throw new ProtocolError("group " + index + " lists no pens");
        }

        String rawOrientation = need(row, "ORIEN").toUpperCase(Locale.ROOT);
        Orientation orientation;
        try {
            orientation = Orientation.valueOf(rawOrientation);
        } catch (IllegalArgumentException error) {
            throw new ProtocolError("orientation " + Repr.of(rawOrientation) + " is not one we know");
        }
        return new GroupGeometry(
                index, pens, orientation, flag(row, "REV"), needInt(row, "OFF"), needInt(row, "SPACE"));
    }

    private static FiringProfile buildProfile(Map<String, String> row) {
        return new FiringProfile(
                needInt(row, "PEN"),
                needInt(row, "MV"),
                needInt(row, "PULSE"),
                needInt(row, "WARM"),
                needInt(row, "ROHM"));
    }

    private static Optional<ShaftEncoder> buildEncoder(Map<String, String> row) {
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new ShaftEncoder(needInt(row, "PPR"), needInt(row, "WHEEL"), flag(row, "REV")));
    }

    private static Photocell buildPhotocell(Map<String, String> row) {
        if (row == null) {
            return new Photocell();
        }
        String rawMode = row.getOrDefault("MODE", "EDGE").strip().toUpperCase(Locale.ROOT);
        PhotocellMode mode;
        try {
            mode = PhotocellMode.parse(rawMode);
        } catch (IllegalArgumentException error) {
            throw new ProtocolError("photocell mode " + Repr.of(rawMode) + " is not one we know");
        }
        return new Photocell(needInt(row, "CH"), mode, needInt(row, "MASK"), needInt(row, "DELAY"));
    }

    private static PrinterConfig buildConfig(
            HeadGeometry head,
            FiringTable firing,
            Optional<ShaftEncoder> encoder,
            Photocell photocell,
            Map<String, String> modeRow) {
        PrinterConfig.Builder builder =
                PrinterConfig.builder(head, firing).photocell(photocell).encoder(encoder);
        if (modeRow == null) {
            return builder.build();
        }

        String rawMode = need(modeRow, "MODE").toUpperCase(Locale.ROOT);
        PrintMode printMode;
        try {
            printMode = PrintMode.parse(rawMode);
        } catch (IllegalArgumentException error) {
            throw new ProtocolError("print mode " + Repr.of(rawMode) + " is not one we know");
        }

        String rawBus = modeRow.getOrDefault("BUS", "NONE").strip().toUpperCase(Locale.ROOT);
        if (rawBus.isEmpty()) {
            rawBus = "NONE";
        }
        Fieldbus fieldbus;
        try {
            fieldbus = Fieldbus.parse(rawBus);
        } catch (IllegalArgumentException error) {
            throw new ProtocolError("fieldbus " + Repr.of(rawBus) + " is not one we know");
        }

        int speed = needInt(modeRow, "SPEED");
        return builder.speedSource(encoder.isPresent() ? SpeedSource.ENCODER : SpeedSource.FIXED)
                .autospeedMmPerMin(speed)
                .printMode(printMode)
                .fieldbus(fieldbus)
                .repeatCount(needInt(modeRow, "REPEAT"))
                .repeatGapMm(needInt(modeRow, "GAP"))
                .highFire(flag(modeRow, "HFIRE"))
                .build();
    }
}
