package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything about one machine that a second machine could copy.
 *
 * <p>Commissioning a second line means copying the first one. Done by hand, the mistakes are always
 * the same two: a setting that exists on one family and not the other, and a setting read from the
 * wrong screen. A configuration is the machine as a value. It can be pulled off a printer, compared
 * against another, and turned back into the exact command list that would make a second machine
 * match. That list is ordered: geometry before firing, firing before the print mode, and nothing at
 * all while the line is running.
 *
 * @param head the head geometry
 * @param firing the firing profile of every pen
 * @param photocell the product detect input
 * @param encoder the shaft encoder, when one is fitted
 * @param speedSource where the line speed comes from
 * @param autospeedMmPerMin the fixed line speed, used without an encoder
 * @param printMode how the machine decides what to print and when
 * @param fieldbus the industrial protocol stack
 * @param repeatCount prints per trigger in repeat mode
 * @param repeatGapMm gap between repeated prints
 * @param highFire whether high frequency firing is on
 */
public record PrinterConfig(
        HeadGeometry head,
        FiringTable firing,
        Photocell photocell,
        Optional<ShaftEncoder> encoder,
        SpeedSource speedSource,
        int autospeedMmPerMin,
        PrintMode printMode,
        Fieldbus fieldbus,
        int repeatCount,
        int repeatGapMm,
        boolean highFire) {

    /**
     * A builder for a machine with this head and firing table.
     *
     * <p>Everything else starts at its default: the default photocell, no encoder, speed from the
     * encoder, no fixed speed, stand alone mode, no fieldbus, one print per trigger with no gap, and
     * high frequency firing off.
     */
    public static Builder builder(HeadGeometry head, FiringTable firing) {
        return new Builder(head, firing);
    }

    /** A builder starting from every value of this configuration. */
    public Builder toBuilder() {
        return new Builder(head, firing)
                .photocell(photocell)
                .encoder(encoder)
                .speedSource(speedSource)
                .autospeedMmPerMin(autospeedMmPerMin)
                .printMode(printMode)
                .fieldbus(fieldbus)
                .repeatCount(repeatCount)
                .repeatGapMm(repeatGapMm)
                .highFire(highFire);
    }

    /** A copy running a different print mode. */
    public PrinterConfig withMode(PrintMode mode) {
        return toBuilder().printMode(mode).build();
    }

    /**
     * Check every part, then the combinations that only make sense together.
     *
     * @throws ConfigError naming the first thing that is wrong
     */
    public void validate() {
        head.validate();
        firing.validate();
        photocell.validate();
        encoder.ifPresent(ShaftEncoder::validate);

        Set<Integer> pensOnHead = new TreeSet<>();
        for (GroupGeometry group : head.groups()) {
            for (PenGeometry pen : group.pens()) {
                pensOnHead.add(pen.index());
            }
        }
        Set<Integer> pensWithProfiles = new TreeSet<>();
        for (FiringProfile profile : firing.profiles()) {
            pensWithProfiles.add(profile.pen());
        }
        Set<Integer> missing = new TreeSet<>(pensOnHead);
        missing.removeAll(pensWithProfiles);
        if (!missing.isEmpty()) {
            throw new ConfigError("firing.profiles", List.copyOf(missing), "pens on the head have no firing profile");
        }
        Set<Integer> extra = new TreeSet<>(pensWithProfiles);
        extra.removeAll(pensOnHead);
        if (!extra.isEmpty()) {
            throw new ConfigError(
                    "firing.profiles", List.copyOf(extra), "firing profiles for pens the head does not have");
        }

        if (speedSource == SpeedSource.ENCODER && encoder.isEmpty()) {
            throw new ConfigError(
                    "speed_source", speedSource.value(), "encoder speed was selected and no encoder is fitted");
        }
        if (speedSource == SpeedSource.FIXED && autospeedMmPerMin <= 0) {
            throw new ConfigError("autospeed_mm_per_min", autospeedMmPerMin, "fixed speed needs a speed");
        }
        if (printMode.needsEncoder() && encoder.isEmpty()) {
            throw new ConfigError(
                    "print_mode", printMode.value(), "this mode reads the encoder and no encoder is fitted");
        }
        if (printMode.needsFieldbus() && fieldbus == Fieldbus.NONE) {
            throw new ConfigError("fieldbus", fieldbus.value(), "protocol mode needs a fieldbus stack");
        }
        if (printMode == PrintMode.REPEAT && repeatCount < 1) {
            throw new ConfigError("repeat_count", repeatCount, "repeat mode prints at least once");
        }
        if (repeatGapMm < 0) {
            throw new ConfigError("repeat_gap_mm", repeatGapMm, "a gap cannot be negative");
        }
    }

    /** What would have to change on this machine to look like {@code other}, in command order. */
    public ConfigDiff diff(PrinterConfig other) {
        List<FieldChange> changes = new ArrayList<>();
        geometryChanges(other, changes);
        firingChanges(other, changes);
        triggerChanges(other, changes);
        modeChanges(other, changes);
        return new ConfigDiff(changes);
    }

    private void geometryChanges(PrinterConfig other, List<FieldChange> changes) {
        if (head.horizontalDpi() != other.head.horizontalDpi()) {
            changes.add(new FieldChange(
                    "head.horizontal_dpi",
                    head.horizontalDpi(),
                    other.head.horizontalDpi(),
                    "HORZRES",
                    other.head.horizontalDpi()));
        }
        if (head.groups().size() != other.head.groups().size()) {
            changes.add(new FieldChange(
                    "head.groups",
                    head.groups().size(),
                    other.head.groups().size(),
                    "GROUPNUM",
                    other.head.groups().size()));
        }
        for (GroupGeometry theirs : other.head.groups()) {
            GroupGeometry ours;
            try {
                ours = head.group(theirs.index());
            } catch (ConfigError absent) {
                continue;
            }
            groupChanges(ours, theirs, changes);
        }
    }

    private void firingChanges(PrinterConfig other, List<FieldChange> changes) {
        for (FiringProfile theirs : other.firing.profiles()) {
            FiringProfile ours;
            try {
                ours = firing.profile(theirs.pen());
            } catch (ConfigError absent) {
                continue;
            }
            String prefix = "firing.pen" + theirs.pen();
            if (ours.millivolts() != theirs.millivolts()) {
                changes.add(new FieldChange(
                        prefix + ".millivolts",
                        ours.millivolts(),
                        theirs.millivolts(),
                        "FIRINGVOL",
                        theirs.pen(),
                        theirs.millivolts()));
            }
            if (ours.pulseNs() != theirs.pulseNs()) {
                changes.add(new FieldChange(
                        prefix + ".pulse_ns",
                        ours.pulseNs(),
                        theirs.pulseNs(),
                        "PULSELEN",
                        theirs.pen(),
                        theirs.pulseNs()));
            }
            if (ours.warmNs() != theirs.warmNs()) {
                changes.add(new FieldChange(
                        prefix + ".warm_ns",
                        ours.warmNs(),
                        theirs.warmNs(),
                        "PULSEWARM",
                        theirs.pen(),
                        theirs.warmNs()));
            }
        }
    }

    private void triggerChanges(PrinterConfig other, List<FieldChange> changes) {
        if (other.encoder.isPresent() && !encoder.equals(other.encoder)) {
            ShaftEncoder wanted = other.encoder.get();
            Object current = encoder.isPresent() ? encoder.get() : Optional.empty();
            changes.add(
                    new FieldChange("encoder", current, wanted, "SHAFTENC", wanted.pulsesPerRev(), wanted.wheelMm()));
        }
        if (photocell.channel() != other.photocell.channel()) {
            changes.add(new FieldChange(
                    "photocell.channel",
                    photocell.channel(),
                    other.photocell.channel(),
                    "PHOTOSEL",
                    other.photocell.channel()));
        }
        if (photocell.mode() != other.photocell.mode()) {
            changes.add(new FieldChange(
                    "photocell.mode",
                    photocell.mode().value(),
                    other.photocell.mode().value(),
                    "PHOTOMODE",
                    other.photocell.mode().value()));
        }
        if (photocell.maskDots() != other.photocell.maskDots()) {
            changes.add(new FieldChange(
                    "photocell.mask_dots",
                    photocell.maskDots(),
                    other.photocell.maskDots(),
                    "PHOTOMASK",
                    other.photocell.maskDots()));
        }
    }

    private void modeChanges(PrinterConfig other, List<FieldChange> changes) {
        if (printMode != other.printMode) {
            changes.add(new FieldChange(
                    "print_mode", printMode.value(), other.printMode.value(), "PRINTMODE", other.printMode.value()));
        }
        if (fieldbus != other.fieldbus) {
            changes.add(new FieldChange(
                    "fieldbus", fieldbus.value(), other.fieldbus.value(), "PROTOCOL", other.fieldbus.value()));
        }
        if (autospeedMmPerMin != other.autospeedMmPerMin) {
            changes.add(new FieldChange(
                    "autospeed_mm_per_min",
                    autospeedMmPerMin,
                    other.autospeedMmPerMin,
                    "AUTOSPEED",
                    other.autospeedMmPerMin));
        }
        if (highFire != other.highFire) {
            changes.add(new FieldChange("high_fire", highFire, other.highFire, "HFIRE", other.highFire));
        }
    }

    /** Differences between two groups with the same index. */
    private static void groupChanges(GroupGeometry ours, GroupGeometry theirs, List<FieldChange> changes) {
        int index = theirs.index();
        String prefix = "group" + index;
        if (ours.orientation() != theirs.orientation()) {
            changes.add(new FieldChange(
                    prefix + ".orientation",
                    ours.orientation().value(),
                    theirs.orientation().value(),
                    "GROUPORIEN",
                    index,
                    theirs.orientation().value()));
        }
        if (ours.reverse() != theirs.reverse()) {
            changes.add(new FieldChange(
                    prefix + ".reverse", ours.reverse(), theirs.reverse(), "GROUPDIR", index, theirs.reverse()));
        }
        if (ours.offsetDots() != theirs.offsetDots()) {
            changes.add(new FieldChange(
                    prefix + ".offset_dots",
                    ours.offsetDots(),
                    theirs.offsetDots(),
                    "GROUPOFF",
                    index,
                    theirs.offsetDots()));
        }
        if (ours.spacingDots() != theirs.spacingDots()) {
            changes.add(new FieldChange(
                    prefix + ".spacing_dots",
                    ours.spacingDots(),
                    theirs.spacingDots(),
                    "GROUPSPACE",
                    index,
                    theirs.spacingDots()));
        }
    }

    /** Assembles a {@link PrinterConfig}. */
    public static final class Builder {
        private HeadGeometry head;
        private FiringTable firing;
        private Photocell photocell = new Photocell();
        private Optional<ShaftEncoder> encoder = Optional.empty();
        private SpeedSource speedSource = SpeedSource.ENCODER;
        private int autospeedMmPerMin;
        private PrintMode printMode = PrintMode.STAND;
        private Fieldbus fieldbus = Fieldbus.NONE;
        private int repeatCount = 1;
        private int repeatGapMm;
        private boolean highFire;

        private Builder(HeadGeometry head, FiringTable firing) {
            this.head = head;
            this.firing = firing;
        }

        /** The head geometry. */
        public Builder head(HeadGeometry value) {
            head = value;
            return this;
        }

        /** The firing table. */
        public Builder firing(FiringTable value) {
            firing = value;
            return this;
        }

        /** The product detect input. */
        public Builder photocell(Photocell value) {
            photocell = value;
            return this;
        }

        /** A fitted shaft encoder. */
        public Builder encoder(ShaftEncoder value) {
            return encoder(Optional.of(value));
        }

        /** The shaft encoder, or none. */
        public Builder encoder(Optional<ShaftEncoder> value) {
            encoder = value;
            return this;
        }

        /** No shaft encoder fitted. */
        public Builder noEncoder() {
            return encoder(Optional.empty());
        }

        /** Where the line speed comes from. */
        public Builder speedSource(SpeedSource value) {
            speedSource = value;
            return this;
        }

        /** The fixed line speed. */
        public Builder autospeedMmPerMin(int value) {
            autospeedMmPerMin = value;
            return this;
        }

        /** The print mode. */
        public Builder printMode(PrintMode value) {
            printMode = value;
            return this;
        }

        /** The fieldbus stack. */
        public Builder fieldbus(Fieldbus value) {
            fieldbus = value;
            return this;
        }

        /** Prints per trigger in repeat mode. */
        public Builder repeatCount(int value) {
            repeatCount = value;
            return this;
        }

        /** Gap between repeated prints. */
        public Builder repeatGapMm(int value) {
            repeatGapMm = value;
            return this;
        }

        /** High frequency firing on or off. */
        public Builder highFire(boolean value) {
            highFire = value;
            return this;
        }

        /** The configuration, not yet validated. */
        public PrinterConfig build() {
            return new PrinterConfig(
                    head,
                    firing,
                    photocell,
                    encoder,
                    speedSource,
                    autospeedMmPerMin,
                    printMode,
                    fieldbus,
                    repeatCount,
                    repeatGapMm,
                    highFire);
        }
    }
}
