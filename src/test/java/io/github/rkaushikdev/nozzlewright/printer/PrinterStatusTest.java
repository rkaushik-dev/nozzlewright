package io.github.rkaushikdev.nozzlewright.printer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.nozzlewright.ProtocolError;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrinterStatusTest {

    @Test
    void runningState() {
        assertThat(MachineState.PRINTING.isRunning()).isTrue();
        assertThat(MachineState.READY.isRunning()).isFalse();
    }

    @Test
    void configIsOnlyAcceptedWhenStopped() {
        assertThat(MachineState.READY.acceptsConfig()).isTrue();
        assertThat(MachineState.PAUSED.acceptsConfig()).isTrue();
        assertThat(MachineState.PRINTING.acceptsConfig()).isFalse();
        assertThat(MachineState.FAULT.acceptsConfig()).isFalse();
    }

    @Test
    void decodeEmptyWord() {
        assertThat(Alarms.decode("")).isEqualTo(Alarms.NONE);
        assertThat(Alarms.decode("0")).isEqualTo(Alarms.NONE);
    }

    @Test
    void decodeSingleBit() {
        assertThat(Alarms.decode("1")).isEqualTo(Alarms.of(Alarm.INK_LOW));
        assertThat(Alarms.decode("02")).isEqualTo(Alarms.of(Alarm.INK_OUT));
    }

    @Test
    void decodeAcceptsAHexPrefix() {
        assertThat(Alarms.decode("0x20")).isEqualTo(Alarms.of(Alarm.HEAD_FAULT));
    }

    @Test
    void decodeIsCaseInsensitive() {
        assertThat(Alarms.decode("2A")).isEqualTo(Alarms.decode("2a"));
    }

    @Test
    void decodeCombinesBits() {
        Alarms combined = Alarms.decode("41");
        assertThat(combined.contains(Alarm.INK_LOW)).isTrue();
        assertThat(combined.contains(Alarm.ENCODER_LOST)).isTrue();
    }

    @Test
    void decodeDropsBitsWeDoNotKnow() {
        assertThat(Alarms.decode("FFFFFF")).isEqualTo(Alarms.decode("3FFF"));
    }

    @Test
    void decodeRejectsJunk() {
        assertThatThrownBy(() -> Alarms.decode("bananas"))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("not hexadecimal");
    }

    @Test
    void decodeRejectsANegativeWord() {
        assertThatThrownBy(() -> Alarms.decode("-4"))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("negative");
    }

    @Test
    void severityOfNothingIsInfo() {
        assertThat(Alarms.NONE.severity()).isSameAs(Severity.INFO);
    }

    @Test
    void severityPicksTheWorst() {
        assertThat(Alarms.of(Alarm.INK_LOW).severity()).isSameAs(Severity.WARNING);
        assertThat(Alarms.of(Alarm.INK_LOW, Alarm.STORE_FULL).severity()).isSameAs(Severity.WARNING);
        assertThat(Alarms.of(Alarm.INK_LOW, Alarm.HEAD_FAULT).severity()).isSameAs(Severity.STOP);
    }

    @Test
    void lowInkWarnsAndEmptyInkStops() {
        assertThat(Alarms.of(Alarm.INK_LOW).severity()).isSameAs(Severity.WARNING);
        assertThat(Alarms.of(Alarm.INK_OUT).severity()).isSameAs(Severity.STOP);
    }

    @Test
    void everyAlarmHasASeverity() {
        for (Alarm bit : Alarm.values()) {
            assertThat(Alarms.of(bit).severity()).isIn(Severity.INFO, Severity.WARNING, Severity.STOP);
        }
    }

    @Test
    void parseAHealthyMachine() {
        PrinterStatus status = PrinterStatus.parse(Map.of("STATE", "READY", "ALARMS", "0", "PRINTED", "1204"));
        assertThat(status.state()).isSameAs(MachineState.READY);
        assertThat(status.healthy()).isTrue();
        assertThat(status.printed()).isEqualTo(1204);
        assertThat(status.stopping()).isFalse();
    }

    @Test
    void parseARunningMachineWithNumbers() {
        PrinterStatus status = PrinterStatus.parse(Map.of(
                "STATE", "PRINTING",
                "ALARMS", "0",
                "PRINTED", "88213",
                "TEMP", "41.5",
                "SPEED", "36000",
                "LABEL", "carton-outer"));
        assertThat(status.headTemperatureC().getAsDouble()).isCloseTo(41.5, within(1e-9));
        assertThat(status.lineSpeedMmPerMin().getAsDouble()).isCloseTo(36000.0, within(1e-9));
        assertThat(status.activeLabel()).isEqualTo("carton-outer");
    }

    @Test
    void missingStateReadsAsOffline() {
        assertThat(PrinterStatus.parse(Map.of()).state()).isSameAs(MachineState.OFFLINE);
    }

    @Test
    void unknownStateIsAnError() {
        assertThatThrownBy(() -> PrinterStatus.parse(Map.of("STATE", "DANCING")))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("unknown machine state");
    }

    @Test
    void missingNumbersAreNoneNotZero() {
        PrinterStatus status = PrinterStatus.parse(Map.of("STATE", "READY"));
        assertThat(status.headTemperatureC()).isEmpty();
        assertThat(status.lineSpeedMmPerMin()).isEmpty();
    }

    @Test
    void unreadableNumbersAreNone() {
        PrinterStatus status = PrinterStatus.parse(Map.of("STATE", "READY", "TEMP", "warm", "PRINTED", "lots"));
        assertThat(status.headTemperatureC()).isEmpty();
        assertThat(status.printed()).isZero();
    }

    @Test
    void faultStateStopsEvenWithoutAlarmBits() {
        PrinterStatus status = PrinterStatus.parse(Map.of("STATE", "FAULT", "ALARMS", "0"));
        assertThat(status.stopping()).isTrue();
        assertThat(status.healthy()).isFalse();
    }

    @Test
    void activeAlarmsAreListedIndividually() {
        PrinterStatus status = PrinterStatus.parse(Map.of("STATE", "READY", "ALARMS", "5"));
        assertThat(status.activeAlarms()).containsExactly(Alarm.INK_LOW, Alarm.CARTRIDGE_MISSING);
        assertThat(status.names()).containsExactly("INK_LOW", "CARTRIDGE_MISSING");
    }

    @Test
    void rawRecordIsKeptForTheServiceScreen() {
        Map<String, String> record = new LinkedHashMap<>();
        record.put("STATE", "READY");
        record.put("ALARMS", "0");
        record.put("ODD", "keepme");
        assertThat(PrinterStatus.parse(record).raw()).containsEntry("ODD", "keepme");
    }
}
