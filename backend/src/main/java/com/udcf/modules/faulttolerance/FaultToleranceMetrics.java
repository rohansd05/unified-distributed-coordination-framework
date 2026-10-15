package com.udcf.modules.faulttolerance;

import com.udcf.core.metrics.MetricNames;
import com.udcf.modules.replication.ConsistencyModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;

/**
 * Experiment 8 meters (R5: {@code node_id} on every meter). Only measured intervals are
 * recorded: an interval that never completed records nothing (R7, never 0).
 *
 * <ul>
 *   <li>{@code distributed_failures_total{node_id}}: primary failures detected, by the failed
 *       primary.</li>
 *   <li>{@code distributed_recovery_duration{node_id, interval}}: a timer per failover interval,
 *       by the failed primary; {@code interval} is {@code detection}, {@code failover},
 *       {@code service_restored}, {@code outage} (when the run restores the service) or
 *       {@code recovery} (when the old primary has demoted and resynchronised).</li>
 * </ul>
 *
 * <p>No other module registers these two names, so their tag keys are only these. Covered by
 * FaultToleranceMetricsTest.</p>
 */
public class FaultToleranceMetrics {

    static final String INTERVAL = "interval";
    static final String DETECTION = "detection";
    static final String FAILOVER = "failover";
    static final String SERVICE_RESTORED = "service_restored";
    static final String OUTAGE = "outage";
    static final String RECOVERY = "recovery";
    // E8c: client updates of the update stream, and the updates lost in a failover.
    static final String MODEL = "model";
    static final String STATUS = "status";
    static final String ACKNOWLEDGED = "acknowledged";
    static final String GAVE_UP = "gave_up";

    private final MeterRegistry registry;

    public FaultToleranceMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /** The current primary {@code nodeId} was detected as failed (once per run). */
    public void failureDetected(int nodeId) {
        registry.counter(MetricNames.FAILURES_TOTAL, MetricNames.NODE_ID, String.valueOf(nodeId)).increment();
    }

    /** A run restored the service: records its detection, failover, service-restored and outage intervals that exist. */
    public void runRestored(FailoverRun run) {
        FailoverMeasurements m = run.measurements();
        record(run.oldPrimaryId(), DETECTION, m.detectionMillis());
        record(run.oldPrimaryId(), FAILOVER, m.failoverMillis());
        record(run.oldPrimaryId(), SERVICE_RESTORED, m.serviceRestoredMillis());
        record(run.oldPrimaryId(), OUTAGE, m.outageMillis());
    }

    /** The old primary of {@code run} demoted and resynchronised: records the recovery interval if it exists. */
    public void oldPrimaryRecovered(FailoverRun run) {
        record(run.oldPrimaryId(), RECOVERY, run.measurements().recoveryMillis());
    }

    /**
     * E8c: one client update ended. {@code distributed_failover_updates_total{node_id, model, status}}:
     * node_id is the primary that accepted it, or {@value MetricNames#CLUSTER_NODE_ID} if it gave up.
     */
    public void updateFinished(UpdateOutcome outcome) {
        String nodeId = outcome.accepted() ? String.valueOf(outcome.nodeId()) : MetricNames.CLUSTER_NODE_ID;
        registry.counter(MetricNames.FAILOVER_UPDATES_TOTAL, MetricNames.NODE_ID, nodeId, MODEL, outcome.model().name(),
                STATUS, outcome.accepted() ? ACKNOWLEDGED : GAVE_UP).increment();
    }

    /**
     * E8c: the data loss of one failover was measured on {@code newPrimaryId}.
     * {@code distributed_failover_lost_updates_total{node_id, model}} grows by the measured counts,
     * 0 included (a measured 0 is real); a loss that was not assessed records nothing.
     */
    public void dataLossMeasured(int newPrimaryId, DataLossReport report) {
        if (!report.assessed()) {
            return;
        }
        String nodeId = String.valueOf(newPrimaryId);
        registry.counter(MetricNames.FAILOVER_LOST_UPDATES_TOTAL, MetricNames.NODE_ID, nodeId, MODEL,
                ConsistencyModel.SYNCHRONOUS.name()).increment(report.lostSynchronous());
        registry.counter(MetricNames.FAILOVER_LOST_UPDATES_TOTAL, MetricNames.NODE_ID, nodeId, MODEL,
                ConsistencyModel.ASYNCHRONOUS.name()).increment(report.lostAsynchronous());
    }

    private void record(int nodeId, String interval, Double millis) {
        if (millis == null) {
            return;
        }
        Timer.builder(MetricNames.RECOVERY_DURATION)
                .description("Experiment 8 failover intervals, by the failed primary")
                .tag(MetricNames.NODE_ID, String.valueOf(nodeId))
                .tag(INTERVAL, interval)
                .register(registry)
                .record(Duration.ofNanos(Math.round(millis * 1_000_000d)));
    }
}
