package com.udcf.modules.clocksync.lamport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClockEventTest {

    private final Instant now = Instant.parse("2026-10-07T12:00:00Z");

    @Test
    @DisplayName("validates constructor parameters")
    void validatesConstructorParameters() {
        assertThatThrownBy(() -> new ClockEvent(0, ClockEventType.LOCAL, 1, 0, -1, "desc", now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nodeId must be >= 1");

        assertThatThrownBy(() -> new ClockEvent(1, null, 1, 0, -1, "desc", now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("type");

        assertThatThrownBy(() -> new ClockEvent(1, ClockEventType.LOCAL, -1, 0, -1, "desc", now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lamportTime must be >= 0");

        assertThatThrownBy(() -> new ClockEvent(1, ClockEventType.LOCAL, 1, -1, -1, "desc", now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("peerId must be >= 0");

        assertThatThrownBy(() -> new ClockEvent(1, ClockEventType.LOCAL, 1, 0, -1, null, now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("description");

        assertThatThrownBy(() -> new ClockEvent(1, ClockEventType.LOCAL, 1, 0, -1, "desc", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("wallTime");
    }

    @Test
    @DisplayName("factory methods create well-formed events and predicates return expected status")
    void factoryMethodsAndPredicatesWork() {
        ClockEvent local = ClockEvent.local(1, 5, "local event", now);
        assertThat(local.isLocal()).isTrue();
        assertThat(local.isSend()).isFalse();
        assertThat(local.isReceive()).isFalse();
        assertThat(local.peerId()).isZero();
        assertThat(local.causedByTime()).isEqualTo(-1L);

        ClockEvent send = ClockEvent.send(1, 6, 2, "send message", now);
        assertThat(send.isSend()).isTrue();
        assertThat(send.isLocal()).isFalse();
        assertThat(send.isReceive()).isFalse();
        assertThat(send.peerId()).isEqualTo(2);

        ClockEvent recv = ClockEvent.receive(2, 7, 1, 6, "recv message", now);
        assertThat(recv.isReceive()).isTrue();
        assertThat(recv.isLocal()).isFalse();
        assertThat(recv.isSend()).isFalse();
        assertThat(recv.peerId()).isEqualTo(1);
        assertThat(recv.causedByTime()).isEqualTo(6L);
    }

    @Test
    @DisplayName("compareTo delegates to Lamport total order")
    void compareToDelegatesToTotalOrder() {
        ClockEvent e1 = ClockEvent.local(1, 10, "event 1", now);
        ClockEvent e2 = ClockEvent.local(2, 10, "event 2", now);
        ClockEvent e3 = ClockEvent.local(1, 11, "event 3", now);

        assertThat(e1.compareTo(e2)).isNegative();
        assertThat(e2.compareTo(e1)).isPositive();
        assertThat(e1.compareTo(e3)).isNegative();
        assertThat(e1.compareTo(e1)).isZero();
    }
}
