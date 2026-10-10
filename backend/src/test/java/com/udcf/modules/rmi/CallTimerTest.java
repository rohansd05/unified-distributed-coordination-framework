package com.udcf.modules.rmi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallTimerTest {

    @Test
    @DisplayName("a returning action is timed between the two clock readings")
    void timesSuccess() {
        CallTimer timer = new CallTimer(new ScriptedNanoClock(100, 350));

        CallTimer.Measurement<String> measured = timer.measure(() -> "done");

        assertThat(measured.value()).isEqualTo("done");
        assertThat(measured.failure()).isNull();
        assertThat(measured.failed()).isFalse();
        assertThat(measured.elapsedNanos()).isEqualTo(250);
    }

    @Test
    @DisplayName("a throwing action is timed too, and its exception is captured")
    void timesFailure() {
        CallTimer timer = new CallTimer(new ScriptedNanoClock(1_000, 4_000));
        IOException boom = new IOException("refused");

        CallTimer.Measurement<String> measured = timer.measure(() -> {
            throw boom;
        });

        assertThat(measured.failed()).isTrue();
        assertThat(measured.failure()).isSameAs(boom);
        assertThat(measured.value()).isNull();
        assertThat(measured.elapsedNanos()).isEqualTo(3_000);
    }

    @Test
    @DisplayName("a clock that goes backwards is an error, never a clamped 0")
    void backwardsClockFails() {
        CallTimer timer = new CallTimer(new ScriptedNanoClock(500, 499));

        assertThatThrownBy(() -> timer.measure(() -> "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("time source went backwards by 1 ns");
    }

    @Test
    @DisplayName("an Error propagates; null action, value-and-failure and negative elapsed are rejected")
    void validation() {
        CallTimer timer = new CallTimer(new ScriptedNanoClock(0));
        assertThatThrownBy(() -> timer.measure(() -> {
            throw new AssertionError("not captured");
        })).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> timer.measure(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CallTimer(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CallTimer.Measurement<>("v", new IOException(), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallTimer.Measurement<>("v", null, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
