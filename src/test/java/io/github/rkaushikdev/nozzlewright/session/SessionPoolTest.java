package io.github.rkaushikdev.nozzlewright.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.printer.FiringProfile;
import io.github.rkaushikdev.nozzlewright.printer.FiringTable;
import io.github.rkaushikdev.nozzlewright.printer.GroupGeometry;
import io.github.rkaushikdev.nozzlewright.printer.HeadGeometry;
import io.github.rkaushikdev.nozzlewright.printer.PenGeometry;
import io.github.rkaushikdev.nozzlewright.printer.PrintMode;
import io.github.rkaushikdev.nozzlewright.printer.PrinterConfig;
import io.github.rkaushikdev.nozzlewright.printer.SpeedSource;
import io.github.rkaushikdev.nozzlewright.simulator.SimulatedPrinter;
import io.github.rkaushikdev.nozzlewright.testing.Concurrently;
import io.github.rkaushikdev.nozzlewright.testing.Fast;
import io.github.rkaushikdev.nozzlewright.transport.LoopbackTransport;
import io.github.rkaushikdev.nozzlewright.transport.Responder;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SessionPoolTest {

    static PrinterConfig config() {
        HeadGeometry head =
                new HeadGeometry("HVX8PEN", 300, 300, List.of(new GroupGeometry(1, List.of(new PenGeometry(1)))));
        return PrinterConfig.builder(head, FiringTable.of(new FiringProfile(1, 9000, 1500)))
                .noEncoder()
                .speedSource(SpeedSource.FIXED)
                .autospeedMmPerMin(24_000)
                .printMode(PrintMode.DRIVER)
                .build();
    }

    /** Machines and the pool in front of them. */
    static final class Bench {
        final Map<String, SimulatedPrinter> machines = Map.of(
                "line4",
                        SimulatedPrinter.builder()
                                .config(config())
                                .label("carton", "x")
                                .build(),
                "line5",
                        SimulatedPrinter.builder()
                                .config(config())
                                .label("carton", "x")
                                .build());
        final AtomicInteger built = new AtomicInteger();
        final SessionPool pool;

        Bench() {
            this(30.0);
        }

        Bench(double lingerSeconds) {
            pool = new SessionPool(this::build, lingerSeconds, 3.0);
        }

        PrinterSession build(String printerId) {
            built.incrementAndGet();
            SimulatedPrinter machine = machines.get(printerId);
            return PrinterSession.builder(printerId, () -> new LoopbackTransport(machine, "sim"))
                    .credentials(new Credentials("factory", "9999"))
                    .linkSettings(Fast.LINK)
                    .sessionSettings(Fast.SESSION)
                    .build();
        }
    }

    @Test
    void borrowingBringsALinkUp() throws Exception {
        Bench bench = new Bench();
        bench.pool.borrow("line4", session -> {
            assertThat(SessionPool.isUp(session)).isTrue();
            assertThat(bench.pool.live()).containsExactly("line4");
            return session;
        });
        bench.pool.close();
    }

    @Test
    void theLinkLingersAfterTheLastBorrower() throws Exception {
        Bench bench = new Bench();
        bench.pool.borrow("line4", session -> session);
        assertThat(bench.pool.live()).containsExactly("line4");
        assertThat(bench.pool.borrowersOf("line4")).isZero();
        bench.pool.close();
    }

    @Test
    void twoBorrowersShareOneLink() throws Exception {
        Bench bench = new Bench();
        PrinterSession first = bench.pool.acquire("line4");
        PrinterSession second = bench.pool.acquire("line4");
        assertThat(first).isSameAs(second);
        assertThat(bench.pool.borrowersOf("line4")).isEqualTo(2);
        bench.pool.release("line4");
        assertThat(bench.pool.borrowersOf("line4")).isEqualTo(1);
        bench.pool.release("line4");
        assertThat(bench.built).hasValue(1);
        assertThat(bench.pool.reused()).isEqualTo(1);
        bench.pool.close();
    }

    @Test
    void borrowersArrivingAtOnceStillOpenOneLink() throws Exception {
        Bench bench = new Bench();
        List<Concurrently.Outcome<PrinterSession>> outcomes = Concurrently.run(5, () -> bench.pool.acquire("line4"));
        Set<PrinterSession> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Concurrently.Outcome<PrinterSession> outcome : outcomes) {
            assertThat(outcome.ok()).isTrue();
            distinct.add(outcome.value());
        }
        assertThat(distinct).hasSize(1);
        assertThat(bench.built).hasValue(1);
        assertThat(bench.pool.borrowersOf("line4")).isEqualTo(5);
        for (int index = 0; index < 5; index++) {
            bench.pool.release("line4");
        }
        bench.pool.close();
    }

    @Test
    void twoMachinesGetTwoLinks() throws Exception {
        Bench bench = new Bench();
        bench.pool.borrow(
                "line4",
                outer -> bench.pool.borrow("line5", inner -> {
                    assertThat(bench.pool.live()).containsExactly("line4", "line5");
                    assertThat(bench.built).hasValue(2);
                    return inner;
                }));
        bench.pool.close();
    }

    @Test
    void aLinkComesBackAfterItWasClosed() throws Exception {
        Bench bench = new Bench();
        bench.pool.borrow("line4", session -> session);
        bench.pool.drop("line4");
        assertThat(bench.pool.live()).isEmpty();
        bench.pool.borrow("line4", session -> {
            assertThat(bench.pool.live()).containsExactly("line4");
            return session;
        });
        assertThat(bench.built).hasValue(2);
        bench.pool.close();
    }

    @Test
    void noLingerClosesStraightAway() throws Exception {
        Bench bench = new Bench(0.0);
        bench.pool.borrow("line4", session -> {
            assertThat(bench.pool.live()).containsExactly("line4");
            return session;
        });
        assertThat(bench.pool.live()).isEmpty();
        assertThat(bench.pool.closed()).isEqualTo(1);
    }

    @Test
    void anIdleLinkIsReaped() throws Exception {
        Bench bench = new Bench(0.05);
        bench.pool.borrow("line4", session -> session);
        for (int tick = 0; tick < 200 && !bench.pool.live().isEmpty(); tick++) {
            Thread.sleep(10);
        }
        assertThat(bench.pool.live()).isEmpty();
        assertThat(bench.pool.closed()).isEqualTo(1);
    }

    @Test
    void borrowingAgainBeforeTheReaperFires() throws Exception {
        Bench bench = new Bench(0.2);
        bench.pool.borrow("line4", session -> session);
        bench.pool.borrow("line4", session -> {
            assertThat(bench.pool.live()).containsExactly("line4");
            return session;
        });
        Thread.sleep(50);
        assertThat(bench.pool.live()).containsExactly("line4");
        assertThat(bench.built).hasValue(1);
        bench.pool.close();
    }

    @Test
    void droppingAMachineNobodyHasBorrowed() throws Exception {
        Bench bench = new Bench();
        assertThat(bench.pool.drop("line9")).isFalse();
    }

    @Test
    void droppingALinkThatIsInUse() throws Exception {
        Bench bench = new Bench();
        bench.pool.acquire("line4");
        assertThat(bench.pool.drop("line4")).isTrue();
        assertThat(bench.pool.live()).isEmpty();
        assertThat(bench.pool.borrowersOf("line4")).isZero();
    }

    @Test
    void aMachineThatNeverAnswers() throws Exception {
        Bench bench = new Bench();
        bench.machines.get("line4").checksumReplies(true);
        Responder silent = data -> {
            Thread.sleep(5000);
            return new byte[0];
        };
        SessionPool pool = new SessionPool(
                printerId -> PrinterSession.builder(printerId, () -> new LoopbackTransport(silent, "sim"))
                        .credentials(new Credentials("factory", "9999"))
                        .linkSettings(Fast.LINK)
                        .sessionSettings(Fast.SESSION)
                        .build(),
                30.0,
                0.05);
        assertThatThrownBy(() -> pool.acquire("line4"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("did not answer");
        assertThat(pool.borrowersOf("line4")).isZero();
        pool.close();
    }

    @Test
    void closingTakesEverythingDown() throws Exception {
        Bench bench = new Bench();
        bench.pool.acquire("line4");
        bench.pool.acquire("line5");
        bench.pool.close();
        assertThat(bench.pool.live()).isEmpty();
        assertThat(bench.pool.closed()).isEqualTo(2);
    }

    @Test
    void releasingSomethingThatWasNeverBorrowed() throws Exception {
        Bench bench = new Bench();
        bench.pool.release("line9");
        assertThat(bench.pool.live()).isEmpty();
    }

    @Test
    void releasingMoreThanWasBorrowed() throws Exception {
        Bench bench = new Bench();
        bench.pool.acquire("line4");
        bench.pool.release("line4");
        bench.pool.release("line4");
        assertThat(bench.pool.borrowersOf("line4")).isZero();
        bench.pool.close();
    }

    @Test
    void theStats() throws Exception {
        Bench bench = new Bench();
        bench.pool.borrow(
                "line4",
                outer -> bench.pool.borrow("line4", inner -> {
                    Map<String, Integer> body = bench.pool.stats();
                    assertThat(body).containsEntry("live", 1);
                    assertThat(body).containsEntry("borrowers", 2);
                    assertThat(body).containsEntry("opened", 1);
                    assertThat(body).containsEntry("reused", 1);
                    return inner;
                }));
        bench.pool.close();
        assertThat(bench.pool.stats()).containsEntry("closed", 1);
    }
}
