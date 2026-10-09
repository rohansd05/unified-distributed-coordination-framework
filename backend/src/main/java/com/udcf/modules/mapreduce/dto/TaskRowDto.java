package com.udcf.modules.mapreduce.dto;

import java.util.List;

/**
 * One map or reduce task of a run, with every attempt the coordinator made.
 *
 * <p>No dedicated test: a record; built and tested by TaskAttemptRecorderTest.</p>
 *
 * @param taskType       MAP or REDUCE
 * @param taskNumber     1-based number within its stage
 * @param completed      true if an attempt produced the task's result
 * @param workerId       the worker that finally ran it; {@code null} if no attempt succeeded
 * @param attempts       attempts made, at least 1
 * @param failedAttempts the attempts that failed, in order (empty when the first attempt succeeded)
 */
public record TaskRowDto(
        String taskType,
        int taskNumber,
        boolean completed,
        Integer workerId,
        int attempts,
        List<FailedAttemptDto> failedAttempts
) {
}
