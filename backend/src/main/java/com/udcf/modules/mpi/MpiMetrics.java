package com.udcf.modules.mpi;

import com.udcf.core.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;

/**
 * Experiment 9 meters. {@code node_id} is always the <b>root</b>, which makes every call (as
 * Experiment 1's caller), and the node it called is the separate {@code target_node} tag. Every
 * name always carries the same tag keys:
 * <ul>
 *   <li>{@value MetricNames#MPI_COLLECTIVES_TOTAL} {node_id, operation, outcome}: every collective,
 *       REFUSED included.</li>
 *   <li>{@value MetricNames#MPI_MESSAGES_TOTAL} {node_id, target_node, operation, direction, status}:
 *       every remote transfer (DELIVERED, FAILED, NOT_ATTEMPTED). The root's LOCAL part crosses no
 *       network and is not counted.</li>
 *   <li>{@value MetricNames#MPI_TRANSFER_LATENCY} {node_id, target_node, operation, direction}: the
 *       measured latency of each DELIVERED transfer only; never a time to failure.</li>
 *   <li>{@value MetricNames#MPI_COLLECTIVE_DURATION} {node_id, operation, outcome}: the measured total,
 *       only when a remote transfer was attempted (totalNanos not null).</li>
 * </ul>
 *
 * <p>Plain class, owned by {@link MpiModule}. Covered by MpiMetricsTest.</p>
 */
public class MpiMetrics {

    static final String OPERATION = "operation";
    static final String OUTCOME = "outcome";
    static final String TARGET_NODE = "target_node";
    static final String DIRECTION = "direction";
    static final String STATUS = "status";

    private final MeterRegistry registry;

    public MpiMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /** One finished or refused collective. */
    public void record(CollectiveResult result) {
        Objects.requireNonNull(result, "result must not be null");
        String root = String.valueOf(result.rootNodeId());
        String operation = result.operation().name();
        String outcome = result.outcome().name();
        registry.counter(MetricNames.MPI_COLLECTIVES_TOTAL, MetricNames.NODE_ID, root, OPERATION, operation,
                OUTCOME, outcome).increment();
        for (TransferRecord transfer : result.transfers()) {
            if (transfer.status() == TransferStatus.LOCAL) {
                continue;
            }
            // the node the root called: the receiver of a TO_RANK part, the sender of a FROM_RANK part
            String target = String.valueOf(transfer.direction() == TransferDirection.TO_RANK
                    ? transfer.toNodeId() : transfer.fromNodeId());
            String direction = transfer.direction().name();
            registry.counter(MetricNames.MPI_MESSAGES_TOTAL, MetricNames.NODE_ID, root, TARGET_NODE, target,
                    OPERATION, operation, DIRECTION, direction, STATUS, transfer.status().name()).increment();
            if (transfer.status() == TransferStatus.DELIVERED) {
                Timer.builder(MetricNames.MPI_TRANSFER_LATENCY)
                        .description("Measured latency of a delivered Experiment 9 transfer (lookup and call)")
                        .tag(MetricNames.NODE_ID, root)
                        .tag(TARGET_NODE, target)
                        .tag(OPERATION, operation)
                        .tag(DIRECTION, direction)
                        .register(registry)
                        .record(Duration.ofNanos(transfer.latencyNanos()));
            }
        }
        if (result.totalNanos() != null) {
            Timer.builder(MetricNames.MPI_COLLECTIVE_DURATION)
                    .description("Measured total time of an Experiment 9 collective")
                    .tag(MetricNames.NODE_ID, root)
                    .tag(OPERATION, operation)
                    .tag(OUTCOME, outcome)
                    .register(registry)
                    .record(Duration.ofNanos(result.totalNanos()));
        }
    }
}
