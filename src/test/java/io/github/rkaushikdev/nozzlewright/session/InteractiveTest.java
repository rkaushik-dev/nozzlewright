package io.github.rkaushikdev.nozzlewright.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.CommandRejected;
import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.labels.LabelTemplate;
import io.github.rkaushikdev.nozzlewright.simulator.SimulatedPrinter;
import io.github.rkaushikdev.nozzlewright.testing.Fast;
import io.github.rkaushikdev.nozzlewright.transport.LoopbackTransport;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InteractiveTest {

    static final String ASKED = "LABEL asked 8\n"
            + "FIELD lot PROMPT max=8\n"
            + "FIELD shift PROMPT max=1 required=0 default=A\n"
            + "TEXT l 0,0 lot\n"
            + "TEXT s 40,0 shift\n"
            + "END\n";

    static final String PLAIN = "LABEL plain 8\nFIELD a TEXT HELLO\nTEXT t 0,0 a\nEND\n";

    /** A simulated machine waiting on these fields, with a live session in front of it. */
    static final class Bench {
        final SimulatedPrinter machine;
        final PrinterSession session;

        Bench(String... fields) {
            machine = SimulatedPrinter.builder()
                    .label("asked", "x")
                    .pendingFields(fields)
                    .build();
            session = PrinterSession.builder("line4", () -> new LoopbackTransport(machine, "sim"))
                    .credentials(new Credentials("mueller", "4417"))
                    .linkSettings(Fast.LINK)
                    .sessionSettings(Fast.SESSION)
                    .build();
        }
    }

    private Bench bench;

    @AfterEach
    void stop() throws InterruptedException {
        if (bench != null) {
            bench.session.stop();
        }
    }

    Bench started(String... fields) throws InterruptedException {
        bench = new Bench(fields);
        bench.session.start();
        assertThat(bench.session.waitReady(3.0)).isTrue();
        return bench;
    }

    Bench bench() throws InterruptedException {
        return started("lot", "shift");
    }

    static LabelTemplate asked() {
        return LabelTemplate.parse(ASKED);
    }

    @Test
    void aQuestionChecksItsAnswer() {
        Question question = new Question("lot", "", 8, "", true);
        question.check("L4417");
        assertThatThrownBy(() -> question.check("   "))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("needs an answer");
        assertThatThrownBy(() -> question.check("TOOLONGAVALUE"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("takes 8 characters");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a,b", "a;b", "a:b"})
    void aQuestionRefusesWireSeparators(String bad) {
        assertThatThrownBy(() -> new Question("lot").check(bad))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("must not contain");
    }

    @Test
    void anOptionalQuestionTakesNothing() {
        new Question("shift", "", 32, "", false).check("");
    }

    @Test
    void reconcilingWithTheLabel() {
        AnswerSheet sheet = Interactive.reconcile(List.of("lot", "shift"), asked());
        assertThat(sheet.complete()).isTrue();
        assertThat(sheet.questions().stream().map(Question::name)).containsExactly("lot", "shift");
        assertThat(sheet.question("lot").maxLen()).isEqualTo(8);
        assertThat(sheet.question("shift").defaultValue()).isEqualTo("A");
        assertThat(sheet.question("shift").required()).isFalse();
    }

    @Test
    void aFieldTheLabelDoesNotHave() {
        AnswerSheet sheet = Interactive.reconcile(List.of("lot", "ghost"), asked());
        assertThat(sheet.complete()).isFalse();
        assertThat(sheet.unknown()).containsExactly("ghost");
        assertThat(sheet.question("ghost").maxLen()).isEqualTo(32);
    }

    @Test
    void aFieldTheMachineIsNotAskingFor() {
        AnswerSheet sheet = Interactive.reconcile(List.of("lot"), asked());
        assertThat(sheet.unused()).containsExactly("shift");
        assertThat(sheet.summary()).contains("1 the machine is not asking for");
    }

    @Test
    void reconcilingWithoutALabel() {
        AnswerSheet sheet = Interactive.reconcile(List.of("lot"), Optional.empty());
        assertThat(sheet.unknown()).containsExactly("lot");
        assertThat(sheet.question("lot").name()).isEqualTo("lot");
    }

    @Test
    void aLabelWithNoPrompts() {
        AnswerSheet sheet = Interactive.reconcile(List.of(), LabelTemplate.parse(PLAIN));
        assertThat(sheet.complete()).isTrue();
        assertThat(sheet.questions()).isEmpty();
        assertThat(sheet.summary()).isEqualTo("0 questions");
    }

    @Test
    void askingForAQuestionThatIsNotThere() {
        assertThatThrownBy(() -> new AnswerSheet().question("lot"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("not asking for");
    }

    @Test
    void checkingAWholeSet() {
        AnswerSheet sheet = Interactive.reconcile(List.of("lot", "shift"), asked());
        sheet.check(Map.of("lot", "L4417"));
        assertThatThrownBy(() -> sheet.check(Map.of()))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("lot needs an answer");
        assertThatThrownBy(() -> sheet.check(Map.of("lot", "L4417", "ghost", "x")))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("not asking for: ghost");
    }

    @Test
    void askingTheMachine() throws Exception {
        bench().session.command("LABEL", "asked");
        assertThat(Interactive.ask(bench.session)).containsExactly("lot", "shift");
    }

    @Test
    void aMachineWaitingOnNothing() throws Exception {
        started();
        assertThat(Interactive.ask(bench.session)).isEmpty();
    }

    @Test
    void answeringTheMachine() throws Exception {
        bench().session.command("LABEL", "asked");
        AnswerSheet sheet = Interactive.reconcile(Interactive.ask(bench.session), asked());
        assertThat(Interactive.answer(bench.session, sheet, Map.of("lot", "L4417")))
                .isEqualTo(2);
        assertThat(bench.machine.answered()).isEqualTo(Map.of("lot", "L4417", "shift", "A"));
        assertThat(Interactive.ask(bench.session)).isEmpty();
    }

    @Test
    void aBadAnswerSendsNothing() throws Exception {
        bench().session.command("LABEL", "asked");
        AnswerSheet sheet = Interactive.reconcile(Interactive.ask(bench.session), asked());
        assertThatThrownBy(() -> Interactive.answer(bench.session, sheet, Map.of("lot", "WAY TOO LONG FOR THIS")))
                .isInstanceOf(LabelError.class);
        assertThat(bench.machine.answered()).isEmpty();
    }

    @Test
    void changingTheLabelForgetsTheAnswers() throws Exception {
        bench().session.command("LABEL", "asked");
        AnswerSheet sheet = Interactive.reconcile(Interactive.ask(bench.session), asked());
        Interactive.answer(bench.session, sheet, Map.of("lot", "L4417"));
        bench.machine.putLabel("tray", "y");
        bench.session.command("LABEL", "tray");
        assertThat(bench.machine.answered()).isEmpty();
        assertThat(Interactive.ask(bench.session)).containsExactly("lot", "shift");
    }

    @Test
    void answeringAFieldTheMachineNeverAskedFor() throws Exception {
        bench();
        assertThatThrownBy(() -> bench.session.command("SENDINTER", "ghost", "x"))
                .isInstanceOf(CommandRejected.class);
    }
}
