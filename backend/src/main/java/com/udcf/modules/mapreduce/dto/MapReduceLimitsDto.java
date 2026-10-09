package com.udcf.modules.mapreduce.dto;

/**
 * The configured limits, so the page can check a file before uploading it.
 *
 * <p>No dedicated test: a record; tested through MapReduceControllerTest.</p>
 *
 * @param uploadMaxBytes      largest accepted .txt file in bytes
 * @param requestBodyMaxBytes largest accepted {@code POST /runs} body (Base64 of the cap plus a fixed allowance)
 * @param eventLogMaxBytes    byte cap of the event-log input (the same cap; the newest lines are kept)
 * @param eventLogMaxEvents   events the event-log input can read at most (the event buffer size)
 * @param resultRowsMax       result rows listed in a report; more keys are counted, not listed
 * @param runHistorySize      runs kept, newest first
 * @param taskTimeoutMillis   per-task timeout before the coordinator retries on another worker
 */
public record MapReduceLimitsDto(
        int uploadMaxBytes,
        long requestBodyMaxBytes,
        int eventLogMaxBytes,
        int eventLogMaxEvents,
        int resultRowsMax,
        int runHistorySize,
        long taskTimeoutMillis
) {
}
