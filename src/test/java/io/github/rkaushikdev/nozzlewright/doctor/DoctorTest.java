package io.github.rkaushikdev.nozzlewright.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.api.KeyRing;
import io.github.rkaushikdev.nozzlewright.cli.Cli;
import io.github.rkaushikdev.nozzlewright.labels.LabelStore;
import io.github.rkaushikdev.nozzlewright.labels.LabelTemplate;
import io.github.rkaushikdev.nozzlewright.siteconfig.SiteConfig;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.storage.Schema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DoctorTest {

    static final Instant NOW = Instant.parse("2025-06-26T09:00:00Z");

    static final String CARTON = "LABEL carton 16\nFIELD brand TEXT HALVEX\nTEXT line1 0,0 brand\nEND\n";

    static final String SITE =
            """

            [site]
            id = "bremen-2"

            [api]
            keys = ["sha256:%s"]

            [[printers]]
            id = "line4"
            endpoint = "10.4.0.21:9100"
            """
                    .formatted("0".repeat(64));

    @TempDir
    Path tmp;

    /** The site database in a state directory, as the console and the gateway name it. */
    static String urlFor(Path root) {
        return Database.fileUrl(root.resolve("site"));
    }

    static void migrate(Path root) {
        try (Database database = Database.open(urlFor(root))) {
            Schema.upgrade(database);
        }
    }

    static LabelStore addLabels(Path root, String source) {
        LabelStore store = new LabelStore(root.resolve("labels"));
        store.open();
        store.save(LabelTemplate.parse(source));
        return store;
    }

    static LabelStore addLabels(Path root) {
        return addLabels(root, CARTON);
    }

    static void writeSite(Path root, String text) throws Exception {
        Files.writeString(root.resolve(SiteConfig.SITE_FILE), text, StandardCharsets.UTF_8);
    }

    static Check check(Report report, String name) {
        return report.checks().stream()
                .filter(item -> item.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    Report report() {
        return Doctor.runChecks(tmp, urlFor(tmp), NOW);
    }

    @Test
    void aFreshBoxIsHealthyWithWarnings() {
        migrate(tmp);
        Report report = report();
        assertThat(report.healthy()).isTrue();
        assertThat(report.ok()).isFalse();
        assertThat(report.grade()).isEqualTo(Grade.WARN);
    }

    @Test
    void aCommissionedBoxIsClean() throws Exception {
        migrate(tmp);
        addLabels(tmp);
        writeSite(tmp, SITE);
        Report report = report();
        assertThat(report.ok()).as(report.render()).isTrue();
        assertThat(report.failures()).isEmpty();
    }

    @Test
    void aStateDirectoryThatIsNotThere() {
        Path missing = tmp.resolve("nowhere");
        Report report = Doctor.runChecks(missing, urlFor(tmp), NOW);
        assertThat(check(report, "state directory").grade()).isEqualTo(Grade.FAIL);
        assertThat(report.healthy()).isFalse();
    }

    @Test
    void aDatabaseWithNoSchema() {
        Check found = check(report(), "database");
        assertThat(found.grade()).isEqualTo(Grade.FAIL);
        assertThat(found.detail()).contains("run db upgrade");
    }

    @Test
    void aDatabaseThatIsUpToDate() {
        migrate(tmp);
        assertThat(check(report(), "database").ok()).isTrue();
    }

    @Test
    void noSiteFileIsAWarning() {
        migrate(tmp);
        Check found = check(report(), "site file");
        assertThat(found.grade()).isEqualTo(Grade.WARN);
        assertThat(found.detail()).contains("running on defaults");
    }

    @Test
    void aSiteFileWithNoMachines() throws Exception {
        migrate(tmp);
        writeSite(tmp, "[site]\nid = \"bremen-2\"\n");
        Check found = check(report(), "site file");
        assertThat(found.grade()).isEqualTo(Grade.WARN);
        assertThat(found.detail()).contains("the registry is being used");
    }

    @Test
    void aSiteFileThatWillNotParse() throws Exception {
        migrate(tmp);
        writeSite(tmp, "[widgets]\ncount = 3\n");
        Check found = check(report(), "site file");
        assertThat(found.grade()).isEqualTo(Grade.FAIL);
        assertThat(found.detail()).contains("the check itself failed");
    }

    @Test
    void anEmptyLabelStoreIsAWarning() {
        migrate(tmp);
        assertThat(check(report(), "label store").grade()).isEqualTo(Grade.WARN);
    }

    @Test
    void aLabelStoreThatParses() {
        migrate(tmp);
        addLabels(tmp);
        Check found = check(report(), "label store");
        assertThat(found.ok()).isTrue();
        assertThat(found.detail()).contains("1 labels");
    }

    @Test
    void aLabelThatStoppedParsing() throws Exception {
        migrate(tmp);
        addLabels(tmp);
        Path broken;
        try (Stream<Path> files = Files.list(tmp.resolve("labels"))) {
            broken = files.filter(path -> path.getFileName().toString().startsWith("carton"))
                    .findFirst()
                    .orElseThrow();
        }
        Files.writeString(broken, "this is not a label\n", StandardCharsets.UTF_8);
        Check found = check(report(), "label store");
        assertThat(found.grade()).isEqualTo(Grade.FAIL);
        assertThat(found.detail()).contains("carton");
    }

    @Test
    void aBoxWithNoTimeSource() {
        migrate(tmp);
        Check found = check(Doctor.runChecks(tmp, urlFor(tmp), Instant.parse("2000-01-01T00:00:00Z")), "clock");
        assertThat(found.grade()).isEqualTo(Grade.FAIL);
        assertThat(found.detail()).contains("no time source");
    }

    @Test
    void theDiskCheckReportsFreeSpace() {
        migrate(tmp);
        assertThat(check(report(), "disk space").detail()).contains("MB free");
    }

    @Test
    void everyCheckIsNamedOnce() {
        migrate(tmp);
        List<String> names = report().checks().stream().map(Check::name).toList();
        assertThat(names).doesNotHaveDuplicates();
        assertThat(names).hasSize(7);
    }

    @Test
    void theReportPutsTheWorstFirst() {
        List<String> lines = report().render().lines().toList();
        assertThat(lines.get(0)).endsWith("fail");
        assertThat(lines.get(1)).startsWith("fail");
    }

    @Test
    void aCheckFlattens() {
        migrate(tmp);
        Map<String, Object> body = check(report(), "database").asMap();
        assertThat(body).containsEntry("name", "database");
        assertThat(body).containsEntry("ok", true);
    }

    @Test
    void theDoctorOnTheConsole() throws Exception {
        migrate(tmp);
        addLabels(tmp);
        writeSite(tmp, SITE);
        Cli.Result result = Cli.invoke(tmp, "doctor");
        assertThat(result.exitCode()).as(result.stdout()).isZero();
        assertThat(result.stdout()).contains("ok    database");
    }

    @Test
    void theDoctorFailsOnABrokenBox() {
        Cli.Result result = Cli.invoke(tmp, "doctor");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).contains("run db upgrade");
    }

    @Test
    void aWarningAloneDoesNotFail() {
        migrate(tmp);
        assertThat(Cli.invoke(tmp, "doctor").exitCode()).isZero();
    }

    @Test
    void theDoctorAsJson() throws Exception {
        migrate(tmp);
        JsonNode body =
                new ObjectMapper().readTree(Cli.invoke(tmp, "doctor", "--json").stdout());
        Set<String> names = new HashSet<>();
        body.forEach(row -> names.add(row.get("name").asText()));
        assertThat(names).contains("database", "label store", "clock");
    }

    @ParameterizedTest
    @EnumSource(Grade.class)
    void theGradesAreOrdered(Grade grade) {
        assertThat(grade.rank()).isBetween(0, 2);
    }

    @Test
    void noKeysIsAWarning() {
        migrate(tmp);
        Check found = check(report(), "api keys");
        assertThat(found.grade()).isEqualTo(Grade.WARN);
        assertThat(found.detail()).contains("answers anybody");
    }

    @Test
    void aPlainKeyIsAWarning() throws Exception {
        migrate(tmp);
        writeSite(tmp, "[api]\nkeys = [\"alpha\"]\n");
        Check found = check(report(), "api keys");
        assertThat(found.grade()).isEqualTo(Grade.WARN);
        assertThat(found.detail()).contains("in the clear");
    }

    @Test
    void hashedKeysPass() throws Exception {
        migrate(tmp);
        writeSite(tmp, "[api]\nkeys = [\"" + KeyRing.hashKey("alpha") + "\"]\n");
        Check found = check(report(), "api keys");
        assertThat(found.ok()).isTrue();
        assertThat(found.detail()).contains("all hashed");
    }
}
