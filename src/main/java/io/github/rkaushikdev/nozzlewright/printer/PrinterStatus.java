package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ProtocolError;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * One snapshot of a machine, decoded from its STATUS reply.
 *
 * <p>STATUS comes back as a record. The interesting part is ALARMS, a hexadecimal word where each
 * bit is one thing the machine is unhappy about; see {@link Alarms}.
 *
 * @param state what the machine says it is doing
 * @param alarms the raised alarm bits
 * @param printed the machine's own print counter
 * @param headTemperatureC the head temperature, when the machine sent a readable one
 * @param lineSpeedMmPerMin the line speed, when the machine sent a readable one
 * @param activeLabel the selected label, empty when there is none
 * @param raw the record exactly as it came, for the service screen
 */
public record PrinterStatus(
        MachineState state,
        Alarms alarms,
        long printed,
        OptionalDouble headTemperatureC,
        OptionalDouble lineSpeedMmPerMin,
        String activeLabel,
        Map<String, String> raw) {

    /** Copies the raw record, keeping its order. */
    public PrinterStatus {
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    /** A machine in {@code state} with nothing raised, nothing printed and nothing else known. */
    public PrinterStatus(MachineState state) {
        this(state, Alarms.NONE);
    }

    /** A machine in {@code state} with these alarms and nothing else known. */
    public PrinterStatus(MachineState state, Alarms alarms) {
        this(state, alarms, 0);
    }

    /** A machine in {@code state} with these alarms and this print counter. */
    public PrinterStatus(MachineState state, Alarms alarms, long printed) {
        this(state, alarms, printed, OptionalDouble.empty(), OptionalDouble.empty(), "", Map.of());
    }

    /** A builder for a machine in {@code state}, everything else at its default. */
    public static Builder builder(MachineState state) {
        return new Builder(state);
    }

    /** True when nothing is asking for attention. */
    public boolean healthy() {
        return alarms.isEmpty() && state != MachineState.FAULT;
    }

    /** How bad the worst thing is. A machine in fault is a stop whatever its alarm bits say. */
    public Severity severity() {
        if (state == MachineState.FAULT) {
            return Severity.STOP;
        }
        return alarms.severity();
    }

    /** True when something here should take the line down. */
    public boolean stopping() {
        return severity() == Severity.STOP;
    }

    /** Every raised alarm, one entry each, in bit order. */
    public List<Alarm> activeAlarms() {
        return alarms.active();
    }

    /** Readable names for the raised alarms. */
    public List<String> names() {
        return alarms.names();
    }

    /**
     * Build a status from a STATUS reply record.
     *
     * <p>A missing state reads as {@link MachineState#OFFLINE}. A missing or unreadable print counter
     * reads as nought, and a missing or unreadable temperature or speed as absent rather than nought.
     *
     * @throws ProtocolError when the state is not one the controller knows or the alarm word is junk
     */
    public static PrinterStatus parse(Map<String, String> record) {
        String rawState = record.getOrDefault("STATE", "").strip().toUpperCase(Locale.ROOT);
        MachineState state;
        try {
            state = rawState.isEmpty() ? MachineState.OFFLINE : MachineState.parse(rawState);
        } catch (IllegalArgumentException error) {
            throw new ProtocolError("unknown machine state " + Repr.of(rawState));
        }
        return builder(state)
                .alarms(Alarms.decode(record.getOrDefault("ALARMS", "")))
                .printed(asLong(record.getOrDefault("PRINTED", "0")))
                .headTemperatureC(asDouble(record.get("TEMP")))
                .lineSpeedMmPerMin(asDouble(record.get("SPEED")))
                .activeLabel(record.getOrDefault("LABEL", "").strip())
                .raw(record)
                .build();
    }

    private static long asLong(String text) {
        try {
            return Numbers.parseLong(text);
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static OptionalDouble asDouble(String text) {
        if (text == null || text.isBlank()) {
            return OptionalDouble.empty();
        }
        try {
            return OptionalDouble.of(Numbers.parseDouble(text));
        } catch (NumberFormatException error) {
            return OptionalDouble.empty();
        }
    }

    /** Assembles a {@link PrinterStatus}. */
    public static final class Builder {
        private final MachineState state;
        private Alarms alarms = Alarms.NONE;
        private long printed;
        private OptionalDouble headTemperatureC = OptionalDouble.empty();
        private OptionalDouble lineSpeedMmPerMin = OptionalDouble.empty();
        private String activeLabel = "";
        private Map<String, String> raw = Map.of();

        private Builder(MachineState state) {
            this.state = state;
        }

        /** The raised alarm bits. */
        public Builder alarms(Alarms value) {
            alarms = value;
            return this;
        }

        /** The machine's print counter. */
        public Builder printed(long value) {
            printed = value;
            return this;
        }

        /** The head temperature in degrees Celsius. */
        public Builder headTemperatureC(double value) {
            return headTemperatureC(OptionalDouble.of(value));
        }

        /** The head temperature, or absent. */
        public Builder headTemperatureC(OptionalDouble value) {
            headTemperatureC = value;
            return this;
        }

        /** The line speed in millimetres a minute. */
        public Builder lineSpeedMmPerMin(double value) {
            return lineSpeedMmPerMin(OptionalDouble.of(value));
        }

        /** The line speed, or absent. */
        public Builder lineSpeedMmPerMin(OptionalDouble value) {
            lineSpeedMmPerMin = value;
            return this;
        }

        /** The selected label. */
        public Builder activeLabel(String value) {
            activeLabel = value;
            return this;
        }

        /** The record as it came off the wire. */
        public Builder raw(Map<String, String> value) {
            raw = value;
            return this;
        }

        /** The status. */
        public PrinterStatus build() {
            return new PrinterStatus(state, alarms, printed, headTemperatureC, lineSpeedMmPerMin, activeLabel, raw);
        }
    }
}
