package io.github.rkaushikdev.nozzlewright.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.cli.Cli;
import io.github.rkaushikdev.nozzlewright.cli.Context;
import io.github.rkaushikdev.nozzlewright.labels.LabelStore;
import io.github.rkaushikdev.nozzlewright.labels.LabelTemplate;
import io.github.rkaushikdev.nozzlewright.printer.CartridgeSpec;
import io.github.rkaushikdev.nozzlewright.printer.InkFamily;
import io.github.rkaushikdev.nozzlewright.printer.JobOutcome;
import io.github.rkaushikdev.nozzlewright.printer.JobResult;
import io.github.rkaushikdev.nozzlewright.siteconfig.SiteConfig;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.storage.JobRepo;
import io.github.rkaushikdev.nozzlewright.storage.Printer;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import io.github.rkaushikdev.nozzlewright.storage.RunDetails;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpendTest {

    static final Instant START = Instant.parse("2025-06-29T06:00:00Z");

    static final CartridgeSpec SPEC = new CartridgeSpec("SLX-2580", InkFamily.MEK).withNominalMl(42.0);

    static final String CARTON = "LABEL carton 32\nFIELD brand TEXT HALVEX\nTEXT line1 0,0 brand\nEND\n";
    static final String TRAY = "LABEL tray 32\n"
            + "FIELD brand TEXT HALVEX\n"
            + "FIELD code TEXT 05012345678900\n"
            + "TEXT line1 0,0 brand\n"
            + "BARCODE gtin 0,10 code height=20 human=0\n"
            + "END\n";

    static final String PRICED =
            """

            [site]
            id = "bremen-2"

            [costing]
            cartridge_price = 84.0
            part_number = "SLX-2580"
            drop_volume_pl = 42.0
            """;

    static CostModel model() {
        return new CostModel(84.0, SPEC).withDropVolumePl(42.0);
    }

    static Map<String, Long> counts(Object... pairs) {
        Map<String, Long> body = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            body.put((String) pairs[index], ((Number) pairs[index + 1]).longValue());
        }
        return body;
    }

    @Test
    void nothingRan() {
        Spend result = Costing.spendOver(Map.of(), Map.of(), model());
        assertThat(result.codes()).isZero();
        assertThat(result.cost()).isZero();
        assertThat(result.costPerThousand()).isZero();
    }

    @Test
    void oneMessage() {
        Spend result = Costing.spendOver(counts("carton", 10000), counts("carton", 320), model());
        assertThat(result.codes()).isEqualTo(10000);
        assertThat(result.labels().get(0).drops()).isEqualTo(3_200_000);
        assertThat(result.cost()).isCloseTo(3_200_000 * model().pricePerDrop(), within(1e-9));
    }

    @Test
    void theCostPerThousand() {
        Spend result = Costing.spendOver(counts("carton", 10000), counts("carton", 320), model());
        assertThat(result.costPerThousand()).isCloseTo(result.cost() / 10, within(1e-9));
    }

    @Test
    void twoMessagesAreAddedUp() {
        Spend result =
                Costing.spendOver(counts("carton", 1000, "tray", 1000), counts("carton", 320, "tray", 640), model());
        assertThat(result.codes()).isEqualTo(2000);
        assertThat(result.labels()).hasSize(2);
    }

    @Test
    void theDearestMessageComesFirst() {
        Spend result =
                Costing.spendOver(counts("carton", 1000, "tray", 1000), counts("carton", 320, "tray", 640), model());
        assertThat(result.dearest()).extracting(LabelSpend::label).containsExactly("tray", "carton");
    }

    @Test
    void aMessageNobodyCanPrice() {
        Spend result = Costing.spendOver(counts("carton", 1000, "gone", 500), counts("carton", 320), model());
        assertThat(result.unpriced()).containsExactly("gone");
        assertThat(result.codes()).isEqualTo(1000);
        assertThat(result.summary()).contains("Not counted: gone");
    }

    @Test
    void aMessageThatPrintedNothingIsLeftOut() {
        Spend result = Costing.spendOver(counts("carton", 0), counts("carton", 320), model());
        assertThat(result.labels()).isEmpty();
        assertThat(result.unpriced()).isEmpty();
    }

    @Test
    void aMessageThatRendersToNothingIsUnpriced() {
        Spend result = Costing.spendOver(counts("carton", 100), counts("carton", 0), model());
        assertThat(result.unpriced()).containsExactly("carton");
    }

    @Test
    void theSummaryReads() {
        Spend result = Costing.spendOver(counts("carton", 10000), counts("carton", 320), model());
        assertThat(result.summary()).startsWith("10000 codes,");
    }

    @Test
    void aRowFlattens() {
        Map<String, Object> body = Costing.spendOver(counts("carton", 10), counts("carton", 320), model())
                .labels()
                .get(0)
                .asMap();
        assertThat(body.get("label")).isEqualTo("carton");
        assertThat(((Number) body.get("codes")).longValue()).isEqualTo(10);
        assertThat(((Number) body.get("drops_per_code")).longValue()).isEqualTo(320);
    }

    /** The cost command over a database of runs. */
    @Nested
    class OnTheConsole {

        @TempDir
        Path tmp;

        void seed() throws IOException {
            try (Database database = Database.file(tmp.resolve(Context.DATABASE_NAME))) {
                database.createAll();
                database.run(unit -> {
                    Printer printer =
                            new PrinterRepo(unit).upsert(new PrinterRecord("line4", "10.4.0.21:9100").withLine("4"));
                    JobRepo jobs = new JobRepo(unit);
                    List<String> names = List.of("carton", "tray");
                    List<Integer> printed = List.of(4000, 1000);
                    for (int index = 0; index < names.size(); index++) {
                        jobs.record(
                                printer,
                                new JobResult(
                                        names.get(index),
                                        JobOutcome.COMPLETE,
                                        printed.get(index),
                                        printed.get(index),
                                        0,
                                        600.0),
                                RunDetails.none().withStartedAt(START.plus(Duration.ofHours(index))));
                    }
                });
            }
            LabelStore store = new LabelStore(tmp.resolve("labels"));
            store.open();
            store.save(LabelTemplate.parse(CARTON));
            store.save(LabelTemplate.parse(TRAY));
            Files.writeString(tmp.resolve(SiteConfig.SITE_FILE), PRICED, StandardCharsets.UTF_8);
        }

        Cli.Result window(String... args) {
            List<String> full = new ArrayList<>(List.of(
                    "fleet", "cost", "--since", "2025-06-29T00:00:00+00:00", "--until", "2025-06-30T00:00:00+00:00"));
            full.addAll(List.of(args));
            return Cli.invoke(tmp, full.toArray(String[]::new));
        }

        JsonNode rows(Cli.Result result) throws Exception {
            String text = result.stdout();
            return new ObjectMapper().readTree(text.substring(0, text.lastIndexOf(']') + 1));
        }

        @Test
        void theCostOfAShift() throws Exception {
            seed();
            Cli.Result result = window();
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(result.stdout()).contains("5000 codes");
        }

        @Test
        void theBreakdownAsJson() throws Exception {
            seed();
            JsonNode body = rows(window("--json"));
            assertThat(body.findValuesAsText("label")).containsExactlyInAnyOrder("carton", "tray");
            for (JsonNode row : body) {
                assertThat(row.get("cost").asDouble()).isPositive();
            }
        }

        @Test
        void theBarcodeLabelCostsMorePerCode() throws Exception {
            seed();
            Map<String, JsonNode> body = new HashMap<>();
            for (JsonNode row : rows(window("--json"))) {
                body.put(row.get("label").asText(), row);
            }
            assertThat(body.get("tray").get("drops_per_code").asLong())
                    .isGreaterThan(body.get("carton").get("drops_per_code").asLong());
        }

        @Test
        void aLabelThatHasBeenDeletedSince() throws Exception {
            seed();
            try (Stream<Path> files = Files.list(tmp.resolve("labels"))) {
                for (Path path : files.filter(
                                path -> path.getFileName().toString().startsWith("tray"))
                        .toList()) {
                    Files.delete(path);
                }
            }
            new LabelStore(tmp.resolve("labels")).rebuild();
            assertThat(window().stdout()).contains("Not counted: tray");
        }

        @Test
        void aWindowWithNothingInIt() throws Exception {
            seed();
            Cli.Result result = Cli.invoke(
                    tmp,
                    "fleet",
                    "cost",
                    "--since",
                    "2024-01-01T00:00:00+00:00",
                    "--until",
                    "2024-01-02T00:00:00+00:00");
            assertThat(result.stdout()).contains("nothing ran in that window");
        }

        @Test
        void aSiteWithNoPrices() throws Exception {
            seed();
            Files.writeString(tmp.resolve(SiteConfig.SITE_FILE), "[site]\nid = \"bremen-2\"\n", StandardCharsets.UTF_8);
            Cli.Result result = window();
            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(result.stderr()).contains("no cartridge price");
        }
    }
}
