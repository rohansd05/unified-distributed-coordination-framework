package com.udcf.modules.clocksync.lamport;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Result of executing {@link CausalInvariantChecker} against an execution history.
 *
 * @param totalEventsChecked   total number of events examined
 * @param receiveEventsChecked number of message receive events checked for Rule 3
 * @param violationsCount      number of violations detected
 * @param violations           unmodifiable list of violation details
 * @param passed               true if zero violations were detected
 */
public record CausalVerificationResult(
        int totalEventsChecked,
        int receiveEventsChecked,
        int violationsCount,
        List<CausalViolation> violations,
        boolean passed
) {

    public CausalVerificationResult {
        violations = violations == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(violations);
    }

    public String summary() {
        if (passed) {
            return String.format(
                    "PASS - Causal invariant preserved across all %d events (%d receive events checked, 0 violations).",
                    totalEventsChecked, receiveEventsChecked);
        } else {
            return String.format(
                    "FAIL - Causal invariant violated: %d violation(s) found across %d events (%d receive events checked).",
                    violationsCount, totalEventsChecked, receiveEventsChecked);
        }
    }
}
