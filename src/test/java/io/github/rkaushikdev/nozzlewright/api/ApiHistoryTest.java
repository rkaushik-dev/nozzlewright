package io.github.rkaushikdev.nozzlewright.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.nozzlewright.fleet.PollResult;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.printer.Alarms;
import io.github.rkaushikdev.nozzlewright.printer.JobOutcome;
import io.github.rkaushikdev.nozzlewright.printer.JobResult;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import io.github.rkaushikdev.nozzlewright.settings.Settings;
import io.github.rkaushikdev.nozzlewright.storage.AlarmRepo;
import io.github.rkaushikdev.nozzlewright.storage.AuditAction;
import io.github.rkaushikdev.nozzlewright.storage.AuditLog;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.storage.JobRepo;
import io.github.rkaushikdev.nozzlewright.storage.Printer;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import io.github.rkaushikdev.nozzlewright.storage.RunDetails;
import io.github.rkaushikdev.nozzlewright.storage.SampleRepo;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiHistoryTest {

    static final Instant START = Instant.parse("2025-05-07T06:00:00Z");

    static final String SINCE = "2025-05-07T00:00:00+00:00";

    static final String UNTIL = "2025-05-08T00:00:00+00:00";

    /** A gateway database with a shift of history and the API on top. */
    static final class Harness {
        final Database database = Database.inMemory();
        final Services services;

        Harness() {
            database.createAll();
            Registry registry = new Registry("bench");
            registry.add(new PrinterRecord("line4", "10.4.0.21:9100").withLine("4"));
            database.run(unit -> {
                Printer printer = new PrinterRepo(unit).upsert(registry.get("line4"));
                SampleRepo samples = new SampleRepo(unit);
                for (int step = 0; step < 120; step++) {
                    samples.record(
                            printer,
                            PollResult.answered(
                                    "line4", 0.0, 0.05, new PrinterStatus(MachineState.PRINTING, Alarms.NONE, step)),
                            START.plus(Duration.ofMinutes(step)));
                }
                JobRepo jobs = new JobRepo(unit);
                for (int step = 0; step < 5; step++) {
                    jobs.record(
                            printer,
                            new JobResult("carton", JobOutcome.COMPLETE, 100, 100, 0, 30.0),
                            RunDetails.none().withStartedAt(START.plus(Duration.ofHours(step))));
                }
                AlarmRepo alarms = new AlarmRepo(unit);
                alarms.open(printer, "INK_LOW", "warning", START);
                alarms.close(printer, "INK_LOW", START.plus(Duration.ofMinutes(30)));
                AuditLog log = new AuditLog(unit, "mueller");
                log.entry(AuditAction.LABEL_SELECTED)
                        .printerId("line4")
                        .subject("carton")
                        .at(START)
                        .write();
                log.entry(AuditAction.CONFIG_APPLIED)
                        .printerId("line4")
                        .subject("head.horizontal_dpi")
                        .before("300")
                        .after("600")
                        .at(START.plus(Duration.ofMinutes(5)))
                        .write();
            });
            services = new Services(Settings.builder().siteId("bench").build(), database, registry);
        }

        void close() {
            database.close();
        }
    }

    private Harness bench;
    private Http client;

    @BeforeEach
    void open() {
        bench = new Harness();
        client = Http.of(bench.services);
    }

    @AfterEach
    void close() {
        bench.close();
    }

    static List<Integer> upTo(int count) {
        return IntStream.range(0, count).boxed().toList();
    }

    @Test
    void pageParamsBoundTheWindow() {
        PageParams params = new PageParams(10, 20);
        assertThat(params.stop()).isEqualTo(30);
        assertThat(params.slice(upTo(100)))
                .isEqualTo(IntStream.range(20, 30).boxed().toList());
    }

    @Test
    void aPageOfEverything() {
        Page<Integer> page = Page.ofAll(upTo(10), new PageParams(4, 0));
        assertThat(page.items()).containsExactly(0, 1, 2, 3);
        assertThat(page.total()).hasValue(10);
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    void theLastPageSaysThereIsNoMore() {
        Page<Integer> page = Page.ofAll(upTo(10), new PageParams(4, 8));
        assertThat(page.items()).containsExactly(8, 9);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void aNarrowedPageGuessesAtMore() {
        PageParams params = new PageParams(3, 0);
        assertThat(Page.of(List.of(1, 2, 3), params).hasMore()).isTrue();
        assertThat(Page.of(List.of(1, 2), params).hasMore()).isFalse();
    }

    @Test
    void samplesPage() {
        Http.Answer response = client.get("/history/samples/line4", "since", SINCE, "until", UNTIL);
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertThat(body.get("limit").asInt()).isEqualTo(PageParams.DEFAULT_PAGE);
        assertThat(body.get("items")).hasSize(PageParams.DEFAULT_PAGE);
        assertThat(body.get("total").asInt()).isEqualTo(120);
        assertThat(body.get("has_more").asBoolean()).isTrue();
        assertThat(body.get("items").get(0).get("printer_id").asText()).isEqualTo("line4");
    }

    @Test
    void samplesSecondPage() {
        JsonNode body = client.get(
                        "/history/samples/line4", "since", SINCE, "until", UNTIL, "limit", "50", "offset", "100")
                .json();
        assertThat(body.get("items")).hasSize(20);
        assertThat(body.get("has_more").asBoolean()).isFalse();
    }

    @Test
    void thePageSizeIsCapped() {
        Http.Answer response = client.get(
                "/history/samples/line4",
                "since",
                SINCE,
                "until",
                UNTIL,
                "limit",
                String.valueOf(PageParams.MAX_PAGE + 1));
        assertThat(response.status()).isEqualTo(422);
    }

    @Test
    void aWindowThatRunsBackwards() {
        Http.Answer response = client.get("/history/samples/line4", "since", UNTIL, "until", SINCE);
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("detail").asText()).contains("end after it starts");
    }

    @Test
    void aDefaultWindowIsOneShift() {
        JsonNode body = client.get("/history/samples/line4").json();
        assertThat(body.get("total").asInt()).isZero();
    }

    @Test
    void samplesForAnUnknownMachine() {
        Http.Answer response = client.get("/history/samples/ghost", "since", SINCE, "until", UNTIL);
        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void runsPage() {
        JsonNode body = client.get("/history/runs/line4").json();
        assertThat(body.get("items")).hasSize(5);
        JsonNode first = body.get("items").get(0);
        assertThat(first.get("label").asText()).isEqualTo("carton");
        assertThat(first.get("outcome").asText()).isEqualTo("complete");
        assertThat(first.get("short").asBoolean()).isFalse();
    }

    @Test
    void runsForAnUnknownMachine() {
        assertThat(client.get("/history/runs/ghost").status()).isEqualTo(404);
    }

    @Test
    void alarmHistory() {
        JsonNode body = client.get("/history/alarms/line4").json();
        assertThat(body.get("items")).hasSize(1);
        assertThat(body.get("items").get(0).get("name").asText()).isEqualTo("INK_LOW");
        assertThat(body.get("items").get(0).get("cleared_at").isNull()).isFalse();
    }

    @Test
    void theAuditTrail() {
        JsonNode body = client.get("/history/audit").json();
        assertThat(body.get("items")).hasSize(2);
        assertThat(body.get("items").get(0).get("action").asText()).isEqualTo("config.applied");
        assertThat(body.get("items").get(0).get("actor").asText()).isEqualTo("mueller");
    }

    @Test
    void theAuditTrailByMachine() {
        JsonNode body = client.get("/history/audit", "printer_id", "line4").json();
        assertThat(body.get("items")).hasSize(2);
        JsonNode empty = client.get("/history/audit", "printer_id", "ghost").json();
        assertThat(empty.get("items")).isEmpty();
    }

    @Test
    void theAuditTrailByActor() {
        JsonNode body = client.get("/history/audit", "actor", "mueller").json();
        assertThat(body.get("items")).hasSize(2);
    }

    @Test
    void theAuditTrailByAction() {
        JsonNode body = client.get("/history/audit", "action", "config.applied").json();
        assertThat(body.get("items")).hasSize(1);
        assertThat(body.get("items").get(0).get("subject").asText()).isEqualTo("head.horizontal_dpi");
        assertThat(body.get("items").get(0).get("before").asText()).isEqualTo("300");
    }
}
