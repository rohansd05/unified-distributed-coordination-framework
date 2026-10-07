package com.udcf.modules.loadbalancing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DispatchResultTest {

    @Test
    @DisplayName("a served request on its first attempt is not rerouted")
    void servedFirstTime() {
        DispatchResult r = new DispatchResult(1, 2, 12.5d, true, 1);

        assertThat(r.nodeId()).isEqualTo(2);
        assertThat(r.latencyMillis()).isEqualTo(12.5d);
        assertThat(r.rerouted()).isFalse();
    }

    @Test
    @DisplayName("rerouted is derived from attempts, for served and failed requests alike")
    void reroutedIsDerived() {
        assertThat(new DispatchResult(1, 3, 1d, true, 2).rerouted()).isTrue();
        assertThat(new DispatchResult(1, 0, 1d, false, 3).rerouted()).isTrue();
        // Legacy flagged this one as rerouted although nothing was retried.
        assertThat(new DispatchResult(1, 0, 1d, false, 1).rerouted()).isFalse();
    }

    @Test
    @DisplayName("a request no worker served has node 0 and may have 0 attempts")
    void failedWithoutAttempts() {
        DispatchResult r = new DispatchResult(7, 0, 0d, false, 0);

        assertThat(r.succeeded()).isFalse();
        assertThat(r.attempts()).isZero();
    }

    @Test
    @DisplayName("rejects invalid values")
    void validation() {
        assertThatThrownBy(() -> new DispatchResult(0, 1, 1d, true, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requestId");
        assertThatThrownBy(() -> new DispatchResult(1, 1, -0.1d, true, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("latencyMillis");
        assertThatThrownBy(() -> new DispatchResult(1, 1, Double.NaN, true, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("latencyMillis");
        assertThatThrownBy(() -> new DispatchResult(1, 1, Double.POSITIVE_INFINITY, true, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("latencyMillis");
        assertThatThrownBy(() -> new DispatchResult(1, 0, 1d, false, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attempts");
        assertThatThrownBy(() -> new DispatchResult(1, 0, 1d, true, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nodeId");
        assertThatThrownBy(() -> new DispatchResult(1, 2, 1d, true, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attempts");
        assertThatThrownBy(() -> new DispatchResult(1, 3, 1d, false, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nodeId 0");
    }
}
