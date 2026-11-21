package io.github.rkaushikdev.nozzlewright.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.testing.Concurrently;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RuntimeTest {

    @Test
    void stopSignalsIncludeTheOnesThisPlatformHas() {
        assertThat(Lifecycle.STOP_SIGNALS).contains("SIGINT", "SIGTERM");
    }

    @Test
    void theGracePeriodIsShorterThanAServiceManagerWaits() {
        assertThat(Lifecycle.DEFAULT_GRACE_SECONDS).isPositive().isLessThan(30);
    }

    @Test
    void aFreshShutdownIsNotRequested() {
        assertThat(new Shutdown().requested()).isFalse();
    }

    @Test
    void requestingAShutdown() throws Exception {
        Shutdown asked = new Shutdown();
        asked.request("operator pressed stop");
        assertThat(asked.requested()).isTrue();
        assertThat(asked.await()).isEqualTo("operator pressed stop");
    }

    @Test
    void theFirstReasonIsTheOneKept() {
        Shutdown asked = new Shutdown();
        asked.request("first");
        asked.request("second");
        assertThat(asked.reason()).isEqualTo("first");
    }

    @Test
    void waitingReturnsOnceItIsRequested() throws Exception {
        Shutdown asked = new Shutdown();
        CompletableFuture<String> waiter = Concurrently.start(asked::await);
        Thread.sleep(10);
        assertThat(waiter).isNotDone();
        asked.request("later");
        assertThat(waiter.get(1, TimeUnit.SECONDS)).isEqualTo("later");
    }

    @Test
    void installingHandlersSaysWhatItWired() {
        List<String> wired = Lifecycle.installSignalHandlers(new Shutdown());
        assertThat(wired).allSatisfy(name -> assertThat(name).isInstanceOf(String.class));
    }

    @Test
    void runUntilStoppedStartsAndStops() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        Shutdown asked = new Shutdown();
        CompletableFuture<String> runner = Concurrently.start(() -> Lifecycle.runUntilStopped(
                () -> events.add("start"), () -> events.add("stop"), asked, Lifecycle.DEFAULT_GRACE_SECONDS, false));
        Thread.sleep(10);
        assertThat(events).containsExactly("start");
        asked.request("test asked");
        assertThat(runner.get(1, TimeUnit.SECONDS)).isEqualTo("test asked");
        assertThat(events).containsExactly("start", "stop");
    }

    @Test
    void aStopThatRaisesDoesNotEscape() throws Exception {
        Shutdown asked = new Shutdown();
        asked.request("go");
        String reason = Lifecycle.runUntilStopped(
                () -> {},
                () -> {
                    throw new IllegalStateException("the database went away");
                },
                asked,
                Lifecycle.DEFAULT_GRACE_SECONDS,
                false);
        assertThat(reason).isEqualTo("go");
    }

    @Test
    void aSlowStopIsAbandonedAfterTheGracePeriod() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Shutdown asked = new Shutdown();
        asked.request("go");
        long before = System.nanoTime();
        Lifecycle.runUntilStopped(
                () -> {},
                () -> {
                    started.countDown();
                    Thread.sleep(5000);
                },
                asked,
                0.05,
                false);
        assertThat(started.getCount()).isZero();
        assertThat(System.nanoTime() - before).isLessThan(TimeUnit.SECONDS.toNanos(4));
    }

    @Test
    void cancellingTheRunnerStillStops() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        Shutdown asked = new Shutdown();
        CompletableFuture<Throwable> ended = new CompletableFuture<>();
        Thread runner = Thread.ofPlatform().start(() -> {
            try {
                Lifecycle.runUntilStopped(
                        () -> events.add("start"),
                        () -> events.add("stop"),
                        asked,
                        Lifecycle.DEFAULT_GRACE_SECONDS,
                        false);
                ended.complete(null);
            } catch (InterruptedException error) {
                ended.complete(error);
            }
        });
        Thread.sleep(10);
        runner.interrupt();
        assertThat(ended.get(1, TimeUnit.SECONDS)).isInstanceOf(InterruptedException.class);
        runner.join(1000);
        assertThat(events).containsExactly("start", "stop");
        assertThat(asked.reason()).isEqualTo("cancelled");
    }

    @Test
    void aStartThatRaisesIsNotSwallowed() {
        assertThatThrownBy(() -> Lifecycle.runUntilStopped(
                        () -> {
                            throw new IllegalStateException("the database is not there");
                        },
                        () -> {},
                        new Shutdown(),
                        Lifecycle.DEFAULT_GRACE_SECONDS,
                        false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not there");
    }

    @Test
    void aStartThatRaisesStillRunsTheStop() {
        List<String> events = new CopyOnWriteArrayList<>();
        assertThatThrownBy(() -> Lifecycle.runUntilStopped(
                        () -> {
                            throw new IllegalStateException("nope");
                        },
                        () -> events.add("stop"),
                        new Shutdown(),
                        Lifecycle.DEFAULT_GRACE_SECONDS,
                        false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(events).isEmpty();
    }
}
