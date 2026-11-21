package io.github.rkaushikdev.nozzlewright.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.printer.Severity;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class FleetNotifyTest {

    static final Instant START = Instant.parse("2025-05-12T06:00:00Z");

    /** A sink that keeps what it was given. */
    static final class Collecting implements Sink {
        final String name;
        final List<Notice> seen = new CopyOnWriteArrayList<>();

        Collecting(String name) {
            this.name = name;
        }

        Collecting() {
            this("collecting");
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void deliver(Notice notice) {
            seen.add(notice);
        }
    }

    /** A sink that never works. */
    static final class Broken implements Sink {
        @Override
        public String name() {
            return "broken";
        }

        @Override
        public void deliver(Notice notice) throws IOException {
            throw new IOException("no route to host");
        }
    }

    /** One post the fake client was asked to make. */
    record Call(String url, Map<String, Object> json, Map<String, String> headers) {}

    /** Enough of an HTTP client for the webhook sink. */
    static final class FakeClient implements Poster {
        final int statusCode;
        final long delayMillis;
        final List<Call> calls = new CopyOnWriteArrayList<>();

        FakeClient(int statusCode, long delayMillis) {
            this.statusCode = statusCode;
            this.delayMillis = delayMillis;
        }

        FakeClient() {
            this(200, 0);
        }

        @Override
        public int post(String url, Map<String, Object> json, Map<String, String> headers) throws InterruptedException {
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            calls.add(new Call(url, json, headers));
            return statusCode;
        }
    }

    static Notifier notifier(Sink... sinks) {
        return Notifier.builder("bremen").sinks(sinks).build();
    }

    static AlarmState open(String alarm, Severity severity) {
        return AlarmState.open("line4", alarm, severity);
    }

    static AlarmState cleared(String alarm, Severity severity) {
        return AlarmState.cleared("line4", alarm, severity);
    }

    @Test
    void aNewAlarmIsNews() {
        Notifier router = notifier();
        Notice notice = router.consider(open("INK_OUT", Severity.STOP), START).orElseThrow();
        assertThat(notice.change()).isSameAs(Change.RAISED);
        assertThat(notice.siteId()).isEqualTo("bremen");
    }

    @Test
    void theSameAlarmAgainIsNot() {
        Notifier router = notifier();
        router.consider(open("INK_LOW", Severity.WARNING), START);
        assertThat(router.consider(open("INK_LOW", Severity.WARNING), START.plusSeconds(2)))
                .isEmpty();
        assertThat(router.stats().suppressed()).isEqualTo(1);
    }

    @Test
    void anAlarmGettingWorseSpeaksStraightAway() {
        Notifier router = notifier();
        router.consider(open("INK_LOW", Severity.WARNING), START);
        Notice worse = router.consider(open("INK_LOW", Severity.STOP), START.plusSeconds(30))
                .orElseThrow();
        assertThat(worse.change()).isSameAs(Change.WORSENED);
    }

    @Test
    void infoAlarmsStayInTheBuilding() {
        assertThat(notifier().consider(open("CAP_OPEN", Severity.INFO), START)).isEmpty();
    }

    @Test
    void theFloorCanBeLowered() {
        Notifier router = Notifier.builder("bremen").floor(Severity.INFO).build();
        assertThat(router.consider(open("CAP_OPEN", Severity.INFO), START)).isPresent();
    }

    @Test
    void clearingIsReported() {
        Notifier router = notifier();
        router.consider(open("INK_LOW", Severity.WARNING), START);
        Notice gone = router.consider(cleared("INK_LOW", Severity.WARNING), START.plus(Duration.ofMinutes(1)))
                .orElseThrow();
        assertThat(gone.change()).isSameAs(Change.CLEARED);
        assertThat(gone.summary()).isEqualTo("INK_LOW cleared on line4");
    }

    @Test
    void clearingSomethingWeNeverMentionedSaysNothing() {
        assertThat(notifier().consider(cleared("INK_LOW", Severity.WARNING), START))
                .isEmpty();
    }

    @Test
    void clearingTwiceOnlySpeaksOnce() {
        Notifier router = notifier();
        router.consider(open("INK_LOW", Severity.WARNING), START);
        router.consider(cleared("INK_LOW", Severity.WARNING), START);
        assertThat(router.consider(cleared("INK_LOW", Severity.WARNING), START.plus(Duration.ofMinutes(1))))
                .isEmpty();
    }

    @Test
    void anAlarmThatComesBackIsNewsAgain() {
        Notifier router = notifier();
        router.consider(open("INK_LOW", Severity.WARNING), START);
        router.consider(cleared("INK_LOW", Severity.WARNING), START);
        Notice back = router.consider(open("INK_LOW", Severity.WARNING), START.plus(Duration.ofMinutes(1)))
                .orElseThrow();
        assertThat(back.change()).isSameAs(Change.RAISED);
    }

    @Test
    void aStopNobodyAnswersGetsAReminder() {
        Notifier router = notifier();
        router.consider(open("HEAD_FAULT", Severity.STOP), START);
        Notice later = router.consider(open("HEAD_FAULT", Severity.STOP), START.plus(Duration.ofMinutes(25)))
                .orElseThrow();
        assertThat(later.change()).isSameAs(Change.REMINDER);
    }

    @Test
    void anAcknowledgedStopIsLeftAlone() {
        Notifier router = notifier();
        router.consider(open("HEAD_FAULT", Severity.STOP), START);
        assertThat(router.consider(
                        open("HEAD_FAULT", Severity.STOP).withAcknowledged(true), START.plus(Duration.ofMinutes(25))))
                .isEmpty();
    }

    @Test
    void aWarningNeverReminds() {
        Notifier router = notifier();
        router.consider(open("INK_LOW", Severity.WARNING), START);
        assertThat(router.consider(open("INK_LOW", Severity.WARNING), START.plus(Duration.ofHours(3))))
                .isEmpty();
    }

    @Test
    void forgettingAMachine() {
        Notifier router = notifier();
        router.consider(open("INK_LOW", Severity.WARNING), START);
        router.consider(AlarmState.open("line5", "INK_LOW", Severity.WARNING), START);
        assertThat(router.forget("line4")).isEqualTo(1);
        assertThat(router.consider(open("INK_LOW", Severity.WARNING), START.plusSeconds(1)))
                .isPresent();
    }

    @Test
    void theSummaryPrefersTheLineNumber() {
        Notifier router = notifier();
        Notice notice = router.consider(open("INK_LOW", Severity.WARNING).onLine("4"), START)
                .orElseThrow();
        assertThat(notice.summary()).isEqualTo("INK_LOW raised on line 4 (warning)");
        assertThat(notice.key()).isEqualTo(new Notice.Key("line4", "INK_LOW"));
    }

    @Test
    void sendingReachesEverySink() throws Exception {
        Collecting one = new Collecting("one");
        Collecting two = new Collecting("two");
        Notifier router = notifier(one, two);
        assertThat(router.announce(open("INK_OUT", Severity.STOP), START)).isPresent();
        assertThat(one.seen).hasSize(1);
        assertThat(two.seen).hasSize(1);
        assertThat(router.stats().sent()).isEqualTo(1);
    }

    @Test
    void oneBrokenSinkDoesNotStopTheOthers() throws Exception {
        Collecting good = new Collecting();
        Notifier router = notifier(new Broken(), good);
        router.announce(open("INK_OUT", Severity.STOP), START);
        assertThat(good.seen).hasSize(1);
        assertThat(router.stats().failed()).isEqualTo(1);
        assertThat(router.stats().sent()).isEqualTo(1);
    }

    @Test
    void nothingIsSentWhenThereIsNothingToSay() throws Exception {
        Collecting sink = new Collecting();
        Notifier router = notifier(sink);
        assertThat(router.announce(open("CAP_OPEN", Severity.INFO), START)).isEmpty();
        assertThat(sink.seen).isEmpty();
    }

    @Test
    void theWebhookPostsTheBody() throws Exception {
        FakeClient client = new FakeClient();
        WebhookSink sink = new WebhookSink("http://ops/hook", client).withToken("s3cret");
        notifier(sink).announce(open("INK_OUT", Severity.STOP).onLine("4"), START);
        assertThat(client.calls).hasSize(1);
        Call call = client.calls.get(0);
        assertThat(call.url()).isEqualTo("http://ops/hook");
        assertThat(call.headers()).containsEntry("authorization", "Bearer s3cret");
        assertThat(call.json()).containsEntry("alarm", "INK_OUT");
        assertThat(call.json()).containsEntry("severity", "stop");
        assertThat(call.json()).containsEntry("site", "bremen");
        assertThat(call.json()).containsEntry("at", "2025-05-12T06:00:00+00:00");
    }

    @Test
    void theWebhookLeavesTheHeaderOffWithoutAToken() throws Exception {
        FakeClient client = new FakeClient();
        new WebhookSink("http://ops/hook", client)
                .deliver(new Notice("bremen", "line4", "INK_OUT", Severity.STOP, Change.RAISED, START));
        assertThat(client.calls.get(0).headers()).doesNotContainKey("authorization");
    }

    @Test
    void aRefusingWebhookIsCountedNotRaised() throws Exception {
        FakeClient client = new FakeClient(503, 0);
        Notifier router = notifier(new WebhookSink("http://ops/hook", client));
        router.announce(open("INK_OUT", Severity.STOP), START);
        assertThat(router.stats().failed()).isEqualTo(1);
        assertThat(router.stats().sent()).isZero();
    }

    @Test
    void aSlowWebhookGivesUp() {
        FakeClient client = new FakeClient(200, 200);
        WebhookSink sink = new WebhookSink("http://ops/hook", client).withTimeoutSeconds(0.01);
        assertThatThrownBy(() -> sink.deliver(new Notice("bremen", "line4", "X", Severity.STOP, Change.RAISED, START)))
                .isInstanceOf(HttpTimeoutException.class);
    }

    @Test
    void theLogSinkTakesAnything() {
        LogSink sink = new LogSink();
        sink.deliver(new Notice("bremen", "line4", "X", Severity.STOP, Change.RAISED, START));
        sink.deliver(new Notice("bremen", "line4", "X", Severity.WARNING, Change.CLEARED, START));
    }

    @Test
    void theStatsFlatten() {
        assertThat(notifier().stats().asMap()).isEqualTo(Map.of("sent", 0, "suppressed", 0, "failed", 0));
    }
}
