package com.udcf.modules.mapreduce.dto;

import com.udcf.modules.mapreduce.InputType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body of {@code POST /api/modules/mapreduce/runs}.
 *
 * <p>No dedicated test: a record; validation is tested in MapReduceControllerTest.</p>
 *
 * @param jobId         a job id from the overview
 * @param inputType     SAMPLE, UPLOAD or EVENT_LOG
 * @param upload        required for UPLOAD, must be absent otherwise
 * @param crashWorkerId optional: a live worker (not the coordinator) that the module crashes
 *                      right after the first task is sent to it; null for a run without a crash
 */
public record RunCommand(
        @NotBlank(message = "jobId is required") String jobId,
        @NotNull(message = "inputType is required") InputType inputType,
        UploadDto upload,
        Integer crashWorkerId
) {
}
