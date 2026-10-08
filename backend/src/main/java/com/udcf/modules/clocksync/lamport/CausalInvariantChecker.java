package com.udcf.modules.clocksync.lamport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Proves that causal invariants held across an execution history, rather than merely
 * asserting that they did.
 *
 * <p>Verifies two fundamental distributed system invariants:</p>
 * <ol>
 *   <li><b>Send-Receive Causality (Lamport Rule 3):</b> For every {@link ClockEventType#RECV}
 *       event, the resulting local Lamport timestamp must be strictly greater than the
 *       timestamp carried on the message ({@code causedByTime}). Any receive where
 *       {@code lamportTime <= causedByTime} indicates an ordering violation or lost update.</li>
 *   <li><b>Local Process Monotonicity:</b> On any single node, successive events in timeline
 *       order must exhibit strictly monotonically increasing Lamport timestamps
 *       ({@code e_{i+1}.lamportTime > e_i.lamportTime}).</li>
 * </ol>
 */
public class CausalInvariantChecker {

    /**
     * Checks all causal invariants across the supplied event list.
     *
     * @param events list of events to verify (in arrival or execution order)
     * @return structured verification result
     */
    public CausalVerificationResult verify(List<ClockEvent> events) {
        Objects.requireNonNull(events, "events must not be null");

        List<CausalViolation> violations = new ArrayList<>();
        int receiveChecked = 0;
        Map<Integer, Long> lastNodeTime = new HashMap<>();

        for (ClockEvent e : events) {
            // Check 1: Local process monotonicity
            Long prevTime = lastNodeTime.get(e.nodeId());
            if (prevTime != null && e.lamportTime() <= prevTime) {
                violations.add(new CausalViolation(
                        e.nodeId(),
                        CausalViolation.ViolationType.LOCAL_MONOTONICITY,
                        e.lamportTime(),
                        prevTime,
                        e.peerId(),
                        String.format("Non-monotonic timestamp on Node %d: event '%s' (L=%d) is not > previous (L=%d)",
                                e.nodeId(), e.description(), e.lamportTime(), prevTime)
                ));
            }
            lastNodeTime.put(e.nodeId(), e.lamportTime());

            // Check 2: Send-Receive causal order
            if (e.isReceive()) {
                receiveChecked++;
                if (e.lamportTime() <= e.causedByTime()) {
                    violations.add(new CausalViolation(
                            e.nodeId(),
                            CausalViolation.ViolationType.RECEIVE_ORDER,
                            e.lamportTime(),
                            e.causedByTime(),
                            e.peerId(),
                            String.format("Causal violation on Node %d: received message from Node %d has local L=%d which is not > sent L=%d",
                                    e.nodeId(), e.peerId(), e.lamportTime(), e.causedByTime())
                    ));
                }
            }
        }

        boolean passed = violations.isEmpty();
        return new CausalVerificationResult(
                events.size(),
                receiveChecked,
                violations.size(),
                violations,
                passed
        );
    }

    /**
     * Convenience method to verify all events in a {@link ClockEventLog}.
     */
    public CausalVerificationResult verify(ClockEventLog log) {
        Objects.requireNonNull(log, "log must not be null");
        return verify(log.snapshot());
    }
}
