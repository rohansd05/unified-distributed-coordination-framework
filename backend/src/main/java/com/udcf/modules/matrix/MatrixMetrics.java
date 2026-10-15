package com.udcf.modules.matrix;

import com.udcf.core.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;

/**
 * Experiment 10 meters. No tag carries a size, run id or seed, so every name has a small, fixed set of series,
 * and no gauge exists (an unknown value is never recorded, never 0):
 * <ul>
 *   <li>{@value MetricNames#MATRIX_RUNS_TOTAL} {node_id = root, outcome}: every run, REFUSED included.</li>
 *   <li>{@value MetricNames#MATRIX_EXECUTION_DURATION} {node_id = root, outcome}: the measured parallel time,
 *       only when it is known (not for REFUSED).</li>
 *   <li>{@value MetricNames#MATRIX_COMPUTE_DURATION} {node_id = the computing node}: each rank's multiply,
 *       measured on the node; DONE computes only.</li>
 *   <li>{@value MetricNames#MATRIX_COMMUNICATION_DURATION} {node_id = root, phase}: the measured scatter,
 *       broadcast and gather times (scatter and broadcast include the root's sizing and checksum work), each
 *       only when known.</li>
 * </ul>
 *
 * <p>Plain class, owned by {@link MatrixModule}. Covered by MatrixMetricsTest.</p>
 */
public class MatrixMetrics {

    static final String OUTCOME = "outcome";
    static final String PHASE = "phase";

    private final MeterRegistry registry;

    public MatrixMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /** One finished or refused run. */
    public void record(MatrixRunResult result) {
        Objects.requireNonNull(result, "result must not be null");
        String root = String.valueOf(result.rootNodeId());
        String outcome = result.outcome().name();
        registry.counter(MetricNames.MATRIX_RUNS_TOTAL, MetricNames.NODE_ID, root, OUTCOME, outcome).increment();
        MatrixTiming timing = result.timing();
        if (timing == null) {
            return;
        }
        if (timing.parallelTotalNanos() != null) {
            Timer.builder(MetricNames.MATRIX_EXECUTION_DURATION)
                    .description("Measured parallel time of an Experiment 10 run, on the root")
                    .tag(MetricNames.NODE_ID, root)
                    .tag(OUTCOME, outcome)
                    .register(registry)
                    .record(Duration.ofNanos(timing.parallelTotalNanos()));
        }
        for (ComputeRecord compute : result.computes()) {
            if (compute.computeNanos() != null) {
                Timer.builder(MetricNames.MATRIX_COMPUTE_DURATION)
                        .description("One rank's multiply in an Experiment 10 run, measured on the node")
                        .tag(MetricNames.NODE_ID, String.valueOf(compute.nodeId()))
                        .register(registry)
                        .record(Duration.ofNanos(compute.computeNanos()));
            }
        }
        communication(root, "SCATTER", timing.scatterNanos());
        communication(root, "BROADCAST", timing.broadcastNanos());
        communication(root, "GATHER", timing.gatherNanos());
    }

    private void communication(String root, String phase, Long nanos) {
        if (nanos == null) {
            return;
        }
        Timer.builder(MetricNames.MATRIX_COMMUNICATION_DURATION)
                .description("Measured time of a phase of an Experiment 10 run, on the root (scatter and broadcast include "
                        + "the root's sizing and checksum work)")
                .tag(MetricNames.NODE_ID, root)
                .tag(PHASE, phase)
                .register(registry)
                .record(Duration.ofNanos(nanos));
    }
}
