package io.github.rkaushikdev.nozzlewright.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.internal.jdbc.DriverDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;

class SchemaTest {

    static final Set<String> TABLES =
            Set.of("printers", "poll_samples", "alarm_records", "label_revisions", "config_snapshots", "job_runs");

    // The head is whatever ships; what matters is that upgrading gets there and stays there.
    static final Optional<String> HEAD = Schema.headRevision();

    @TempDir
    Path tmp;

    private Database database;

    Database fileDatabase() {
        database = Database.file(tmp.resolve("site"));
        return database;
    }

    @AfterEach
    void close() {
        if (database != null) {
            database.close();
        }
    }

    static Stream<String> tables() {
        return new TreeSet<>(TABLES).stream();
    }

    @Test
    void theMigrationsShipWithThePackage() {
        assertThat(new ClassPathResource(Schema.migrationsPath()).exists()).isTrue();
        assertThat(Schema.revisions()).isNotEmpty();
    }

    @Test
    void theConfigPointsAtOurScripts() {
        FluentConfiguration config = Schema.buildConfig("jdbc:h2:mem:pointing");
        assertThat(config.getLocations()).extracting(Object::toString).containsExactly(Schema.LOCATION);
        assertThat(((DriverDataSource) config.getDataSource()).getUrl()).startsWith("jdbc:h2:mem:pointing");
    }

    @Test
    void thereIsAHeadAndItIsTheNewestScript() {
        List<Revision> revisions = Schema.revisions();
        assertThat(HEAD).contains(revisions.get(revisions.size() - 1).id());
        assertThat(HEAD.orElseThrow()).startsWith(String.format("%04d_", revisions.size()));
    }

    @Test
    void aFreshDatabaseHasNoRevision() {
        assertThat(Schema.currentRevision(fileDatabase())).isEmpty();
    }

    @Test
    void upgradeCreatesEveryTable() {
        Database made = fileDatabase();
        assertThat(Schema.upgrade(made)).isEqualTo(HEAD);
        assertThat(Catalog.tables(made)).containsAll(TABLES);
    }

    @Test
    void upgradeIsIdempotent() {
        Database made = fileDatabase();
        Schema.upgrade(made);
        assertThat(Schema.upgrade(made)).isEqualTo(HEAD);
    }

    @Test
    void theIndexesComeWithTheTables() {
        Database made = fileDatabase();
        Schema.upgrade(made);
        assertThat(Catalog.indexes(made, "poll_samples")).contains("ix_poll_samples_printer_at");
    }

    @Test
    void theUniqueConstraintOnLabelRevisions() {
        Database made = fileDatabase();
        Schema.upgrade(made);
        assertThat(Catalog.uniqueConstraints(made, "label_revisions")).contains("uq_label_revision");
    }

    @Test
    void stampingRecordsARevisionWithoutRunningIt() {
        Database made = fileDatabase();
        Schema.stamp(made);
        assertThat(Schema.currentRevision(made)).isEqualTo(HEAD);
        assertThat(Catalog.tables(made)).doesNotContain("printers");
    }

    @Test
    void aStampedDatabaseIsLeftAlone() {
        Database made = fileDatabase();
        Schema.stamp(made);
        assertThat(Schema.upgrade(made)).isEqualTo(HEAD);
    }

    @Test
    void theRowsSurviveAnUpgradeRunTwice() {
        Database made = fileDatabase();
        Schema.upgrade(made);
        Catalog.execute(
                made,
                "INSERT INTO printers (printer_id, endpoint, created_at)"
                        + " VALUES ('line4', '10.0.0.1:9100', TIMESTAMP WITH TIME ZONE '2025-03-20 08:00:00+00:00')");
        Schema.upgrade(made);
        assertThat(Catalog.count(made, "SELECT COUNT(*) FROM printers")).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("tables")
    void everyTableHasAPrimaryKey(String table) {
        Database made = fileDatabase();
        Schema.upgrade(made);
        assertThat(Catalog.primaryKey(made, table)).containsExactly("id");
    }
}
