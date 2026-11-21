package io.github.rkaushikdev.nozzlewright.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LinkClosed;
import io.github.rkaushikdev.nozzlewright.PermissionDenied;
import io.github.rkaushikdev.nozzlewright.settings.LinkSettings;
import io.github.rkaushikdev.nozzlewright.settings.SessionSettings;
import io.github.rkaushikdev.nozzlewright.testing.ScriptedPrinter;
import io.github.rkaushikdev.nozzlewright.transport.LoopbackTransport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SupervisorTest {

    static final String VERSION_OK = "0:FAM=HVX8PEN,REV=2,BUILD=2.4.118";

    static final LinkSettings FAST_LINK =
            LinkSettings.builder().reconnectBackoffSeconds(0.01, 0.02).build();
    static final SessionSettings FAST_SESSION = SessionSettings.builder()
            .keepaliveIntervalSeconds(0.02)
            .commandTimeoutSeconds(0.4)
            .retries(0)
            .build();

    /** A bench full of identical printers, one per connect attempt. */
    static final class Bench implements Supplier<LoopbackTransport> {
        final Map<String, String> replies = new HashMap<>();
        final List<LoopbackTransport> links = new CopyOnWriteArrayList<>();
        final List<ScriptedPrinter> printers = new CopyOnWriteArrayList<>();

        Bench(String... replyPairs) {
            replies.put("VERSION", VERSION_OK);
            for (int index = 0; index < replyPairs.length; index += 2) {
                replies.put(replyPairs[index], replyPairs[index + 1]);
            }
        }

        @Override
        public LoopbackTransport get() {
            ScriptedPrinter printer = new ScriptedPrinter(Map.copyOf(replies));
            LoopbackTransport link = new LoopbackTransport(printer, "bench-" + links.size());
            links.add(link);
            printers.add(printer);
            return link;
        }

        ScriptedPrinter latest() {
            return printers.get(printers.size() - 1);
        }
    }

    private PrinterSession session;

    @AfterEach
    void stop() throws InterruptedException {
        if (session != null) {
            session.stop();
        }
    }

    static PrinterSession.Builder make(Bench bench) {
        return PrinterSession.builder("line4", bench).linkSettings(FAST_LINK).sessionSettings(FAST_SESSION);
    }

    Bench running() throws Exception {
        Bench bench = new Bench("STATUS", "0:STATE=READY");
        session = make(bench).build();
        session.start();
        assertThat(session.waitReady(2.0)).isTrue();
        return bench;
    }

    @Test
    void sessionReachesReady() throws Exception {
        running();
        assertThat(session.state()).isSameAs(SessionState.READY);
        assertThat(session.connects()).isEqualTo(1);
    }

    @Test
    void versionBuildsTheDialect() throws Exception {
        running();
        assertThat(session.dialect()).isPresent();
        assertThat(session.dialect().get().family()).isEqualTo("HVX8PEN");
        assertThat(session.dialect().get().revision()).isEqualTo(2);
        assertThat(session.dialect().get().build()).isEqualTo("2.4.118");
    }

    @Test
    void commandsGoThrough() throws Exception {
        Bench bench = running();
        assertThat(session.command("STATUS").record()).containsEntry("STATE", "READY");
        assertThat(bench.latest().count("STATUS")).isEqualTo(1);
    }

    @Test
    void startIsIdempotent() throws Exception {
        Bench bench = running();
        session.start();
        Thread.sleep(50);
        assertThat(bench.links).hasSize(1);
    }

    @Test
    void stopClosesTheLink() throws Exception {
        Bench bench = running();
        session.stop();
        assertThat(session.state()).isSameAs(SessionState.DISCONNECTED);
        assertThat(bench.links.get(0).isOpen()).isFalse();
    }

    @Test
    void commandBeforeReadyIsRefused() {
        PrinterSession idle = make(new Bench()).build();
        assertThatThrownBy(() -> idle.command("STATUS"))
                .isInstanceOf(LinkClosed.class)
                .hasMessageContaining("not ready");
    }

    @Test
    void credentialsAreUsedAtConnect() throws Exception {
        Bench bench = new Bench("LOGIN", "0:USER=mueller,LEVEL=2", "STATUS", "0:");
        session = make(bench).credentials(new Credentials("mueller", "4417")).build();
        session.start();
        assertThat(session.waitReady(2.0)).isTrue();
        assertThat(session.operator()).isPresent();
        assertThat(session.operator().get().level()).isEqualTo(2);
        assertThat(bench.latest().count("LOGIN")).isEqualTo(1);
    }

    @Test
    void levelIsEnforcedBeforeTheWire() throws Exception {
        Bench bench = new Bench("LOGIN", "0:USER=mueller,LEVEL=1");
        session = make(bench).credentials(new Credentials("mueller", "4417")).build();
        session.start();
        assertThat(session.waitReady(2.0)).isTrue();
        assertThatThrownBy(() -> session.command("FIRINGVOL", 1, 9000)).isInstanceOf(PermissionDenied.class);
        assertThat(bench.latest().count("FIRINGVOL")).isZero();
    }

    @Test
    void badVersionReplyFaultsAndRetries() throws Exception {
        Bench bench = new Bench();
        bench.replies.put("VERSION", "0:FAM=CIJ,REV=2");
        session = make(bench).build();
        session.start();
        Thread.sleep(200);
        assertThat(session.state()).isIn(SessionState.CONNECTING, SessionState.FAULTED);
        assertThat(session.faults()).isGreaterThanOrEqualTo(1);
        assertThat(bench.links.size()).isGreaterThan(1);
    }

    @Test
    void stateHookSeesTheWalk() throws Exception {
        List<SessionState> seen = new CopyOnWriteArrayList<>();
        session = make(new Bench()).onState((changed, state) -> seen.add(state)).build();
        session.start();
        assertThat(session.waitReady(2.0)).isTrue();
        session.stop();
        assertThat(seen.get(0)).isSameAs(SessionState.CONNECTING);
        assertThat(seen).contains(SessionState.READY);
        assertThat(seen.get(seen.size() - 1)).isSameAs(SessionState.DISCONNECTED);
    }

    @Test
    void linkLossReconnects() throws Exception {
        Bench bench = new Bench("STATUS", "0:");
        session = make(bench).build();
        session.start();
        assertThat(session.waitReady(2.0)).isTrue();
        bench.links.get(0).dropAfter(0);
        assertThatThrownBy(() -> session.command("STATUS")).isInstanceOf(LinkClosed.class);
        Thread.sleep(300);
        assertThat(session.connects()).isGreaterThanOrEqualTo(2);
        assertThat(bench.links.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void operatorIsLoggedBackInAfterADrop() throws Exception {
        Bench bench = new Bench("LOGIN", "0:USER=mueller,LEVEL=2", "STATUS", "0:");
        session = make(bench).credentials(new Credentials("mueller", "4417")).build();
        session.start();
        assertThat(session.waitReady(2.0)).isTrue();
        bench.links.get(0).dropAfter(0);
        assertThatThrownBy(() -> session.command("STATUS")).isInstanceOf(LinkClosed.class);
        Thread.sleep(300);
        assertThat(session.waitReady(2.0)).isTrue();
        assertThat(session.operator()).isPresent();
        assertThat(bench.printers.get(bench.printers.size() - 1).count("LOGIN")).isEqualTo(1);
    }

    @Test
    void waitReadyTimesOutOnADeadBench() throws Exception {
        session = PrinterSession.builder("line9", () -> {
                    LoopbackTransport link = new LoopbackTransport("dead");
                    link.dropAfter(0);
                    return link;
                })
                .linkSettings(FAST_LINK)
                .sessionSettings(FAST_SESSION)
                .build();
        session.start();
        assertThat(session.waitReady(0.3)).isFalse();
    }
}
