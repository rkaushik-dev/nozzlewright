package io.github.rkaushikdev.nozzlewright.printer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.ProtocolError;
import io.github.rkaushikdev.nozzlewright.cli.Cli;
import io.github.rkaushikdev.nozzlewright.cli.Context;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.protocol.Catalogue;
import io.github.rkaushikdev.nozzlewright.session.Credentials;
import io.github.rkaushikdev.nozzlewright.simulator.SimulatedPrinter;
import io.github.rkaushikdev.nozzlewright.testing.Fast;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClockTest {

    static final LocalDateTime LOCAL = LocalDateTime.of(2025, 6, 19, 14, 30, 0);

    /** A GETTIME record for {@link #LOCAL}, with some fields changed. */
    static Map<String, String> record(Object... changes) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("YEAR", "2025");
        body.put("MONTH", "6");
        body.put("DAY", "19");
        body.put("HOUR", "14");
        body.put("MIN", "30");
        body.put("SEC", "0");
        for (int index = 0; index < changes.length; index += 2) {
            body.put((String) changes[index], String.valueOf(changes[index + 1]));
        }
        return body;
    }

    static ClockReading parse(Map<String, String> record) {
        return ClockReading.parse("line4", record, LOCAL);
    }

    static SimulatedPrinter machine() {
        return SimulatedPrinter.builder()
                .config(SimulatedBench.fixedSpeedConfig(1))
                .labels(Map.of("carton", "x"))
                .build();
    }

    @Test
    void theVerbsAreInTheCatalogue() {
        assertThat(Catalogue.lookup("SETTIME").args()).hasSize(6);
        assertThat(Catalogue.lookup("GETTIME").args()).isEmpty();
    }

    @Test
    void aClockThatAgrees() {
        ClockReading reading = parse(record());
        assertThat(reading.drift()).isEqualTo(Duration.ZERO);
        assertThat(reading.outOfTolerance()).isFalse();
        assertThat(reading.lost()).isFalse();
    }

    @Test
    void aClockThatIsAhead() {
        ClockReading reading = parse(record("SEC", 30));
        assertThat(reading.driftSeconds()).isEqualTo(30.0);
        assertThat(reading.summary()).isEqualTo("line4 is 30.0 seconds ahead");
    }

    @Test
    void aClockThatIsBehind() {
        ClockReading reading = parse(record("MIN", 29));
        assertThat(reading.driftSeconds()).isEqualTo(-60.0);
        assertThat(reading.summary()).isEqualTo("line4 is 60.0 seconds behind");
    }

    @Test
    void aMinuteOfDriftIsTolerated() {
        assertThat(parse(record("MIN", 31)).outOfTolerance()).isFalse();
    }

    @Test
    void fiveMinutesIsNot() {
        assertThat(parse(record("MIN", 35)).outOfTolerance()).isTrue();
    }

    @Test
    void theToleranceCanBeTightened() {
        ClockReading reading = parse(record("SEC", 30));
        assertThat(reading.outOfTolerance(Duration.ofSeconds(10))).isTrue();
        assertThat(reading.outOfTolerance(ClockReading.DEFAULT_TOLERANCE)).isFalse();
    }

    @Test
    void aClockThatHasLostItsBattery() {
        ClockReading reading = parse(record("YEAR", 2000, "MONTH", 1, "DAY", 1));
        assertThat(reading.lost()).isTrue();
        assertThat(reading.drift().abs()).isGreaterThan(ClockReading.LOST_CLOCK);
        assertThat(reading.summary()).contains("lost its clock");
    }

    @Test
    void aReadingFlattens() {
        Map<String, Object> body = parse(record("SEC", 12)).asMap();
        assertThat(body.get("printer_id")).isEqualTo("line4");
        assertThat(body.get("drift_seconds")).isEqualTo(12.0);
        assertThat(body.get("round_trip_ms")).isEqualTo(0.0);
        assertThat(body.get("lost")).isEqualTo(false);
    }

    @Test
    void theRoundTripShowsUp() {
        ClockReading reading = new ClockReading("line4", LOCAL, LOCAL, Duration.ofMillis(850));
        assertThat(reading.asMap().get("round_trip_ms")).isEqualTo(850.0);
    }

    @Test
    void aReadingWithAMissingField() {
        Map<String, String> body = record();
        body.remove("SEC");
        assertThatThrownBy(() -> parse(body)).isInstanceOf(ProtocolError.class).hasMessageContaining("no SEC");
    }

    @Test
    void aReadingThatIsNotNumbers() {
        assertThatThrownBy(() -> parse(record("HOUR", "two")))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("not a number");
    }

    @Test
    void aTimeThatDoesNotExist() {
        assertThatThrownBy(() -> parse(record("MONTH", 2, "DAY", 30)))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void theSetArgumentsAreInOrder() {
        assertThat(ClockReading.formatSetArgs(LocalDateTime.of(2025, 6, 19, 14, 30, 45, 999_000)))
                .containsExactly(2025, 6, 19, 14, 30, 45);
    }

    /** A live session on a simulated machine. */
    @Nested
    class OnAMachine {

        final SimulatedBench bench;

        OnAMachine() throws InterruptedException {
            bench = SimulatedBench.start(
                    "line4", machine(), new Credentials("factory", "9999"), Fast.session(0.05, 1.0));
        }

        @AfterEach
        void stop() throws InterruptedException {
            bench.stop();
        }

        @Test
        void aMachineThatAgreesWithUs() throws InterruptedException {
            ClockReading reading = Operations.readClock(bench.session);
            assertThat(Math.abs(reading.driftSeconds())).isLessThanOrEqualTo(1.0);
            assertThat(reading.outOfTolerance()).isFalse();
        }

        @Test
        void aMachineThatHasDrifted() throws InterruptedException {
            bench.machine.clockOffset(Duration.ofMinutes(7));
            ClockReading reading = Operations.readClock(bench.session);
            assertThat(reading.outOfTolerance()).isTrue();
            assertThat(reading.driftSeconds()).isBetween(400.0, 430.0);
        }

        @Test
        void aMachineThatLostItsClock() throws InterruptedException {
            bench.machine.clockOffset(Duration.ofDays(-9000));
            assertThat(Operations.readClock(bench.session).lost()).isTrue();
        }

        @Test
        void settingTheClockPutsItRight() throws InterruptedException {
            bench.machine.clockOffset(Duration.ofMinutes(7));
            ClockReading reading = Operations.setClock(bench.session);
            assertThat(Math.abs(reading.driftSeconds())).isLessThanOrEqualTo(1.0);
            assertThat(Math.abs(bench.machine.clockOffset().toMillis())).isLessThanOrEqualTo(1000L);
        }

        @Test
        void settingTheClockToANamedTime() throws InterruptedException {
            Operations.setClock(bench.session, LocalDateTime.now().plusHours(2));
            assertThat(bench.machine.clockOffset().toSeconds()).isBetween(7100L, 7300L);
        }

        @Test
        void theRoundTripIsMeasured() throws InterruptedException {
            assertThat(Operations.readClock(bench.session).roundTrip()).isGreaterThanOrEqualTo(Duration.ZERO);
        }
    }

    /** The clock commands as a technician meets them, on the console. */
    @Nested
    class OnTheConsole {

        @TempDir
        Path tmp;

        final SimulatedPrinter console = machine();

        void register() {
            Registry registry = new Registry("bench");
            registry.add(new PrinterRecord("line4", "sim4").withKind("loopback").withLine("4"));
            registry.save(tmp.resolve(Context.REGISTRY_NAME));
        }

        Cli.Result invoke(String... args) {
            register();
            List<String> full = new ArrayList<>(List.of("--user", "factory", "--pin", "9999"));
            full.addAll(List.of(args));
            return Cli.clean().machines(Map.of("line4", console)).in(tmp, full.toArray(String[]::new));
        }

        @Test
        void readingTheClockFromTheConsole() {
            Cli.Result result = invoke("printer", "clock", "line4");
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(result.stdout()).contains("drift_seconds=");
        }

        @Test
        void aDriftedClockFailsTheCheck() {
            console.clockOffset(Duration.ofMinutes(9));
            Cli.Result result = invoke("printer", "clock", "line4");
            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(result.stderr()).contains("ahead");
        }

        @Test
        void aTighterToleranceFailsSooner() {
            console.clockOffset(Duration.ofSeconds(30));
            assertThat(invoke("printer", "clock", "line4").exitCode()).isZero();
            assertThat(invoke("printer", "clock", "line4", "--tolerance-s", "5").exitCode())
                    .isEqualTo(1);
        }

        @Test
        void settingTheClockFromTheConsole() throws Exception {
            console.clockOffset(Duration.ofMinutes(9));
            JsonNode body = new ObjectMapper()
                    .readTree(invoke("printer", "clock", "line4", "--set", "--json")
                            .stdout());
            assertThat(Math.abs(body.get("drift_seconds").asDouble())).isLessThanOrEqualTo(1.0);
            assertThat(Math.abs(console.clockOffset().toMillis())).isLessThanOrEqualTo(1000L);
        }
    }
}
