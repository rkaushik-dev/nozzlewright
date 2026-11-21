package io.github.rkaushikdev.nozzlewright.printer;

import static io.github.rkaushikdev.nozzlewright.printer.Operations.LABEL_CHUNK_BYTES;
import static io.github.rkaushikdev.nozzlewright.printer.Operations.MAX_LABEL_CHUNKS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.CommandRejected;
import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.protocol.Catalogue;
import io.github.rkaushikdev.nozzlewright.protocol.Codec;
import io.github.rkaushikdev.nozzlewright.protocol.Response;
import io.github.rkaushikdev.nozzlewright.session.Credentials;
import io.github.rkaushikdev.nozzlewright.simulator.SimulatedPrinter;
import io.github.rkaushikdev.nozzlewright.testing.Fast;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LabelTransferTest {

    static final String CARTON = "LABEL carton 16\nFIELD brand TEXT HALVEX\nTEXT line1 0,0 brand\nEND\n";

    private SimulatedBench bench;

    @AfterEach
    void stop() throws InterruptedException {
        if (bench != null) {
            bench.stop();
        }
    }

    static SimulatedPrinter.Builder machine() {
        return SimulatedPrinter.builder()
                .config(SimulatedBench.fixedSpeedConfig(1))
                .label("tray", "x");
    }

    SimulatedPrinter bench() throws InterruptedException {
        return start("line4", machine().build());
    }

    SimulatedPrinter start(String printerId, SimulatedPrinter machine) throws InterruptedException {
        bench = SimulatedBench.start(printerId, machine, new Credentials("factory", "9999"), Fast.session(0.05, 1.0));
        return machine;
    }

    @Test
    void theVerbsAreInTheCatalogue() {
        assertThat(Catalogue.lookup("LABELPUT").minRevision()).isEqualTo(2);
        assertThat(Catalogue.lookup("LABELPUT").whilePrinting()).isFalse();
        assertThat(Catalogue.lookup("LABELPUT").args()).hasSize(4);
        assertThat(Catalogue.lookup("LABELDEL").whilePrinting()).isFalse();
    }

    @Test
    void aCommaIsHiddenAndPutBack() {
        assertThat(Codec.escapeArgument("a,b")).isEqualTo("a" + Codec.ESCAPED_COMMA + "b");
        assertThat(Codec.unescapeArgument(Codec.escapeArgument(CARTON))).isEqualTo(CARTON);
    }

    @Test
    void textWithNoCommaIsLeftAlone() {
        assertThat(Codec.escapeArgument("plain")).isEqualTo("plain");
    }

    @Test
    void aSmallLabelGoesInOneChunk() throws Exception {
        SimulatedPrinter machine = bench();
        assertThat(Operations.pushLabel(bench.session, "carton", CARTON)).isEqualTo(1);
        assertThat(machine.labels()).containsEntry("carton", CARTON);
    }

    @Test
    void theCommasSurviveTheTrip() throws Exception {
        SimulatedPrinter machine = bench();
        Operations.pushLabel(bench.session, "carton", CARTON);
        assertThat(machine.labels().get("carton")).contains("TEXT line1 0,0 brand");
    }

    @Test
    void aBigLabelIsSplit() throws Exception {
        SimulatedPrinter machine = bench();
        String big = CARTON + "; padding\n".repeat(400);
        int chunks = Operations.pushLabel(bench.session, "carton", big);
        assertThat(chunks).isGreaterThan(1);
        assertThat(machine.labels()).containsEntry("carton", big);
    }

    @Test
    void theChunkSizeCanBeSet() throws Exception {
        SimulatedPrinter machine = bench();
        assertThat(Operations.pushLabel(bench.session, "carton", CARTON, 16)).isEqualTo(CARTON.length() / 16 + 1);
        assertThat(machine.labels()).containsEntry("carton", CARTON);
    }

    @Test
    void nothingIsCommittedUntilTheLastChunk() throws Exception {
        SimulatedPrinter machine = bench();
        bench.session.command("LABELPUT", "carton", 0, 3, Codec.escapeArgument("LABEL carton 16\n"));
        bench.session.command("LABELPUT", "carton", 1, 3, Codec.escapeArgument("FIELD brand TEXT X\n"));
        assertThat(machine.labels()).doesNotContainKey("carton");
        assertThat(machine.partialLabels().get("carton")).hasSize(2);
        bench.session.command("LABELPUT", "carton", 2, 3, Codec.escapeArgument("END\n"));
        assertThat(machine.labels()).containsKey("carton");
        assertThat(machine.partialLabels()).doesNotContainKey("carton");
    }

    @Test
    void aRestartedTransferDropsWhatWasHeld() throws Exception {
        SimulatedPrinter machine = bench();
        bench.session.command("LABELPUT", "carton", 0, 3, Codec.escapeArgument("first go\n"));
        bench.session.command("LABELPUT", "carton", 1, 3, Codec.escapeArgument("junk\n"));
        Operations.pushLabel(bench.session, "carton", CARTON);
        assertThat(machine.labels()).containsEntry("carton", CARTON);
    }

    @Test
    void aChunkNumberPastTheTotal() throws Exception {
        bench();
        assertThatThrownBy(() -> bench.session.command("LABELPUT", "carton", 3, 3, "x"))
                .isInstanceOf(CommandRejected.class);
    }

    @Test
    void aLabelWithNoName() throws Exception {
        bench();
        assertThatThrownBy(() -> Operations.pushLabel(bench.session, "  ", CARTON))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("a label needs a name");
    }

    @Test
    void aLabelWithNoSource() throws Exception {
        bench();
        assertThatThrownBy(() -> Operations.pushLabel(bench.session, "carton", "   "))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("no source to send");
    }

    @Test
    void aLabelTooBigForTheProtocol() throws Exception {
        bench();
        String huge = "x".repeat(LABEL_CHUNK_BYTES * (MAX_LABEL_CHUNKS + 1));
        assertThatThrownBy(() -> Operations.pushLabel(bench.session, "carton", huge))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("carries " + MAX_LABEL_CHUNKS);
    }

    @Test
    void anOlderFirmwareCannotBeSentLabels() throws Exception {
        start("line9", machine().revision(1).build());
        assertThat(bench.session.dialect()).isPresent();
        assertThat(bench.session.dialect().get().supports("LABELPUT")).isFalse();
        assertThatThrownBy(() -> Operations.pushLabel(bench.session, "carton", CARTON))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("cannot be sent labels");
    }

    @Test
    void aPrintingMachineRefusesATransfer() throws Exception {
        bench().state(MachineState.PRINTING);
        assertThatThrownBy(() -> Operations.pushLabel(bench.session, "carton", CARTON))
                .isInstanceOf(CommandRejected.class);
    }

    @Test
    void deletingALabel() throws Exception {
        SimulatedPrinter machine = bench();
        assertThat(Operations.deleteLabel(bench.session, "tray")).isTrue();
        assertThat(machine.labels()).doesNotContainKey("tray");
    }

    @Test
    void deletingALabelThatIsNotThere() throws Exception {
        bench();
        assertThat(Operations.deleteLabel(bench.session, "ghost")).isFalse();
    }

    @Test
    void theSelectedLabelCannotBeDeleted() throws Exception {
        SimulatedPrinter machine = bench();
        bench.session.command("LABEL", "tray");
        assertThatThrownBy(() -> Operations.deleteLabel(bench.session, "tray")).isInstanceOf(CommandRejected.class);
        assertThat(machine.labels()).containsKey("tray");
    }

    @Test
    void aPushedLabelShowsUpInTheList() throws Exception {
        bench();
        Operations.pushLabel(bench.session, "carton", CARTON);
        Response response = bench.session.command("ENUMLABELS");
        assertThat(response.table().stream().map(row -> row.get("NAME")).sorted())
                .containsExactly("carton", "tray");
    }
}
