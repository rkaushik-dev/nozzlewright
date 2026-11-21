package io.github.rkaushikdev.nozzlewright.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.nozzlewright.TransportError;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DialerTest {

    /** Fails to open until the countdown runs out. */
    static final class FlakyLink extends LoopbackTransport {
        int remaining;

        FlakyLink(int failures) {
            super("flaky");
            remaining = failures;
        }

        @Override
        protected void openLink() {
            if (remaining > 0) {
                remaining--;
                throw new TransportError("radio out of range", endpoint());
            }
        }
    }

    /** Hands out links that fail a set number of times each. */
    static final class CountingFactory implements Supplier<FlakyLink> {
        final List<FlakyLink> made = new ArrayList<>();
        private final List<Integer> failuresPerLink;

        CountingFactory(Integer... failuresPerLink) {
            this.failuresPerLink = List.of(failuresPerLink);
        }

        @Override
        public FlakyLink get() {
            int count = made.size() < failuresPerLink.size() ? failuresPerLink.get(made.size()) : 0;
            FlakyLink link = new FlakyLink(count);
            made.add(link);
            return link;
        }
    }

    @Test
    void firstAttemptSucceeds() throws Exception {
        CountingFactory factory = new CountingFactory();
        DialOutcome outcome =
                Dialer.builder(factory).backoffSeconds(0.01).build().dial();
        assertThat(outcome.connected()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(1);
        assertThat(outcome.waitedSeconds()).isZero();
        assertThat(factory.made).hasSize(1);
    }

    @Test
    void retriesUntilTheRadioAnswers() throws Exception {
        CountingFactory factory = new CountingFactory(1, 1, 0);
        DialOutcome outcome =
                Dialer.builder(factory).backoffSeconds(0.01).jitter(0.0).build().dial();
        assertThat(outcome.connected()).isTrue();
        assertThat(outcome.attempts()).isEqualTo(3);
        assertThat(outcome.waitedSeconds()).isCloseTo(0.02, within(1e-9));
    }

    @Test
    void attemptBudgetGivesUp() throws Exception {
        CountingFactory factory = new CountingFactory(1, 1, 1, 1);
        DialOutcome outcome = Dialer.builder(factory)
                .backoffSeconds(0.01)
                .jitter(0.0)
                .maxAttempts(2)
                .build()
                .dial();
        assertThat(outcome.connected()).isFalse();
        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(outcome.lastError()).containsInstanceOf(TransportError.class);
    }

    @Test
    void failureHookSeesEveryAttempt() throws Exception {
        List<Integer> seen = new ArrayList<>();
        CountingFactory factory = new CountingFactory(1, 1, 0);
        Dialer.builder(factory)
                .backoffSeconds(0.01)
                .jitter(0.0)
                .onFailure((attempt, error) -> seen.add(attempt))
                .build()
                .dial();
        assertThat(seen).containsExactly(1, 2);
    }

    @Test
    void backoffClimbsAndThenHolds() {
        Dialer dialer = Dialer.builder(LoopbackTransport::new)
                .backoffSeconds(1.0, 2.0, 4.0)
                .jitter(0.0)
                .build();
        assertThat(IntStream.of(1, 2, 3, 4, 9).mapToObj(dialer::delayFor).toList())
                .containsExactly(1.0, 2.0, 4.0, 4.0, 4.0);
    }

    @Test
    void attemptZeroIsRejected() {
        Dialer dialer = Dialer.builder(LoopbackTransport::new)
                .backoffSeconds(1.0)
                .jitter(0.0)
                .build();
        assertThatThrownBy(() -> dialer.delayFor(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counted from one");
    }

    @Test
    void jitterStaysInsideTheBand() {
        Dialer dialer = Dialer.builder(LoopbackTransport::new)
                .backoffSeconds(10.0)
                .jitter(0.2)
                .build();
        dialer.seed(4);
        for (int round = 0; round < 50; round++) {
            assertThat(dialer.delayFor(1)).isBetween(8.0, 12.0);
        }
    }

    @Test
    void jitterActuallyVaries() {
        Dialer dialer = Dialer.builder(LoopbackTransport::new)
                .backoffSeconds(10.0)
                .jitter(0.2)
                .build();
        dialer.seed(11);
        Set<Long> seen = new HashSet<>();
        for (int round = 0; round < 20; round++) {
            seen.add(Math.round(dialer.delayFor(1) * 1e6));
        }
        assertThat(seen).hasSizeGreaterThan(1);
    }

    @Test
    void emptyBackoffIsRejected() {
        assertThatThrownBy(() -> Dialer.builder(LoopbackTransport::new)
                        .backoffSeconds(List.of())
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.0, 2.5})
    void jitterBoundsAreChecked(double bad) {
        assertThatThrownBy(() -> Dialer.builder(LoopbackTransport::new)
                        .backoffSeconds(1.0)
                        .jitter(bad)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between zero and one");
    }

    @Test
    void failedLinkIsClosedBeforeTheNextTry() throws Exception {
        CountingFactory factory = new CountingFactory(1, 0);
        Dialer.builder(factory).backoffSeconds(0.01).jitter(0.0).build().dial();
        assertThat(factory.made.get(0).isOpen()).isFalse();
        assertThat(factory.made.get(1).isOpen()).isTrue();
    }
}
