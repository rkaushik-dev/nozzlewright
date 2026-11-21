package io.github.rkaushikdev.nozzlewright.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.cli.Cli;
import io.github.rkaushikdev.nozzlewright.cli.Context;
import io.github.rkaushikdev.nozzlewright.printer.FiringProfile;
import io.github.rkaushikdev.nozzlewright.printer.FiringTable;
import io.github.rkaushikdev.nozzlewright.printer.GroupGeometry;
import io.github.rkaushikdev.nozzlewright.printer.Head;
import io.github.rkaushikdev.nozzlewright.printer.HeadGeometry;
import io.github.rkaushikdev.nozzlewright.printer.PenGeometry;
import io.github.rkaushikdev.nozzlewright.printer.PrintMode;
import io.github.rkaushikdev.nozzlewright.printer.PrinterConfig;
import io.github.rkaushikdev.nozzlewright.printer.SpeedSource;
import io.github.rkaushikdev.nozzlewright.session.Credentials;
import io.github.rkaushikdev.nozzlewright.session.PrinterSession;
import io.github.rkaushikdev.nozzlewright.session.SessionPool;
import io.github.rkaushikdev.nozzlewright.simulator.SimulatedPrinter;
import io.github.rkaushikdev.nozzlewright.testing.Fast;
import io.github.rkaushikdev.nozzlewright.transport.LoopbackTransport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DriftTest {

    static PrinterConfig config() {
        return config(300);
    }

    static PrinterConfig config(int dpi) {
        HeadGeometry head = new HeadGeometry(
                "HVX8PEN", dpi, Head.NATIVE_VERTICAL_DPI, List.of(new GroupGeometry(1, List.of(new PenGeometry(1)))));
        return PrinterConfig.builder(head, new FiringTable(List.of(new FiringProfile(1, 9000, 1500))))
                .noEncoder()
                .speedSource(SpeedSource.FIXED)
                .autospeedMmPerMin(24_000)
                .printMode(PrintMode.DRIVER)
                .build();
    }

    /** Two machines, a registry and a profile store in front of them. */
    @Nested
    class AgainstAPool {

        @TempDir
        Path tmp;

        final Map<String, SimulatedPrinter> machines = new LinkedHashMap<>();
        final Registry registry = new Registry("bench");
        ProfileStore store;
        SessionPool pool;

        @BeforeEach
        void setUp() {
            machines.put("line4", new SimulatedPrinter(config()));
            machines.put("line5", new SimulatedPrinter(config()));
            for (String printerId : machines.keySet()) {
                registry.add(
                        new PrinterRecord(printerId, "sim").withKind("loopback").withTags(List.of("carton")));
            }
            store = new ProfileStore(tmp.resolve("profiles"));
            pool = new SessionPool(this::build, 0.0, 3.0);
        }

        @AfterEach
        void tearDown() throws InterruptedException {
            pool.close();
        }

        PrinterSession build(String printerId) {
            SimulatedPrinter machine = machines.get(printerId);
            if (machine == null) {
                throw new ConfigError("printer_id", printerId, "no such machine on this site");
            }
            return PrinterSession.builder(printerId, () -> new LoopbackTransport(machine, "sim"))
                    .credentials(new Credentials("factory", "9999"))
                    .linkSettings(Fast.LINK)
                    .sessionSettings(Fast.session(0.05, 1.0))
                    .build();
        }

        void saveProfile() {
            saveProfile(List.of());
        }

        void saveProfile(List<ProfileOverride> overrides) {
            Profile built = Profile.fromConfig("carton-coder", config()).withTags(List.of("carton"));
            store.save(new Profile(built.name(), built.family(), built.payload())
                    .withTags(List.of("carton"))
                    .withOverrides(overrides));
        }

        static ProfileOverride shortCarton() {
            return new ProfileOverride("line4", "HORZRES", List.of("600"), "short carton");
        }

        DriftReport find() throws InterruptedException {
            return DriftReport.find(pool, store, registry);
        }

        @Test
        void aSiteThatMatches() throws InterruptedException {
            saveProfile();
            DriftReport report = find();
            assertThat(report.ok()).isTrue();
            assertThat(report.drifted()).isEmpty();
            assertThat(report.summary()).isEqualTo("2 machines, 0 drifted, 0 unreadable, 0 not covered");
        }

        @Test
        void aMachineSomebodyNudged() throws InterruptedException {
            saveProfile();
            machines.get("line4").config(config(600));
            DriftReport report = find();
            assertThat(report.ok()).isFalse();
            assertThat(report.drifted()).extracting(MachineDrift::printerId).containsExactly("line4");
            assertThat(report.drifted().get(0).changes()).isNotEmpty();
        }

        @Test
        void theDriftNamesBothValues() throws InterruptedException {
            saveProfile();
            machines.get("line4").config(config(600));
            Map<String, Object> row = find().asRows().get(0);
            assertThat(row.get("printer_id")).isEqualTo("line4");
            assertThat(row.get("on_machine")).isEqualTo("600");
            assertThat(row.get("in_profile")).isEqualTo("300");
        }

        @Test
        void aMachineThatIsDeliberatelyDifferent() throws InterruptedException {
            saveProfile(List.of(shortCarton()));
            machines.get("line4").config(config(600));
            DriftReport report = find();
            assertThat(report.ok()).isTrue();
            assertThat(report.machines().get(0).expected()).isNotEmpty();
        }

        @Test
        void anOverrideOnlyExcusesTheMachineItNames() throws InterruptedException {
            saveProfile(List.of(shortCarton()));
            machines.get("line5").config(config(600));
            assertThat(find().drifted()).extracting(MachineDrift::printerId).containsExactly("line5");
        }

        @Test
        void aMachineNoProfileCovers() throws InterruptedException {
            DriftReport report = find();
            assertThat(report.unclaimed()).hasSize(2);
            assertThat(report.ok()).isTrue();
            assertThat(report.machines().get(0).summary()).contains("not covered by any profile");
        }

        @Test
        void aMachineNobodyCanReach() throws InterruptedException {
            saveProfile();
            DriftReport report = DriftReport.find(pool, store, registry, List.of("line9"));
            assertThat(report.unreadable()).hasSize(1);
            assertThat(report.ok()).isFalse();
            assertThat(report.machines().get(0).summary()).contains("not on this site");
        }

        @Test
        void oneMachineAtATime() throws InterruptedException {
            saveProfile();
            DriftReport report = DriftReport.find(pool, store, registry, List.of("line4"));
            assertThat(report.machines()).extracting(MachineDrift::printerId).containsExactly("line4");
        }

        @Test
        void theWorstMachineComesFirst() throws InterruptedException {
            saveProfile();
            machines.get("line4").config(config(600));
            machines.get("line5").config(config(600));
            assertThat(find().drifted()).hasSize(2);
        }

        @Test
        void aMatchingMachineSaysSo() throws InterruptedException {
            saveProfile();
            DriftReport report = DriftReport.find(pool, store, registry, List.of("line4"));
            assertThat(report.machines().get(0).summary()).isEqualTo("line4 matches carton-coder");
        }
    }

    /** The drift check as a technician meets it, on the console. */
    @Nested
    class OnTheConsole {

        @TempDir
        Path tmp;

        final SimulatedPrinter line4 = new SimulatedPrinter(config());

        @BeforeEach
        void setUp() {
            Registry registry = new Registry("bench");
            registry.add(new PrinterRecord("line4", "sim4").withKind("loopback").withTags(List.of("carton")));
            registry.save(tmp.resolve(Context.REGISTRY_NAME));
            new ProfileStore(tmp.resolve("profiles"))
                    .save(Profile.fromConfig("carton-coder", config()).withTags(List.of("carton")));
        }

        Cli.Result invoke(String... args) {
            List<String> full = new ArrayList<>(List.of("--user", "factory", "--pin", "9999"));
            full.addAll(List.of(args));
            return Cli.clean().machines(Map.of("line4", line4)).in(tmp, full.toArray(String[]::new));
        }

        @Test
        void noDriftOnTheConsole() {
            Cli.Result result = invoke("profile", "drift");
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(result.stdout()).contains("0 drifted");
        }

        @Test
        void driftOnTheConsole() {
            line4.config(config(600));
            Cli.Result result = invoke("profile", "drift");
            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(result.stdout()).contains("1 drifted");
        }

        @Test
        void theDriftAsJson() throws Exception {
            line4.config(config(600));
            String text = invoke("profile", "drift", "--json").stdout();
            JsonNode body = new ObjectMapper().readTree(text.substring(0, text.lastIndexOf(']') + 1));
            assertThat(body.get(0).get("printer_id").asText()).isEqualTo("line4");
            assertThat(body.get(0).get("in_profile").asText()).isEqualTo("300");
        }
    }
}
