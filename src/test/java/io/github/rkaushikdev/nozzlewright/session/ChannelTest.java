package io.github.rkaushikdev.nozzlewright.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.CommandRejected;
import io.github.rkaushikdev.nozzlewright.DialectMismatch;
import io.github.rkaushikdev.nozzlewright.LinkClosed;
import io.github.rkaushikdev.nozzlewright.PrinterBusy;
import io.github.rkaushikdev.nozzlewright.protocol.Codec;
import io.github.rkaushikdev.nozzlewright.protocol.Dialect;
import io.github.rkaushikdev.nozzlewright.protocol.Response;
import io.github.rkaushikdev.nozzlewright.settings.SessionSettings;
import io.github.rkaushikdev.nozzlewright.testing.Concurrently;
import io.github.rkaushikdev.nozzlewright.testing.Concurrently.Outcome;
import io.github.rkaushikdev.nozzlewright.testing.ScriptedPrinter;
import io.github.rkaushikdev.nozzlewright.testing.Wire;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ChannelTest {

    static final SessionSettings DEFAULT = SessionSettings.builder()
            .keepaliveIntervalSeconds(0.05)
            .commandTimeoutSeconds(0.5)
            .retries(2)
            .build();

    private final List<Channel> opened = new ArrayList<>();
    private ScriptedPrinter printer;

    @AfterEach
    void stopEverything() throws InterruptedException {
        for (Channel channel : opened) {
            channel.stop();
        }
    }

    Channel open(Map<String, String> replies, SessionSettings settings, Duration latency) throws Exception {
        printer = new ScriptedPrinter(replies, "0:", latency);
        Channel channel = new Channel(ScriptedPrinter.link(printer).transport(), Dialect.REVISION_TWO, settings);
        channel.start();
        opened.add(channel);
        return channel;
    }

    Channel open() throws Exception {
        return open(Map.of(), DEFAULT, Duration.ZERO);
    }

    /** The shared case: a channel whose printer reports READY to STATUS. */
    Channel ready() throws Exception {
        return open(Map.of("STATUS", "0:STATE=READY"), DEFAULT, Duration.ZERO);
    }

    @Test
    void startOpensTheTransport() throws Exception {
        Channel channel = open();
        assertThat(channel.running()).isTrue();
        assertThat(channel.transport().isOpen()).isTrue();
        channel.stop();
        assertThat(channel.running()).isFalse();
    }

    @Test
    void startIsIdempotent() throws Exception {
        Channel channel = open();
        channel.start();
        assertThat(channel.transport().stats().opens()).isEqualTo(1);
    }

    @Test
    void simpleExchange() throws Exception {
        Channel made = ready();
        Response response = made.send("STATUS");
        assertThat(response.ok()).isTrue();
        assertThat(response.record()).containsEntry("STATE", "READY");
        assertThat(printer.seen().get(0)).startsWith("STATUS");
    }

    @Test
    void argumentsReachThePrinter() throws Exception {
        Channel made = ready();
        made.send("LABEL", "batch-a");
        assertThat(printer.seen()).anyMatch(line -> line.startsWith("LABEL:batch-a"));
    }

    @Test
    void checksumIsAppendedOnRevisionTwo() throws Exception {
        Channel made = ready();
        made.send("STATUS");
        assertThat(Wire.text(written(made).get(0))).startsWith("STATUS#");
    }

    @Test
    void noChecksumOnRevisionOne() throws Exception {
        ScriptedPrinter.Link link = ScriptedPrinter.scriptedLink(Map.of("STATUS", "0:"));
        Channel channel = new Channel(
                link.transport(),
                Dialect.REVISION_ONE,
                SessionSettings.builder()
                        .keepaliveIntervalSeconds(0.05)
                        .commandTimeoutSeconds(0.5)
                        .build());
        channel.start();
        opened.add(channel);
        channel.send("STATUS");
        assertThat(link.transport().written().get(0)).isEqualTo(Wire.bytes("STATUS\r\n"));
    }

    @Test
    void dialectBlocksAMissingVerb() throws Exception {
        Channel channel = new Channel(ScriptedPrinter.scriptedLink().transport(), Dialect.REVISION_ONE);
        channel.start();
        opened.add(channel);
        assertThatThrownBy(() -> channel.send("ASKINTER")).isInstanceOf(DialectMismatch.class);
    }

    @Test
    void rejectionRaises() throws Exception {
        Channel made = ready();
        printer.expect("LABEL", "10:");
        CommandRejected caught = org.assertj.core.api.Assertions.catchThrowableOfType(
                CommandRejected.class, () -> made.send("LABEL", "missing"));
        assertThat(caught.status()).isEqualTo(10);
        assertThat(made.stats().rejected()).isEqualTo(1);
    }

    @Test
    void busyGetsItsOwnException() throws Exception {
        Channel made = open(
                Map.of("STATUS", "0:STATE=READY"),
                DEFAULT.toBuilder().retries(0).build(),
                Duration.ZERO);
        printer.expect("PRINTMODE", "1:");
        assertThatThrownBy(() -> made.send("PRINTMODE", "STAND")).isInstanceOf(PrinterBusy.class);
    }

    @Test
    void errorCanBeReturnedInsteadOfRaised() throws Exception {
        Channel made = ready();
        printer.expect("LABEL", "10:");
        assertThat(made.sendAnyStatus("LABEL", "missing").status()).isEqualTo(10);
    }

    @Test
    void retryableStatusIsRetried() throws Exception {
        Channel made = ready();
        printer.queue("13:", "13:", "0:STATE=READY");
        assertThat(made.send("STATUS").ok()).isTrue();
        assertThat(made.stats().retries()).isEqualTo(2);
        assertThat(printer.count("STATUS")).isEqualTo(3);
    }

    @Test
    void retriesStopAtTheBudget() throws Exception {
        Channel made = ready();
        printer.expect("STATUS", "13:");
        assertThatThrownBy(() -> made.send("STATUS")).isInstanceOf(CommandRejected.class);
        assertThat(printer.count("STATUS")).isEqualTo(3);
    }

    @Test
    void permanentStatusIsNotRetried() throws Exception {
        Channel made = ready();
        printer.expect("STATUS", "2:");
        assertThatThrownBy(() -> made.send("STATUS")).isInstanceOf(CommandRejected.class);
        assertThat(printer.count("STATUS")).isEqualTo(1);
    }

    @Test
    void silentPrinterTimesOut() throws Exception {
        Channel made = ready();
        printer.goSilent("STATUS");
        assertThatThrownBy(() -> made.send("STATUS"))
                .isInstanceOf(LinkClosed.class)
                .hasMessageContaining("no reply");
        assertThat(made.stats().timeouts()).isEqualTo(1);
    }

    @Test
    void channelRecoversAfterATimeout() throws Exception {
        Channel made = ready();
        printer.goSilent("STATUS");
        assertThatThrownBy(() -> made.send("STATUS")).isInstanceOf(LinkClosed.class);
        printer.speakAgain();
        assertThat(made.send("STATUS").ok()).isTrue();
    }

    @Test
    void keepaliveGoesOutWhenIdle() throws Exception {
        Channel channel = open(
                Map.of(),
                SessionSettings.builder()
                        .keepaliveIntervalSeconds(0.02)
                        .commandTimeoutSeconds(0.5)
                        .build(),
                Duration.ZERO);
        // Wait for the second keepalive rather than sleeping a fixed span and hoping: on a loaded
        // machine twenty milliseconds inside a hundred and twenty can be fewer than two chances.
        for (int round = 0; round < 100 && channel.stats().keepalives() < 2; round++) {
            Thread.sleep(10);
        }
        channel.stop();
        assertThat(channel.stats().keepalives()).isGreaterThanOrEqualTo(2);
        assertThat(printer.count("PING")).isGreaterThanOrEqualTo(2);
    }

    @Test
    void commandsRunOneAtATime() throws Exception {
        Channel made = ready();
        printer.latency(Duration.ofMillis(10));
        List<Outcome<Response>> results = Concurrently.run(5, () -> made.send("STATUS"));
        assertThat(results).allMatch(result -> result.ok() && result.value().ok());
        assertThat(printer.count("STATUS")).isEqualTo(5);
        assertThat(made.stats().sent()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void queueBoundShedsRatherThanGrows() throws Exception {
        Channel channel = open(
                Map.of(),
                SessionSettings.builder()
                        .keepaliveIntervalSeconds(1.0)
                        .commandTimeoutSeconds(2.0)
                        .queueDepth(2)
                        .build(),
                Duration.ofMillis(50));
        List<Outcome<Response>> results = Concurrently.run(12, () -> channel.send("STATUS"));
        assertThat(results).anyMatch(result -> result.failure() instanceof QueueFull);
        assertThat(channel.stats().shed()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void sendBeforeStartIsRefused() {
        Channel channel = new Channel(ScriptedPrinter.scriptedLink().transport());
        assertThatThrownBy(() -> channel.send("STATUS"))
                .isInstanceOf(LinkClosed.class)
                .hasMessageContaining("not running");
    }

    @Test
    void stopFailsAnythingQueued() throws Exception {
        Channel channel = open(
                Map.of(),
                SessionSettings.builder()
                        .keepaliveIntervalSeconds(1.0)
                        .commandTimeoutSeconds(2.0)
                        .build(),
                Duration.ofMillis(200));
        List<CompletableFuture<Response>> sends = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            sends.add(Concurrently.start(() -> channel.send("STATUS")));
        }
        Thread.sleep(10);
        channel.stop();
        assertThat(Concurrently.gather(sends)).anyMatch(result -> result.failure() instanceof LinkClosed);
    }

    @Test
    void linkDropIsRecordedAsAFault() throws Exception {
        Channel channel = open();
        ((io.github.rkaushikdev.nozzlewright.transport.LoopbackTransport) channel.transport()).dropAfter(0);
        assertThatThrownBy(() -> channel.send("STATUS")).isInstanceOf(LinkClosed.class);
    }

    @Test
    void statsSnapshotHasEveryCounter() throws Exception {
        Channel made = ready();
        made.send("STATUS");
        Map<String, Long> snapshot = made.stats().asMap();
        assertThat(snapshot.keySet())
                .containsExactlyInAnyOrder(
                        "sent", "received", "keepalives", "retries", "timeouts", "rejected", "shed", "displaced");
        assertThat(snapshot.get("sent")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void urgentCommandsGoFirst() throws Exception {
        Channel made = ready();
        printer.latency(Duration.ofMillis(20));
        List<CompletableFuture<Response>> sends = new ArrayList<>();
        sends.add(Concurrently.start(() -> made.send("STATUS")));
        Thread.sleep(5);
        for (int index = 0; index < 3; index++) {
            sends.add(Concurrently.start(() -> made.send("STATUS")));
        }
        for (int round = 0; round < 100 && made.depth() < 3; round++) {
            Thread.sleep(1);
        }
        sends.add(Concurrently.start(() -> made.send("STOPPRINT")));
        assertThat(Concurrently.gather(sends)).allMatch(Outcome::ok);
        List<String> verbs =
                printer.seen().stream().map(line -> line.split(":", 2)[0]).toList();
        int secondStatus = verbs.subList(2, verbs.size()).indexOf("STATUS") + 2;
        assertThat(verbs.indexOf("STOPPRINT")).isLessThan(secondStatus);
    }

    @Test
    void prioritiesAreWhereTheyShouldBe() {
        assertThat(Priority.of("STOPPRINT")).isSameAs(Priority.URGENT);
        assertThat(Priority.of("PAUSEPRINT")).isSameAs(Priority.URGENT);
        assertThat(Priority.of("STATUS")).isSameAs(Priority.ROUTINE);
        assertThat(Priority.of("LABEL")).isSameAs(Priority.NORMAL);
    }

    /** A channel with a queue and no worker taking things off it. */
    static Channel idleChannel(int depth) {
        return new Channel(
                ScriptedPrinter.scriptedLink().transport(),
                Dialect.REVISION_TWO,
                SessionSettings.builder()
                        .queueDepth(depth)
                        .commandTimeoutSeconds(0.5)
                        .retries(0)
                        .build());
    }

    /** Put one command on the queue without a worker to take it off. */
    static Pending queued(Channel channel, String verb, Object... args) {
        return channel.enqueue(Codec.buildRequest(verb, args));
    }

    @Test
    void aFullQueueShedsARoutineCommand() {
        Channel made = idleChannel(2);
        Pending first = queued(made, "STATUS");
        queued(made, "STATUS");
        queued(made, "STOPPRINT");

        assertThat(made.stats().displaced()).isEqualTo(1);
        assertThat(made.depth()).isEqualTo(2);
        assertThat(first.future()).isDone();
        assertThatThrownBy(() -> first.future().get())
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(QueueFull.class)
                .hasMessageContaining("displaced by STOPPRINT");
    }

    @Test
    void theOldestRoutineCommandIsTheOneDropped() {
        Channel made = idleChannel(3);
        Pending oldest = queued(made, "STATUS");
        Pending middle = queued(made, "STATUS");
        Pending newest = queued(made, "STATUS");
        queued(made, "STOPPRINT");
        assertThat(oldest.future()).isDone();
        assertThat(middle.future()).isNotDone();
        assertThat(newest.future()).isNotDone();
    }

    @Test
    void aQueueFullOfUrgentCommandsRefuses() {
        Channel made = idleChannel(2);
        queued(made, "STOPPRINT");
        queued(made, "STOPPRINT");
        assertThatThrownBy(() -> queued(made, "PAUSEPRINT"))
                .isInstanceOf(QueueFull.class)
                .hasMessageContaining("outbound queue is at");
        assertThat(made.stats().shed()).isEqualTo(1);
        assertThat(made.stats().displaced()).isZero();
    }

    @Test
    void aRoutineCommandDoesNotDisplaceANormalOne() {
        Channel made = idleChannel(1);
        queued(made, "LABEL", "carton");
        assertThatThrownBy(() -> queued(made, "STATUS")).isInstanceOf(QueueFull.class);
        assertThat(made.stats().displaced()).isZero();
    }

    @Test
    void anUrgentCommandComesOffTheQueueFirst() {
        Channel made = idleChannel(8);
        queued(made, "STATUS");
        queued(made, "LABEL", "carton");
        queued(made, "STOPPRINT");
        List<String> order = new ArrayList<>();
        while (made.depth() > 0) {
            order.add(made.poll().orElseThrow().request.verb());
        }
        assertThat(order).containsExactly("STOPPRINT", "LABEL", "STATUS");
    }

    private static List<byte[]> written(Channel channel) {
        return ((io.github.rkaushikdev.nozzlewright.transport.LoopbackTransport) channel.transport()).written();
    }
}
