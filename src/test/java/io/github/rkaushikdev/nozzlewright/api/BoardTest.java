package io.github.rkaushikdev.nozzlewright.api;

import static io.github.rkaushikdev.nozzlewright.api.Board.REFRESH_SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.nozzlewright.fleet.PollResult;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.printer.Alarm;
import io.github.rkaushikdev.nozzlewright.printer.Alarms;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import io.github.rkaushikdev.nozzlewright.session.PrinterSession;
import io.github.rkaushikdev.nozzlewright.settings.Settings;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BoardTest {

    static final Instant NOW = Instant.parse("2025-06-25T06:30:00Z");

    /** A coding machine at 41.5 degrees with 41,200 on its counter. */
    static PrinterStatus.Builder status() {
        return PrinterStatus.builder(MachineState.PRINTING).printed(41200).headTemperatureC(41.5);
    }

    /** The same machine sitting ready. */
    static PrinterStatus.Builder idle() {
        return PrinterStatus.builder(MachineState.READY).printed(41200).headTemperatureC(41.5);
    }

    static PollResult reading(String printerId, PrinterStatus.Builder status) {
        return PollResult.answered(printerId, 0.0, 0.05, status.build());
    }

    static PollResult reading(PrinterStatus.Builder status) {
        return reading("line4", status);
    }

    static PollResult reading() {
        return reading(status());
    }

    static PollResult failed(String error) {
        return PollResult.failed("line4", 0.0, 2.0, error);
    }

    /** The page for site bremen-2 at six thirty, with these readings by machine. */
    static String board(Object... pairs) {
        Map<String, PollResult> results = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            results.put((String) pairs[index], (PollResult) pairs[index + 1]);
        }
        return Board.render("bremen-2", results, NOW);
    }

    /** The API with a poller that has seen two machines. */
    static final class Harness {
        final Database database = Database.inMemory();
        final Services services;

        Harness() {
            database.createAll();
            Registry registry = new Registry("bremen-2");
            registry.add(new PrinterRecord("line4", "10.4.0.21:9100").withLine("4"));
            services = new Services(Settings.builder().siteId("bremen-2").build(), database, registry);
        }
    }

    /** Just enough poller to answer a snapshot. */
    record FakePoller(Map<String, PollResult> results) implements PollView {

        @Override
        public Map<String, PollResult> snapshot() {
            return results;
        }

        @Override
        public PrinterSession session(String printerId) {
            throw new AssertionError("the board never asks for a session");
        }
    }

    private Harness bench;

    @AfterEach
    void close() {
        if (bench != null) {
            bench.database.close();
        }
    }

    Http client() {
        bench = new Harness();
        return Http.of(bench.services);
    }

    @Test
    void anEmptySite() {
        String page = board();
        assertThat(page).contains("No machines on this site yet.");
        assertThat(page).doesNotContain("<table>");
    }

    @Test
    void thePageNamesTheSite() {
        String page = board("line4", reading());
        assertThat(page).contains("<title>bremen-2 coding</title>");
        assertThat(page).contains("<h1>bremen-2</h1>");
    }

    @Test
    void thePageRefreshesItself() {
        assertThat(board("line4", reading()))
                .contains("<meta http-equiv=\"refresh\" content=\"" + REFRESH_SECONDS + "\">");
    }

    @Test
    void theRefreshCanBeChanged() {
        String page = Board.render("bench", Map.of("line4", reading()), NOW, 5);
        assertThat(page).contains("content=\"5\"");
    }

    @Test
    void theTimeIsOnThePage() {
        assertThat(board("line4", reading())).contains("2025-06-25 06:30:00 UTC");
    }

    @Test
    void theRollupCountsMachines() {
        String page = board("line4", reading(), "line5", reading(idle()));
        assertThat(page).contains(">2<");
        assertThat(page).contains("machines");
    }

    @Test
    void aMachineThatIsCodingIsGreen() {
        assertThat(board("line4", reading())).contains("class=\"state ok\">printing<");
    }

    @Test
    void aMachineInWarningIsAmber() {
        assertThat(board("line4", reading(status().alarms(Alarms.of(Alarm.INK_LOW)))))
                .contains("class=\"state warn\"");
    }

    @Test
    void aMachineInFaultIsRed() {
        assertThat(board("line4", reading(status().alarms(Alarms.of(Alarm.HEAD_FAULT)))))
                .contains("class=\"state stop\"");
    }

    @Test
    void aMachineSittingIdleIsGrey() {
        assertThat(board("line4", reading(idle()))).contains("class=\"state idle\">ready<");
    }

    @Test
    void aMachineThatDidNotAnswer() {
        String page = board("line4", failed("no route to host"));
        assertThat(page).contains("no answer");
        assertThat(page).contains("no route to host");
    }

    @Test
    void theAlarmsAreNamed() {
        assertThat(board("line4", reading(status().alarms(Alarms.of(Alarm.INK_LOW)))))
                .contains("INK_LOW");
    }

    @Test
    void aMachineWithNoAlarmsShowsADash() {
        assertThat(board("line4", reading())).contains("<td>-</td>");
    }

    @Test
    void theCounterIsGrouped() {
        assertThat(board("line4", reading())).contains("41,200");
    }

    @Test
    void theHeadTemperatureIsShown() {
        assertThat(board("line4", reading())).contains("41.5 C");
    }

    @Test
    void aMachineWithNoTemperature() {
        assertThat(board("line4", reading(status().headTemperatureC(OptionalDouble.empty()))))
                .doesNotContain("41.5 C");
    }

    @Test
    void theMachinesAreSorted() {
        String page = board("line9", reading("line9", status()), "line4", reading("line4", status()));
        assertThat(page.indexOf("line4")).isLessThan(page.indexOf("line9"));
    }

    @Test
    void aMachineNameWithHtmlInIt() {
        String page = board("line4", reading("<script>x</script>", status()));
        assertThat(page).doesNotContain("<script>x</script>");
        assertThat(page).contains("&lt;script&gt;");
    }

    @Test
    void thePageIsHtml() {
        String page = board("line4", reading());
        assertThat(page).startsWith("<!doctype html>");
        assertThat(page.strip()).endsWith("</html>");
    }

    @Test
    void theBoardIsServed() {
        Http client = client();
        bench.services.poller(new FakePoller(Map.of("line4", reading())));
        Http.Answer response = client.get("/board");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("text/html");
        assertThat(response.text()).contains("bremen-2");
        assertThat(response.text()).contains("41,200");
    }

    @Test
    void theBoardWithoutAPoller() {
        Http.Answer response = client().get("/board");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.text()).contains("No machines on this site yet.");
    }

    @Test
    void theBoardNeedsNoKey() {
        Http client = client();
        bench.services.apiKeys(Set.of("alpha"));
        assertThat(client.get("/board").status()).isEqualTo(200);
        assertThat(client.get("/fleet/status").status()).isEqualTo(401);
    }
}
