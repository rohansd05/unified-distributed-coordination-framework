package com.udcf.modules.clocksync.lamport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CausalInvariantCheckerTest {

    private final Instant now = Instant.parse("2026-10-07T12:00:00Z");
    private final CausalInvariantChecker checker = new CausalInvariantChecker();

    @Test
    @DisplayName("rejects null input")
    void rejectsNullInput() {
        assertThatThrownBy(() -> checker.verify((List<ClockEvent>) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> checker.verify((ClockEventLog) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("empty event list passes with zero violations")
    void emptyEventsPasses() {
        CausalVerificationResult result = checker.verify(List.of());

        assertThat(result.passed()).isTrue();
        assertThat(result.totalEventsChecked()).isZero();
        assertThat(result.receiveEventsChecked()).isZero();
        assertThat(result.violationsCount()).isZero();
        assertThat(result.violations()).isEmpty();
        assertThat(result.summary()).contains("PASS");
    }

    @Test
    @DisplayName("valid Lamport sequence passes verification with zero violations")
    void validLamportSequencePasses() {
        // Node 1: local (L=1), send to Node 2 (L=2)
        // Node 2: receive from Node 1 (L=max(0, 2)+1 = 3), send to Node 3 (L=4)
        // Node 3: receive from Node 2 (L=max(0, 4)+1 = 5)
        ClockEvent e1 = ClockEvent.local(1, 1, "local", now);
        ClockEvent e2 = ClockEvent.send(1, 2, 2, "send to N2", now);
        ClockEvent e3 = ClockEvent.receive(2, 3, 1, 2, "recv from N1", now);
        ClockEvent e4 = ClockEvent.send(2, 4, 3, "send to N3", now);
        ClockEvent e5 = ClockEvent.receive(3, 5, 2, 4, "recv from N2", now);

        CausalVerificationResult result = checker.verify(List.of(e1, e2, e3, e4, e5));

        assertThat(result.passed()).isTrue();
        assertThat(result.totalEventsChecked()).isEqualTo(5);
        assertThat(result.receiveEventsChecked()).isEqualTo(2);
        assertThat(result.violationsCount()).isZero();
        assertThat(result.violations()).isEmpty();
        assertThat(result.summary()).contains("PASS");
    }

    @Test
    @DisplayName("detects violation when receive timestamp is less than or equal to sent timestamp (Rule 3)")
    void detectsReceiveViolationWhenEqualOrLess() {
        ClockEvent send = ClockEvent.send(1, 5, 2, "send message", now);
        // Faulty receive: local time is 5, but message carried 5 (must be strictly > 5)
        ClockEvent faultyRecv = ClockEvent.receive(2, 5, 1, 5, "faulty receive", now);

        CausalVerificationResult result = checker.verify(List.of(send, faultyRecv));

        assertThat(result.passed()).isFalse();
        assertThat(result.violationsCount()).isEqualTo(1);
        assertThat(result.violations()).hasSize(1);
        CausalViolation v = result.violations().get(0);
        assertThat(v.nodeId()).isEqualTo(2);
        assertThat(v.type()).isEqualTo(CausalViolation.ViolationType.RECEIVE_ORDER);
        assertThat(v.actualLamportTime()).isEqualTo(5L);
        assertThat(v.expectedRelationTime()).isEqualTo(5L);
        assertThat(v.peerId()).isEqualTo(1);
        assertThat(result.summary()).contains("FAIL");
    }

    @Test
    @DisplayName("detects violation when receive timestamp is strictly less than sent timestamp")
    void detectsReceiveViolationWhenStrictlyLess() {
        ClockEvent send = ClockEvent.send(1, 10, 2, "send message", now);
        // Broken receive: local time jumped backwards or failed to advance past remote
        ClockEvent faultyRecv = ClockEvent.receive(2, 8, 1, 10, "broken receive", now);

        CausalVerificationResult result = checker.verify(List.of(send, faultyRecv));

        assertThat(result.passed()).isFalse();
        assertThat(result.violationsCount()).isEqualTo(1);
        CausalViolation v = result.violations().get(0);
        assertThat(v.type()).isEqualTo(CausalViolation.ViolationType.RECEIVE_ORDER);
        assertThat(v.actualLamportTime()).isEqualTo(8L);
        assertThat(v.expectedRelationTime()).isEqualTo(10L);
    }

    @Test
    @DisplayName("detects local monotonicity violation when successive events on same node do not increase")
    void detectsLocalMonotonicityViolation() {
        ClockEvent e1 = ClockEvent.local(1, 5, "event 1", now);
        ClockEvent e2 = ClockEvent.local(1, 5, "event 2 with same Lamport time", now);

        CausalVerificationResult result = checker.verify(List.of(e1, e2));

        assertThat(result.passed()).isFalse();
        assertThat(result.violationsCount()).isEqualTo(1);
        CausalViolation v = result.violations().get(0);
        assertThat(v.nodeId()).isEqualTo(1);
        assertThat(v.type()).isEqualTo(CausalViolation.ViolationType.LOCAL_MONOTONICITY);
        assertThat(v.actualLamportTime()).isEqualTo(5L);
        assertThat(v.expectedRelationTime()).isEqualTo(5L);
    }

    @Test
    @DisplayName("detects both local monotonicity and receive violations in one run")
    void detectsMultipleViolations() {
        ClockEvent e1 = ClockEvent.local(1, 5, "event 1", now);
        ClockEvent e2 = ClockEvent.local(1, 4, "event 2 backwards", now);
        ClockEvent e3 = ClockEvent.receive(2, 3, 1, 10, "receive with L < send", now);

        CausalVerificationResult result = checker.verify(List.of(e1, e2, e3));

        assertThat(result.passed()).isFalse();
        assertThat(result.violationsCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("verifies directly from ClockEventLog")
    void verifiesDirectlyFromClockEventLog() {
        ClockEventLog log = new ClockEventLog();
        log.record(ClockEvent.send(1, 2, 2, "send", now));
        log.record(ClockEvent.receive(2, 3, 1, 2, "receive", now));

        CausalVerificationResult result = checker.verify(log);
        assertThat(result.passed()).isTrue();
        assertThat(result.totalEventsChecked()).isEqualTo(2);
        assertThat(result.receiveEventsChecked()).isEqualTo(1);
    }
}
