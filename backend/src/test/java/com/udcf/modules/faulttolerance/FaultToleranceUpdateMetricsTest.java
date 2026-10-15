package com.udcf.modules.faulttolerance;

import com.udcf.core.metrics.MetricNames;
import com.udcf.modules.replication.ConsistencyModel;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The two E8c meters of {@link FaultToleranceMetrics}. No ports. */
class FaultToleranceUpdateMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FaultToleranceMetrics metrics = new FaultToleranceMetrics(registry);

    private double count(String name, String nodeId, String... tags) {
        var search = registry.find(name).tag(MetricNames.NODE_ID, nodeId);
        for (int i = 0; i < tags.length; i += 2) {
            search = search.tag(tags[i], tags[i + 1]);
        }
        return search.counter() == null ? -1 : search.counter().count();
    }

    @Test
    @DisplayName("an acknowledged update counts on its primary; one that gave up counts on node 0")
    void updatesByStatus() {
        SystemUpdate one = SystemUpdate.numbered(1);
        metrics.updateFinished(UpdateStreamTest.accepted(one, ConsistencyModel.ASYNCHRONOUS));
        metrics.updateFinished(UpdateOutcome.gaveUp(SystemUpdate.numbered(2), ConsistencyModel.SYNCHRONOUS, 50));
        assertThat(count(MetricNames.FAILOVER_UPDATES_TOTAL, "1", "model", "ASYNCHRONOUS", "status", "acknowledged"))
                .isEqualTo(1.0);
        assertThat(count(MetricNames.FAILOVER_UPDATES_TOTAL, "0", "model", "SYNCHRONOUS", "status", "gave_up"))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("lost updates count by model on the new primary; a measured 0 is recorded; an unassessed loss is not")
    void lostUpdates() {
        metrics.dataLossMeasured(2, new DataLossReport(5, 0, List.of(), 0, 0, false, 0, null));
        assertThat(count(MetricNames.FAILOVER_LOST_UPDATES_TOTAL, "2", "model", "SYNCHRONOUS")).isZero();
        metrics.dataLossMeasured(3, new DataLossReport(5, 2, List.of("a", "b"), 0, 2, true, 450, null));
        assertThat(count(MetricNames.FAILOVER_LOST_UPDATES_TOTAL, "3", "model", "ASYNCHRONOUS")).isEqualTo(2.0);
        metrics.dataLossMeasured(4, new DataLossReport(0, null, List.of(), null, null, false, 0,
                DataLossReport.NotAssessed.NOTHING_ACKNOWLEDGED));
        assertThat(registry.find(MetricNames.FAILOVER_LOST_UPDATES_TOTAL).tag(MetricNames.NODE_ID, "4").meters()).isEmpty();
    }

    @Test
    @DisplayName("every meter carries node_id, and reading with find creates none")
    void nodeIdOnEveryMeter() {
        assertThat(registry.find(MetricNames.FAILOVER_UPDATES_TOTAL).meters()).isEmpty();
        assertThat(registry.getMeters()).isEmpty();
        metrics.updateFinished(UpdateStreamTest.accepted(SystemUpdate.numbered(1), ConsistencyModel.SYNCHRONOUS));
        metrics.dataLossMeasured(2, new DataLossReport(1, 0, List.of(), 0, 0, false, 0, null));
        assertThat(registry.getMeters()).hasSize(3)
                .allSatisfy((Meter m) -> assertThat(m.getId().getTag(MetricNames.NODE_ID)).isNotNull());
    }
}
