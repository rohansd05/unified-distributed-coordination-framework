package com.udcf.integration;

import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.EventDraft;
import com.udcf.modules.mapreduce.InputType;
import com.udcf.modules.mapreduce.MapReduceModule;
import com.udcf.modules.mapreduce.dto.JobReportDto;
import com.udcf.modules.mapreduce.dto.ResultRowDto;
import com.udcf.modules.mapreduce.dto.RunCommand;
import com.udcf.modules.mapreduce.dto.RunDto;
import com.udcf.modules.mapreduce.dto.RunState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Link L5: the live event log is exported from the bus (EventLogExporter) as MapReduce input, and the
 * "event-category-count" job over it counts exactly what the bus held.
 *
 * <p>The event-log input is one snapshot taken while the run starts, and the cluster keeps publishing around
 * it (the election, the run's own events). The bus appends in sequence order under one lock, so the snapshot
 * is always the first N events by sequence since the reset, where N is the run's {@code inputLines}. The test
 * compares the job's counts with that prefix, type by type.</p>
 *
 * <p>Ports 27300-27355: RMI 27300, clock 27310, election 27320, replication 27330, requests 27340,
 * MapReduce 27350.</p>
 */
@TestPropertySource(properties = {
        "udcf.cluster.ports.rmi-base=27300", "udcf.cluster.ports.clock-base=27310",
        "udcf.cluster.ports.election-base=27320", "udcf.cluster.ports.replication-base=27330",
        "udcf.cluster.ports.requests-base=27340", "udcf.cluster.ports.mapreduce-base=27350"})
class L5EventExportToMapReduceIntegrationTest extends IntegrationTestSupport {

    private static final String MARKER = "L5_MARKER";
    private static final int MARKERS = 7;

    @Autowired
    private MapReduceModule mapReduce;

    @Test
    @DisplayName("event-category-count over the live event log equals the bus's own count per event type")
    void jobCountsWhatTheBusHeld() {
        resetCluster();
        for (int i = 0; i < MARKERS; i++) {
            // A test marker (not a module's event), so one count in the result is known in advance.
            bus.publish(EventDraft.of("integration", 0, MARKER, cluster.clusterClock().tick()));
        }

        String runId = mapReduce.startRun(new RunCommand("event-category-count", InputType.EVENT_LOG, null, null)).runId();
        RunDto run = await().atMost(DEADLINE).until(() -> mapReduce.run(runId), r -> r.state() != RunState.RUNNING);

        assertThat(run.state()).as(run.error()).isEqualTo(RunState.COMPLETED);
        JobReportDto report = run.report();
        assertThat(report.inputLinesDropped()).as("nothing left out by the byte cap").isZero();
        assertThat(report.resultsTruncated()).isFalse();
        Map<String, Long> counted = report.results().stream()
                .collect(Collectors.toMap(ResultRowDto::key, ResultRowDto::count));

        Map<String, Long> held = allEvents().stream().limit(report.inputLines())
                .collect(Collectors.groupingBy(ClusterEvent::type, Collectors.counting()));
        assertThat(counted).isEqualTo(held);
        assertThat(counted).containsEntry(MARKER, (long) MARKERS).containsEntry("CLUSTER_RESET", 1L);
        assertThat(counted.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(report.inputLines());
    }
}
