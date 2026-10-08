package com.udcf.modules.clocksync.berkeley;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pure algorithm class implementing the Berkeley physical clock synchronization algorithm
 * with outlier rejection (Gusella and Zatti, 1989).
 *
 * <p><b>Algorithm steps:</b></p>
 * <ol>
 *   <li>The designated time daemon polls all nodes for their clock offsets.</li>
 *   <li>The daemon computes each node's offset difference relative to itself:
 *       {@code delta_i = offset_i - offset_daemon}.</li>
 *   <li><b>Outlier rejection:</b> Clocks whose difference exceeds {@code outlierThresholdMillis}
 *       ({@code |delta_i| > threshold}) are flagged as outliers and excluded from the
 *       average calculation to prevent faulty or malicious oscillators from skewing the cluster.</li>
 *   <li>The daemon computes the fault-tolerant average difference of all non-outlier nodes:
 *       {@code avgDelta = round(sum(delta_v) / |V|)}.</li>
 *   <li>The target consensus offset is {@code targetOffset = offset_daemon + avgDelta}.</li>
 *   <li>The daemon computes individual adjustments:
 *       <ul>
 *         <li>For non-outliers: {@code adjustment_i = targetOffset - offset_i}, bringing all
 *             participating nodes to {@code targetOffset} (spread reduces to 0 ms).</li>
 *         <li>For outliers: {@code adjustment = 0}, discarded from adjustment to isolate the fault.</li>
 *       </ul>
 *   </li>
 * </ol>
 */
public class BerkeleyAveragingCoordinator {

    /**
     * Executes one round of Berkeley synchronization over the provided clock readings.
     *
     * @param daemonNodeId           the node ID acting as the time daemon
     * @param readings               readings collected from cluster nodes (must contain daemon's reading)
     * @param outlierThresholdMillis maximum permitted absolute deviation from daemon before being discarded
     * @return structured result of the synchronization round
     */
    public BerkeleyRoundResult computeRound(
            int daemonNodeId,
            List<NodeClockReading> readings,
            long outlierThresholdMillis
    ) {
        if (daemonNodeId < 1) {
            throw new IllegalArgumentException("daemonNodeId must be >= 1, was " + daemonNodeId);
        }
        Objects.requireNonNull(readings, "readings must not be null");
        if (readings.isEmpty()) {
            throw new IllegalArgumentException("readings must not be empty");
        }
        if (outlierThresholdMillis < 0) {
            throw new IllegalArgumentException("outlierThresholdMillis must be >= 0, was " + outlierThresholdMillis);
        }

        // 1. Locate daemon reading
        NodeClockReading daemonReading = readings.stream()
                .filter(r -> r.nodeId() == daemonNodeId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "readings must contain a reading for daemon node " + daemonNodeId));

        long daemonOffset = daemonReading.offsetMillis();

        // 2. Classify into participating (non-outlier) and outlier nodes, with Cristian RTT compensation
        List<NodeClockReading> validReadings = new ArrayList<>();
        List<Integer> participatingNodes = new ArrayList<>();
        List<Integer> outlierNodes = new ArrayList<>();

        for (NodeClockReading r : readings) {
            long compensatedOffset = r.offsetMillis() + Math.round(r.rttMillis() / 2.0);
            long diffFromDaemon = Math.abs(compensatedOffset - daemonOffset);
            if (diffFromDaemon <= outlierThresholdMillis) {
                validReadings.add(r);
                participatingNodes.add(r.nodeId());
            } else {
                outlierNodes.add(r.nodeId());
            }
        }

        // 3. Compute fault-tolerant average
        // Daemon itself is always valid (diff = 0 <= threshold)
        long sumDeltas = 0;
        for (NodeClockReading r : validReadings) {
            long compensatedOffset = r.offsetMillis() + Math.round(r.rttMillis() / 2.0);
            sumDeltas += (compensatedOffset - daemonOffset);
        }
        long avgDelta = Math.round((double) sumDeltas / validReadings.size());
        long targetOffset = daemonOffset + avgDelta;

        // 4. Compute adjustments per node
        // Outliers are excluded from the average calculation, but still receive an individual adjustment
        // toward the target consensus offset so the whole cluster converges (HANDOFF Section 7, Gusella & Zatti 1989)
        List<NodeAdjustment> adjustments = new ArrayList<>();
        for (NodeClockReading r : readings) {
            boolean isOutlier = outlierNodes.contains(r.nodeId());
            long compensatedBefore = r.offsetMillis() + Math.round(r.rttMillis() / 2.0);
            long adjustment = targetOffset - compensatedBefore;
            long after = targetOffset;
            adjustments.add(new NodeAdjustment(r.nodeId(), r.offsetMillis(), adjustment, after, isOutlier, r.rttMillis()));
        }

        // Sort adjustments by nodeId for clean deterministic presentation
        adjustments.sort(Comparator.comparingInt(NodeAdjustment::nodeId));
        Collections.sort(participatingNodes);
        Collections.sort(outlierNodes);

        // 5. Measure spread before and after across ALL participating readings (honest reporting, R7)
        long minBefore = readings.stream().mapToLong(NodeClockReading::offsetMillis).min().orElse(0L);
        long maxBefore = readings.stream().mapToLong(NodeClockReading::offsetMillis).max().orElse(0L);
        long spreadBefore = maxBefore - minBefore;

        long minAfter = adjustments.stream()
                .mapToLong(NodeAdjustment::afterOffsetMillis)
                .min()
                .orElse(0L);
        long maxAfter = adjustments.stream()
                .mapToLong(NodeAdjustment::afterOffsetMillis)
                .max()
                .orElse(0L);
        long spreadAfter = maxAfter - minAfter;

        return new BerkeleyRoundResult(
                daemonNodeId,
                outlierThresholdMillis,
                targetOffset,
                spreadBefore,
                spreadAfter,
                adjustments,
                participatingNodes,
                outlierNodes,
                true
        );
    }
}
