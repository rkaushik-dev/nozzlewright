package io.github.rkaushikdev.nozzlewright.cli;

import static io.github.rkaushikdev.nozzlewright.cli.Cli.invoke;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.storage.AlarmRecord;
import io.github.rkaushikdev.nozzlewright.storage.AlarmRepo;
import io.github.rkaushikdev.nozzlewright.storage.AuditAction;
import io.github.rkaushikdev.nozzlewright.storage.AuditEntry;
import io.github.rkaushikdev.nozzlewright.storage.AuditLog;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.storage.Printer;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import io.github.rkaushikdev.nozzlewright.util.Times;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CliAckTest {

    @TempDir
    Path tmp;

    Database open() {
        return Database.file(tmp.resolve(Context.DATABASE_NAME));
    }

    /** A machine with two alarms up, one of them long enough to have climbed. */
    void seed(boolean old) {
        Instant raised = Times.now().minus(old ? Duration.ofHours(2) : Duration.ofMinutes(1));
        try (Database database = open()) {
            database.createAll();
            database.run(unit -> {
                Printer printer =
                        new PrinterRepo(unit).upsert(new PrinterRecord("line4", "10.4.0.21:9100").withLine("4"));
                AlarmRepo alarms = new AlarmRepo(unit);
                alarms.open(printer, "INK_LOW", "warning", raised);
                alarms.open(printer, "DOOR_OPEN", "info", raised);
            });
        }
    }

    void seed() {
        seed(false);
    }

    Cli.Result asUser(String... args) {
        List<String> full = new ArrayList<>(List.of("--user", "mueller"));
        full.addAll(List.of(args));
        return invoke(tmp, full.toArray(String[]::new));
    }

    Map<String, String> acknowledged() {
        try (Database database = open()) {
            return database.apply(unit -> {
                Map<String, String> found = new LinkedHashMap<>();
                for (AlarmRecord row : new AlarmRepo(unit).active("line4")) {
                    found.put(row.getName(), row.getAcknowledgedBy());
                }
                return found;
            });
        }
    }

    static JsonNode json(String text) throws Exception {
        return new ObjectMapper().readTree(text);
    }

    @Test
    void takingEveryAlarm() {
        seed();
        Cli.Result result = asUser("fleet", "ack", "line4");
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(acknowledged()).isEqualTo(Map.of("INK_LOW", "mueller", "DOOR_OPEN", "mueller"));
    }

    @Test
    void takingOneAlarm() {
        seed();
        asUser("fleet", "ack", "line4", "INK_LOW");
        Map<String, String> found = acknowledged();
        assertThat(found.get("INK_LOW")).isEqualTo("mueller");
        assertThat(found.get("DOOR_OPEN")).isEmpty();
    }

    @Test
    void takingAnAlarmThatIsNotUp() {
        seed();
        Cli.Result result = asUser("fleet", "ack", "line4", "HEAD_FAULT");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("nothing to take");
    }

    @Test
    void takingAnAlarmTwice() {
        seed();
        asUser("fleet", "ack", "line4");
        Cli.Result again = asUser("fleet", "ack", "line4");
        assertThat(again.stdout()).contains("nothing to take");
    }

    @Test
    void onlyTheEscalatedOnes() throws Exception {
        seed(true);
        JsonNode body =
                json(asUser("fleet", "ack", "line4", "--escalated", "--json").stdout());
        assertThat(body.findValuesAsText("alarm")).containsExactly("INK_LOW");
        assertThat(body.get(0).get("severity").asText()).isEqualTo("stop");
        assertThat(acknowledged().get("DOOR_OPEN")).isEmpty();
    }

    @Test
    void aFreshWarningHasNotEscalated() {
        seed();
        Cli.Result result = asUser("fleet", "ack", "line4", "--escalated");
        assertThat(result.stdout()).contains("nothing to take");
    }

    @Test
    void aMachineNobodyHasHeardOf() {
        seed();
        Cli.Result result = asUser("fleet", "ack", "line9");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("no machine called line9");
    }

    @Test
    void anAcknowledgementNeedsAName() {
        seed();
        Cli.Result result = invoke(tmp, "fleet", "ack", "line4");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("an acknowledgement needs a name");
    }

    @Test
    void theAuditTrailRecordsWho() {
        seed();
        asUser("fleet", "ack", "line4", "INK_LOW");
        try (Database database = open()) {
            List<String> rows = database.apply(unit -> new AuditLog(unit)
                    .ofAction(AuditAction.ALARM_ACKNOWLEDGED).stream()
                            .map((AuditEntry row) -> row.getActor() + "/" + row.getSubject())
                            .toList());
            assertThat(rows).containsExactly("mueller/INK_LOW");
        }
    }

    @Test
    void theAlarmListShowsWhoTookIt() throws Exception {
        seed();
        asUser("fleet", "ack", "line4", "INK_LOW");
        JsonNode body = json(invoke(tmp, "fleet", "alarms", "--json").stdout());
        JsonNode taken = null;
        for (JsonNode row : body) {
            if (row.get("name").asText().equals("INK_LOW")) {
                taken = row;
            }
        }
        assertThat(taken).isNotNull();
        assertThat(taken.get("ack").asText()).isEqualTo("mueller");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--json", ""})
    void theOutputSaysWhatWasTaken(String flag) {
        seed();
        List<String> args = new ArrayList<>(List.of("fleet", "ack", "line4"));
        if (!flag.isEmpty()) {
            args.add(flag);
        }
        Cli.Result result = asUser(args.toArray(String[]::new));
        assertThat(result.stdout()).contains("INK_LOW");
    }
}
