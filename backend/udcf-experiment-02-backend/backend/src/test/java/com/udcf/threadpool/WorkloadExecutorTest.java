package com.udcf.threadpool;

import com.udcf.model.WorkloadType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkloadExecutorTest {

    private final WorkloadExecutor executor = new WorkloadExecutor();

    @Test
    @DisplayName("CPU workload returns a deterministic digest for the same input")
    void cpuWorkloadIsDeterministic() throws InterruptedException {
        String first = executor.execute(WorkloadType.CPU_HASH, 5);
        String second = executor.execute(WorkloadType.CPU_HASH, 5);

        assertThat(first).startsWith("hash=");
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("larger payloads take measurably longer, proving the work is real")
    void largerPayloadsCostMore() throws InterruptedException {
        long smallStart = System.nanoTime();
        executor.execute(WorkloadType.CPU_HASH, 5);
        long smallCost = System.nanoTime() - smallStart;

        long largeStart = System.nanoTime();
        executor.execute(WorkloadType.CPU_HASH, 200);
        long largeCost = System.nanoTime() - largeStart;

        assertThat(largeCost).isGreaterThan(smallCost);
    }

    @Test
    @DisplayName("IO workload waits and reports how long it waited")
    void ioWorkloadWaits() throws InterruptedException {
        long start = System.nanoTime();
        String summary = executor.execute(WorkloadType.IO_SIMULATED, 25);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(summary).startsWith("waited=").endsWith("ms");
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("mixed workload reports both phases")
    void mixedWorkloadReportsBothPhases() throws InterruptedException {
        String summary = executor.execute(WorkloadType.MIXED, 20);

        assertThat(summary).contains("hash=").contains("waited=");
    }

    @Test
    @DisplayName("guards against a zero or negative payload size")
    void guardsAgainstNonPositivePayload() throws InterruptedException {
        assertThat(executor.execute(WorkloadType.CPU_HASH, 0)).startsWith("hash=");
        assertThat(executor.execute(WorkloadType.CPU_HASH, -10)).startsWith("hash=");
    }
}
