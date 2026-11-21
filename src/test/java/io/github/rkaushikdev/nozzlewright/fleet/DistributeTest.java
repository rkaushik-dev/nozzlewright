package io.github.rkaushikdev.nozzlewright.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.cli.Cli;
import io.github.rkaushikdev.nozzlewright.cli.Context;
import io.github.rkaushikdev.nozzlewright.labels.LabelStore;
import io.github.rkaushikdev.nozzlewright.labels.LabelTemplate;
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

class DistributeTest {

    static final String CARTON = "LABEL carton 16\nFIELD brand TEXT HALVEX\nTEXT line1 0,0 brand\nEND\n";
    static final String TRAY = "LABEL tray 16\nFIELD brand TEXT HALVEX\nTEXT line1 0,0 brand\nEND\n";

    static PrinterConfig config() {
        HeadGeometry head = new HeadGeometry(
                "HVX8PEN", 300, Head.NATIVE_VERTICAL_DPI, List.of(new GroupGeometry(1, List.of(new PenGeometry(1)))));
        return PrinterConfig.builder(head, new FiringTable(List.of(new FiringProfile(1, 9000, 1500))))
                .noEncoder()
                .speedSource(SpeedSource.FIXED)
                .autospeedMmPerMin(24_000)
                .printMode(PrintMode.DRIVER)
                .build();
    }

    static Map<String, SimulatedPrinter> twoMachines() {
        Map<String, SimulatedPrinter> machines = new LinkedHashMap<>();
        machines.put("line4", new SimulatedPrinter(config()));
        machines.put("line5", new SimulatedPrinter(config()));
        return machines;
    }

    static List<String> sortedNames(SimulatedPrinter machine) {
        return machine.labels().keySet().stream().sorted().toList();
    }

    /** A store, two machines and a pool in front of them. */
    @Nested
    class AgainstAPool {

        @TempDir
        Path tmp;

        final Map<String, SimulatedPrinter> machines = twoMachines();
        LabelStore store;
        SessionPool pool;

        @BeforeEach
        void setUp() {
            store = new LabelStore(tmp.resolve("labels"));
            store.open();
            store.save(LabelTemplate.parse(CARTON));
            store.save(LabelTemplate.parse(TRAY));
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

        Distribution distribute(List<String> ids) throws InterruptedException {
            return Distribution.distribute(pool, store, ids);
        }

        @Test
        void twoEmptyMachinesGetEverything() throws InterruptedException {
            Distribution result = distribute(List.of("line4", "line5"));
            assertThat(result.ok()).isTrue();
            assertThat(result.sent()).isEqualTo(4);
            assertThat(sortedNames(machines.get("line4"))).containsExactly("carton", "tray");
            assertThat(sortedNames(machines.get("line5"))).containsExactly("carton", "tray");
        }

        @Test
        void aSecondPassSendsNothing() throws InterruptedException {
            distribute(List.of("line4"));
            Distribution again = distribute(List.of("line4"));
            assertThat(again.sent()).isZero();
            assertThat(again.machines().get(0).alreadyHad()).containsExactly("carton", "tray");
        }

        @Test
        void onlyTheNamedLabelGoes() throws InterruptedException {
            Distribution result =
                    Distribution.distribute(pool, store, List.of("line4"), List.of("carton"), false, false);
            assertThat(result.machines().get(0).sent()).containsExactly("carton");
            assertThat(sortedNames(machines.get("line4"))).containsExactly("carton");
        }

        @Test
        void aLabelTheStoreHasNotGot() {
            assertThatThrownBy(() ->
                            Distribution.distribute(pool, store, List.of("line4"), List.of("ghost"), false, false))
                    .isInstanceOf(NozzlewrightError.class)
                    .hasMessageContaining("no label called ghost");
        }

        @Test
        void theLabelArrivesIntact() throws InterruptedException {
            Distribution.distribute(pool, store, List.of("line4"), List.of("carton"), false, false);
            assertThat(machines.get("line4").labels().get("carton")).contains("TEXT line1 0,0 brand");
        }

        @Test
        void aDryRunMovesNothing() throws InterruptedException {
            Distribution result = Distribution.distribute(pool, store, List.of("line4"), List.of(), false, true);
            assertThat(result.dryRun()).isTrue();
            assertThat(result.sent()).isEqualTo(2);
            assertThat(machines.get("line4").labels()).isEmpty();
            assertThat(result.summary()).startsWith("dry run:");
        }

        @Test
        void pruningTakesOffWhatTheStoreHasNotGot() throws InterruptedException {
            machines.get("line4").putLabel("obsolete", "x");
            Distribution result = Distribution.distribute(pool, store, List.of("line4"), List.of(), true, false);
            assertThat(result.removed()).isEqualTo(1);
            assertThat(machines.get("line4").labels()).doesNotContainKey("obsolete");
        }

        @Test
        void withoutPruningTheExtraLabelStays() throws InterruptedException {
            machines.get("line4").putLabel("obsolete", "x");
            Distribution result = distribute(List.of("line4"));
            assertThat(result.removed()).isZero();
            assertThat(machines.get("line4").labels()).containsKey("obsolete");
        }

        @Test
        void theSelectedLabelCannotBePruned() throws InterruptedException {
            machines.get("line4").putLabel("obsolete", "x");
            machines.get("line4").activeLabel("obsolete");
            Distribution result = Distribution.distribute(pool, store, List.of("line4"), List.of(), true, false);
            assertThat(result.ok()).isFalse();
            assertThat(result.machines().get(0).refused().get(0).label()).isEqualTo("obsolete");
            assertThat(machines.get("line4").labels()).containsKey("obsolete");
        }

        @Test
        void aMachineNobodyCanReach() throws InterruptedException {
            Distribution result = distribute(List.of("line4", "line9"));
            assertThat(result.ok()).isFalse();
            assertThat(result.machines().get(1).error()).isNotEmpty();
            assertThat(result.failed()).extracting(MachineSync::printerId).containsExactly("line9");
            assertThat(sortedNames(machines.get("line4"))).containsExactly("carton", "tray");
        }

        @Test
        void theSummaryCountsEverything() throws InterruptedException {
            Distribution result = distribute(List.of("line4", "line5"));
            assertThat(result.summary()).isEqualTo("2 machines, 4 sent, 0 removed, 0 failed");
        }

        @Test
        void aMachineRowFlattens() throws InterruptedException {
            Map<String, Object> body =
                    distribute(List.of("line4")).machines().get(0).asMap();
            assertThat(body.get("printer_id")).isEqualTo("line4");
            assertThat(body.get("sent")).isEqualTo(2);
            assertThat(body.get("ok")).isEqualTo(true);
        }

        @Test
        void theMachineSummaryReads() throws InterruptedException {
            MachineSync machine = distribute(List.of("line4")).machines().get(0);
            assertThat(machine.summary()).isEqualTo("line4 2 sent, 0 removed, 0 already there, 0 refused");
            assertThat(machine.changed()).isEqualTo(2);
        }
    }

    /** The push command as a technician meets it, on the console. */
    @Nested
    class OnTheConsole {

        @TempDir
        Path tmp;

        final Map<String, SimulatedPrinter> machines = twoMachines();

        @BeforeEach
        void setUp() {
            Registry registry = new Registry("bench");
            registry.add(new PrinterRecord("line4", "sim4").withKind("loopback").withLine("4"));
            registry.add(new PrinterRecord("line5", "sim5").withKind("loopback").withLine("5"));
            registry.save(tmp.resolve(Context.REGISTRY_NAME));

            LabelStore store = new LabelStore(tmp.resolve("labels"));
            store.open();
            store.save(LabelTemplate.parse(CARTON));
        }

        Cli.Result invoke(String... args) {
            List<String> full = new ArrayList<>(List.of("--user", "factory", "--pin", "9999"));
            full.addAll(List.of(args));
            return Cli.clean().machines(machines).in(tmp, full.toArray(String[]::new));
        }

        /** The JSON part of a push result, which also prints a summary line. */
        List<String> pushedTo(Cli.Result result) throws Exception {
            String text = result.stdout();
            JsonNode body = new ObjectMapper().readTree(text.substring(0, text.lastIndexOf(']') + 1));
            return body.findValuesAsText("printer_id");
        }

        @Test
        void pushingFromTheConsole() {
            Cli.Result result = invoke("fleet", "push");
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(result.stdout()).contains("2 machines, 2 sent");
            for (SimulatedPrinter machine : machines.values()) {
                assertThat(machine.labels()).containsKey("carton");
            }
        }

        @Test
        void pushingToOneLine() throws Exception {
            assertThat(pushedTo(invoke("fleet", "push", "--line", "4", "--json")))
                    .containsExactly("line4");
        }

        @Test
        void pushingToOneMachine() throws Exception {
            assertThat(pushedTo(invoke("fleet", "push", "--printer", "line5", "--json")))
                    .containsExactly("line5");
        }

        @Test
        void pushingToNothing() {
            Cli.Result result = invoke("fleet", "push", "--line", "9");
            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(result.stderr()).contains("matches no machine");
        }

        @Test
        void aDryRunFromTheConsole() {
            Cli.Result result = invoke("fleet", "push", "--dry-run");
            assertThat(result.stdout()).contains("dry run:");
            for (SimulatedPrinter machine : machines.values()) {
                assertThat(machine.labels()).isEmpty();
            }
        }
    }
}
