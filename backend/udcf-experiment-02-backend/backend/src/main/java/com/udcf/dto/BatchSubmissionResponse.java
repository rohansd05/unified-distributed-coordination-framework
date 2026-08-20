package com.udcf.dto;

import java.util.List;

/**
 * Returned immediately after a batch is handed to the executor.
 *
 * <p>Submission is asynchronous on purpose: the caller gets the accepted and rejected
 * counts straight away, and the dashboard then watches the requests drain through the
 * pool over the WebSocket/poll cycle. That is what makes the multithreading visible.</p>
 *
 * <p>No dedicated test file: behaviourless data carrier, asserted via the controller
 * and service tests that construct it.</p>
 */
public record BatchSubmissionResponse(
        int requested,
        int accepted,
        int rejected,
        List<String> requestIds
) {
}
