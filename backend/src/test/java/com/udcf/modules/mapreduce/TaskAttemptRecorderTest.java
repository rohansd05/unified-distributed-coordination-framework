package com.udcf.modules.mapreduce;

import com.udcf.modules.mapreduce.dto.FailedAttemptDto;
import com.udcf.modules.mapreduce.dto.TaskRowDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** One row per task: the final worker, the attempt count and every failed attempt. */
class TaskAttemptRecorderTest {

    @Test
    @DisplayName("rows: map tasks first, then reduce, each with its final worker, attempts and failed attempts")
    void rows() {
        TaskAttemptRecorder recorder = new TaskAttemptRecorder();
        recorder.onAttempt(TaskType.REDUCE, 0, 1, 3, null);
        recorder.onAttempt(TaskType.MAP, 1, 1, 2, "Connection refused");
        recorder.onAttempt(TaskType.MAP, 1, 2, 3, null);
        recorder.onAttempt(TaskType.MAP, 0, 1, 1, null);

        assertThat(recorder.rows()).containsExactly(
                new TaskRowDto("MAP", 1, true, 1, 1, List.of()),
                new TaskRowDto("MAP", 2, true, 3, 2, List.of(new FailedAttemptDto(1, 2, "Connection refused"))),
                new TaskRowDto("REDUCE", 1, true, 3, 1, List.of()));
        assertThat(recorder.failures()).containsExactly(new TaskAttemptRecorder.Failure(TaskType.MAP, 2));
    }

    @Test
    @DisplayName("a task no attempt finished has no worker and is not completed")
    void neverCompleted() {
        TaskAttemptRecorder recorder = new TaskAttemptRecorder();
        recorder.onAttempt(TaskType.MAP, 0, 1, 1, "timeout");
        recorder.onAttempt(TaskType.MAP, 0, 2, 2, "refused");

        TaskRowDto row = recorder.rows().get(0);
        assertThat(row.completed()).isFalse();
        assertThat(row.workerId()).isNull();
        assertThat(row.attempts()).isEqualTo(2);
        assertThat(row.failedAttempts()).extracting(FailedAttemptDto::attempt).containsExactly(1, 2);
    }

    @Test
    @DisplayName("attempts from many threads at once are all recorded")
    void threadSafe() throws Exception {
        TaskAttemptRecorder recorder = new TaskAttemptRecorder();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < 200; i++) {
                int index = i;
                pool.execute(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    recorder.onAttempt(TaskType.MAP, index, 1, index % 5 + 1, null);
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(recorder.rows()).hasSize(200);
    }
}
