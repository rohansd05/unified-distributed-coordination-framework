package com.udcf.modules.mapreduce.dto;

/**
 * One failed attempt of a task.
 *
 * <p>No dedicated test: a record; built and tested by TaskAttemptRecorderTest.</p>
 *
 * @param attempt  1 for the first attempt, 2 for the first retry, and so on
 * @param workerId the worker the attempt was sent to
 * @param reason   why it failed (timeout, refused connection, worker error)
 */
public record FailedAttemptDto(int attempt, int workerId, String reason) {
}
