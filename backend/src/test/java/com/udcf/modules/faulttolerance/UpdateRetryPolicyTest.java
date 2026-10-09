package com.udcf.modules.faulttolerance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The update client's redirect and retry decisions: bounded, never an endless redirect loop. */
class UpdateRetryPolicyTest {

    private final UpdateRetryPolicy policy = new UpdateRetryPolicy(6, 120);

    @Test
    @DisplayName("accepted at once: done after one attempt")
    void acceptedAtOnce() {
        UpdateAttempt attempt = policy.begin(5);

        assertThat(attempt.first()).isEqualTo(RetryDecision.send(5, 0));
        assertThat(attempt.after(AttemptOutcome.accepted(5))).isEqualTo(RetryDecision.done());
        assertThat(attempt.attempts()).isEqualTo(1);
        assertThat(attempt.last().isFinal()).isTrue();
    }

    @Test
    @DisplayName("a redirect to another node is followed at once")
    void redirectFollowed() {
        UpdateAttempt attempt = policy.begin(5);
        attempt.first();

        assertThat(attempt.after(AttemptOutcome.notPrimary(5, 4))).isEqualTo(RetryDecision.send(4, 0));
        assertThat(attempt.after(AttemptOutcome.accepted(4))).isEqualTo(RetryDecision.done());
        assertThat(attempt.attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("an unreachable node leads to a delayed discovery, then a send to the primary found")
    void unreachableThenDiscovery() {
        UpdateAttempt attempt = policy.begin(5);
        attempt.first();

        assertThat(attempt.after(AttemptOutcome.unreachable(5))).isEqualTo(RetryDecision.discover(120));
        assertThat(attempt.afterDiscovery(Optional.of(4))).isEqualTo(RetryDecision.send(4, 0));
        assertThat(attempt.after(AttemptOutcome.accepted(4))).isEqualTo(RetryDecision.done());
    }

    @Test
    @DisplayName("a ping-pong redirect is not followed back: the client discovers instead")
    void pingPongNotFollowed() {
        UpdateAttempt attempt = policy.begin(1);
        attempt.first();

        assertThat(attempt.after(AttemptOutcome.notPrimary(1, 2))).isEqualTo(RetryDecision.send(2, 0));
        assertThat(attempt.after(AttemptOutcome.notPrimary(2, 1))).isEqualTo(RetryDecision.discover(120));
    }

    @Test
    @DisplayName("a node naming itself, or naming nobody, leads to discovery")
    void selfOrMissingHint() {
        UpdateAttempt self = policy.begin(3);
        self.first();
        assertThat(self.after(AttemptOutcome.notPrimary(3, 3))).isEqualTo(RetryDecision.discover(120));

        UpdateAttempt none = policy.begin(3);
        none.first();
        assertThat(none.after(AttemptOutcome.notPrimary(3, null))).isEqualTo(RetryDecision.discover(120));
    }

    @Test
    @DisplayName("with no known primary the first step is a discovery")
    void unknownPrimaryDiscoversFirst() {
        UpdateAttempt attempt = policy.begin(null);
        assertThat(attempt.first()).isEqualTo(RetryDecision.discover(0));
        assertThat(attempt.afterDiscovery(Optional.empty())).isEqualTo(RetryDecision.discover(120));
    }

    @Test
    @DisplayName("a permanent redirect loop gives up at exactly the attempt limit")
    void permanentLoopGivesUp() {
        UpdateAttempt attempt = policy.begin(1);
        RetryDecision next = attempt.first();
        int steps = 0;
        while (!next.isFinal()) {
            next = switch (next.action()) {
                case SEND -> attempt.after(AttemptOutcome.notPrimary(next.targetNodeId(), next.targetNodeId() == 1 ? 2 : 1));
                case DISCOVER -> attempt.afterDiscovery(Optional.of(1));
                default -> throw new AssertionError(next);
            };
            assertThat(++steps).isLessThan(100);
        }
        assertThat(next).isEqualTo(RetryDecision.giveUp());
        assertThat(attempt.attempts()).isEqualTo(6);
    }

    @Test
    @DisplayName("a primary that never appears also ends in giving up")
    void neverFoundGivesUp() {
        UpdateAttempt attempt = new UpdateRetryPolicy(3, 0).begin(null);
        assertThat(attempt.first().action()).isEqualTo(RetryDecision.Action.DISCOVER);
        assertThat(attempt.afterDiscovery(Optional.empty()).action()).isEqualTo(RetryDecision.Action.DISCOVER);
        assertThat(attempt.afterDiscovery(Optional.empty()).action()).isEqualTo(RetryDecision.Action.DISCOVER);
        assertThat(attempt.afterDiscovery(Optional.empty())).isEqualTo(RetryDecision.giveUp());
        assertThat(attempt.attempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("misuse and invalid settings are rejected")
    void misuseRejected() {
        assertThatThrownBy(() -> new UpdateRetryPolicy(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UpdateRetryPolicy(3, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.begin(0)).isInstanceOf(IllegalArgumentException.class);

        UpdateAttempt attempt = policy.begin(5);
        assertThatThrownBy(() -> attempt.after(AttemptOutcome.accepted(5))).isInstanceOf(IllegalStateException.class);
        attempt.first();
        assertThatThrownBy(attempt::first).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> attempt.afterDiscovery(Optional.of(4))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> attempt.after(AttemptOutcome.accepted(4))).isInstanceOf(IllegalArgumentException.class);
        attempt.after(AttemptOutcome.accepted(5));
        assertThatThrownBy(() -> attempt.after(AttemptOutcome.accepted(5))).isInstanceOf(IllegalStateException.class);
    }
}
