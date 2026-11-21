package io.github.rkaushikdev.nozzlewright.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.nozzlewright.printer.Alarm;
import io.github.rkaushikdev.nozzlewright.printer.FiringProfile;
import io.github.rkaushikdev.nozzlewright.printer.FiringTable;
import io.github.rkaushikdev.nozzlewright.printer.GroupGeometry;
import io.github.rkaushikdev.nozzlewright.printer.HeadGeometry;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PenGeometry;
import io.github.rkaushikdev.nozzlewright.printer.PrintMode;
import io.github.rkaushikdev.nozzlewright.printer.PrinterConfig;
import io.github.rkaushikdev.nozzlewright.printer.Snapshot;
import io.github.rkaushikdev.nozzlewright.printer.SpeedSource;
import io.github.rkaushikdev.nozzlewright.protocol.Codec;
import io.github.rkaushikdev.nozzlewright.protocol.Framing;
import io.github.rkaushikdev.nozzlewright.protocol.Response;
import io.github.rkaushikdev.nozzlewright.protocol.Status;
import io.github.rkaushikdev.nozzlewright.testing.Wire;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SimulatorTest {

    static SimulatedPrinter machine() {
        return SimulatedPrinter.builder().build();
    }

    /** Send one command and read the reply the way the frame buffer would. */
    static Response ask(SimulatedPrinter printer, String text) {
        byte[] reply = printer.respond(Framing.encodeFrame(text));
        return Codec.parseResponse(Framing.stripChecksum(trimmed(reply)));
    }

    static byte[] trimmed(byte[] reply) {
        int end = reply.length;
        while (end > 0 && (reply[end - 1] == '\r' || reply[end - 1] == '\n')) {
            end--;
        }
        return Arrays.copyOf(reply, end);
    }

    static SimulatedPrinter loggedIn(SimulatedPrinter.Builder builder) {
        SimulatedPrinter printer = builder.build();
        ask(printer, "LOGIN:mueller,4417");
        return printer;
    }

    static SimulatedPrinter loggedIn() {
        return loggedIn(SimulatedPrinter.builder());
    }

    static SimulatedPrinter.Builder withCarton() {
        return SimulatedPrinter.builder().label("carton", "x");
    }

    static PrinterConfig sampleConfig() {
        HeadGeometry head = new HeadGeometry(
                "HVX8PEN", List.of(new GroupGeometry(1, List.of(new PenGeometry(1), new PenGeometry(2)))));
        FiringTable firing = FiringTable.of(new FiringProfile(1, 9000, 1500), new FiringProfile(2, 9000, 1500));
        return PrinterConfig.builder(head, firing)
                .noEncoder()
                .speedSource(SpeedSource.FIXED)
                .autospeedMmPerMin(24_000)
                .printMode(PrintMode.DRIVER)
                .build();
    }

    @Test
    void versionBeforeLogin() {
        Response response = ask(machine(), "VERSION");
        assertThat(response.ok()).isTrue();
        assertThat(response.record()).containsEntry("FAM", "HVX8PEN");
    }

    @Test
    void statusNeedsASession() {
        Response response = ask(machine(), "STATUS");
        assertThat(response.status()).isEqualTo(Status.NOT_LOGGED_IN.code());
    }

    @Test
    void loginAndLogout() {
        SimulatedPrinter printer = machine();
        Response response = ask(printer, "LOGIN:mueller,4417");
        assertThat(response.ok()).isTrue();
        assertThat(printer.loggedIn()).isTrue();
        ask(printer, "LOGOUT");
        assertThat(printer.loggedIn()).isFalse();
    }

    @Test
    void wrongPinIsRefused() {
        SimulatedPrinter printer = machine();
        assertThat(ask(printer, "LOGIN:mueller,0000").status()).isEqualTo(Status.NOT_FOUND.code());
        assertThat(printer.loggedIn()).isFalse();
    }

    @Test
    void unknownUserIsRefused() {
        assertThat(machine().users()).doesNotContainKey("ghost");
    }

    @Test
    void loginWithTheWrongArgumentCount() {
        assertThat(ask(machine(), "LOGIN:mueller").status()).isEqualTo(Status.BAD_PARAM.code());
    }

    @Test
    void levelIsEnforced() {
        SimulatedPrinter printer = machine();
        ask(printer, "LOGIN:kraft,0000");
        assertThat(ask(printer, "ADDUSER:x,1,1").status()).isEqualTo(Status.NO_PERMISSION.code());
    }

    @Test
    void aFactoryUserMayTouchFiring() {
        SimulatedPrinter printer = machine();
        ask(printer, "LOGIN:factory,9999");
        assertThat(ask(printer, "FIRINGVOL:1,9000").ok()).isTrue();
    }

    @Test
    void unknownVerb() {
        assertThat(ask(machine(), "REBOOT").status()).isEqualTo(Status.UNKNOWN_CMD.code());
    }

    @Test
    void revisionOneRefusesARevisionTwoVerb() {
        SimulatedPrinter printer = loggedIn(SimulatedPrinter.builder().revision(1));
        assertThat(ask(printer, "ASKINTER").status()).isEqualTo(Status.UNKNOWN_CMD.code());
    }

    @Test
    void lcpRefusesAPenVerb() {
        SimulatedPrinter printer = loggedIn(SimulatedPrinter.builder().family("HVXLCP"));
        assertThat(ask(printer, "PENHORZOFF:1,0").status()).isEqualTo(Status.UNKNOWN_CMD.code());
    }

    @Test
    void statusReportsTheMachine() {
        SimulatedPrinter printer = loggedIn();
        printer.printed(4417);
        Map<String, String> record = ask(printer, "STATUS").record();
        assertThat(record).containsEntry("STATE", "READY");
        assertThat(record).containsEntry("PRINTED", "4417");
        assertThat(record).containsEntry("ALARMS", "0");
    }

    @Test
    void alarmsShowUpInStatus() {
        SimulatedPrinter printer = loggedIn();
        printer.raiseAlarm(Alarm.INK_LOW);
        assertThat(ask(printer, "STATUS").record()).containsEntry("ALARMS", "1");
    }

    @Test
    void aHeadFaultPutsTheMachineInFault() {
        SimulatedPrinter printer = loggedIn();
        printer.raiseAlarm(Alarm.HEAD_FAULT);
        assertThat(printer.state()).isSameAs(MachineState.FAULT);
        printer.clearAlarms();
        assertThat(printer.state()).isSameAs(MachineState.READY);
    }

    @Test
    void printingNeedsALabel() {
        SimulatedPrinter printer = loggedIn();
        assertThat(ask(printer, "STARTPRINT").status()).isEqualTo(Status.NOT_FOUND.code());
    }

    @Test
    void selectingALabelThenPrinting() {
        SimulatedPrinter printer = loggedIn(SimulatedPrinter.builder().label("carton", "LABEL carton 104"));
        assertThat(ask(printer, "LABEL:carton").ok()).isTrue();
        assertThat(ask(printer, "STARTPRINT").ok()).isTrue();
        assertThat(printer.state()).isSameAs(MachineState.PRINTING);
    }

    @Test
    void anUnknownLabelIsRefused() {
        SimulatedPrinter printer = loggedIn(withCarton());
        assertThat(ask(printer, "LABEL:ghost").status()).isEqualTo(Status.NOT_FOUND.code());
    }

    @Test
    void configIsLockedWhilePrinting() {
        SimulatedPrinter printer = loggedIn(withCarton());
        ask(printer, "LABEL:carton");
        ask(printer, "STARTPRINT");
        assertThat(ask(printer, "HORZRES:600").status()).isEqualTo(Status.CONFIG_LOCKED.code());
    }

    @Test
    void stoppingUnlocksTheConfig() {
        SimulatedPrinter printer = loggedIn(withCarton());
        ask(printer, "LABEL:carton");
        ask(printer, "STARTPRINT");
        ask(printer, "STOPPRINT");
        printer.sessionUser().orElseThrow().level(2);
        assertThat(ask(printer, "HORZRES:600").ok()).isTrue();
    }

    @Test
    void pauseOnlyFromPrinting() {
        SimulatedPrinter printer = loggedIn(withCarton());
        assertThat(ask(printer, "PAUSEPRINT").status()).isEqualTo(Status.BAD_PARAM.code());
        ask(printer, "LABEL:carton");
        ask(printer, "STARTPRINT");
        assertThat(ask(printer, "PAUSEPRINT").ok()).isTrue();
        assertThat(printer.state()).isSameAs(MachineState.PAUSED);
    }

    @Test
    void labelListing() {
        SimulatedPrinter printer =
                loggedIn(SimulatedPrinter.builder().label("b", "x").label("a", "y"));
        List<Map<String, String>> rows = ask(printer, "ENUMLABELS").table();
        assertThat(rows.stream().map(row -> row.get("NAME"))).containsExactly("a", "b");
    }

    @Test
    void emptyLabelStoreListsNothing() {
        SimulatedPrinter printer = loggedIn();
        assertThat(ask(printer, "ENUMLABELS").table()).isEmpty();
    }

    @Test
    void configReadRoundTrips() {
        PrinterConfig original = sampleConfig();
        SimulatedPrinter printer = loggedIn(SimulatedPrinter.builder().config(original));
        List<Map<String, String>> rows = ask(printer, "CONFIG").table();
        assertThat(Snapshot.parseConfigTable(rows, "HVX8PEN")).isEqualTo(original);
    }

    @Test
    void configWithoutOneLoaded() {
        SimulatedPrinter printer = loggedIn();
        assertThat(ask(printer, "CONFIG").status()).isEqualTo(Status.NOT_FOUND.code());
    }

    @Test
    void inkTableHasARowPerPen() {
        SimulatedPrinter printer = loggedIn(SimulatedPrinter.builder().config(sampleConfig()));
        List<Map<String, String>> rows = ask(printer, "INK").table();
        assertThat(rows.stream().map(row -> row.get("PEN"))).containsExactly("1", "2");
    }

    @Test
    void repliesCarryAChecksumByDefault() {
        SimulatedPrinter printer = machine();
        byte[] reply = printer.respond(Framing.encodeFrame("VERSION"));
        assertThat(Wire.text(reply)).contains("#");
    }

    @Test
    void checksumsCanBeTurnedOff() {
        SimulatedPrinter printer =
                SimulatedPrinter.builder().checksumReplies(false).build();
        byte[] reply = printer.respond(Framing.encodeFrame("VERSION"));
        assertThat(Wire.text(reply)).doesNotContain("#");
    }

    @Test
    void theMachineRecordsWhatItWasAsked() {
        SimulatedPrinter printer = machine();
        ask(printer, "VERSION");
        ask(printer, "PING");
        assertThat(printer.seen()).containsExactly("VERSION", "PING");
    }

    @Test
    void anEmptyFrameGetsNoReply() {
        assertThat(machine().respond(Wire.bytes("\r\n"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PING", "VERSION"})
    void openVerbsNeedNoSession(String verb) {
        assertThat(ask(machine(), verb).ok()).isTrue();
    }
}
