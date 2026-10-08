package com.udcf.modules.clocksync.berkeley;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BerkeleyAveragingCoordinatorTest {

    private final BerkeleyAveragingCoordinator coordinator = new BerkeleyAveragingCoordinator();

    @Test
    @DisplayName("rejects invalid inputs")
    void rejectsInvalidInputs() {
        assertThatThrownBy(() -> coordinator.computeRound(0, List.of(new NodeClockReading(1, 0)), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("daemonNodeId must be >= 1");

        assertThatThrownBy(() -> coordinator.computeRound(1, null, 100))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> coordinator.computeRound(1, List.of(), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("readings must not be empty");

        assertThatThrownBy(() -> coordinator.computeRound(1, List.of(new NodeClockReading(1, 0)), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outlierThresholdMillis must be >= 0");

        assertThatThrownBy(() -> coordinator.computeRound(1, List.of(new NodeClockReading(2, 0)), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must contain a reading for daemon node 1");
    }

    @Test
    @DisplayName("calculates average and adjustments without outliers, reducing spread to zero")
    void calculatesAverageAndAdjustmentsWithoutOutliers() {
        // Daemon is Node 1 (offset = 0 ms)
        // Node 2: offset = +60 ms
        // Node 3: offset = -30 ms
        // Outlier threshold = 500 ms (all within threshold)
        List<NodeClockReading> readings = List.of(
                new NodeClockReading(1, 0L),
                new NodeClockReading(2, 60L),
                new NodeClockReading(3, -30L)
        );

        BerkeleyRoundResult result = coordinator.computeRound(1, readings, 500L);

        // Average offset = (0 + 60 - 30) / 3 = 10 ms
        assertThat(result.averageOffsetMillis()).isEqualTo(10L);
        assertThat(result.participatingNodes()).containsExactly(1, 2, 3);
        assertThat(result.outlierNodes()).isEmpty();
        assertThat(result.spreadBeforeMillis()).isEqualTo(90L); // 60 - (-30) = 90
        assertThat(result.spreadAfterMillis()).isZero();
        assertThat(result.simulated()).isTrue();

        // Adjustments:
        // Node 1: before 0, adjustment +10, after 10
        // Node 2: before 60, adjustment -50, after 10
        // Node 3: before -30, adjustment +40, after 10
        NodeAdjustment adj1 = result.adjustments().stream().filter(a -> a.nodeId() == 1).findFirst().orElseThrow();
        assertThat(adj1.adjustmentMillis()).isEqualTo(10L);
        assertThat(adj1.afterOffsetMillis()).isEqualTo(10L);
        assertThat(adj1.outlier()).isFalse();

        NodeAdjustment adj2 = result.adjustments().stream().filter(a -> a.nodeId() == 2).findFirst().orElseThrow();
        assertThat(adj2.adjustmentMillis()).isEqualTo(-50L);
        assertThat(adj2.afterOffsetMillis()).isEqualTo(10L);
        assertThat(adj2.outlier()).isFalse();

        NodeAdjustment adj3 = result.adjustments().stream().filter(a -> a.nodeId() == 3).findFirst().orElseThrow();
        assertThat(adj3.adjustmentMillis()).isEqualTo(40L);
        assertThat(adj3.afterOffsetMillis()).isEqualTo(10L);
        assertThat(adj3.outlier()).isFalse();
    }

    @Test
    @DisplayName("identifies and discards outliers beyond threshold, protecting average from distortion, while adjusting outlier toward target")
    void discardsOutlierExceedingThreshold() {
        // Daemon is Node 1 (offset = 100 ms)
        // Node 2: offset = 140 ms (diff = +40 ms, within 300 ms threshold)
        // Node 3: offset = 80 ms (diff = -20 ms, within 300 ms threshold)
        // Node 4: offset = 5000 ms (diff = +4900 ms, EXCEEDS 300 ms threshold -> OUTLIER)
        List<NodeClockReading> readings = List.of(
                new NodeClockReading(1, 100L),
                new NodeClockReading(2, 140L),
                new NodeClockReading(3, 80L),
                new NodeClockReading(4, 5000L)
        );

        BerkeleyRoundResult result = coordinator.computeRound(1, readings, 300L);

        // Valid nodes are 1, 2, 3: deltas from daemon (100) are 0, +40, -20.
        // Sum deltas = +20, avgDelta = round(20/3) = 7 ms. Target = 107 ms.
        assertThat(result.participatingNodes()).containsExactly(1, 2, 3);
        assertThat(result.outlierNodes()).containsExactly(4);
        assertThat(result.averageOffsetMillis()).isEqualTo(107L);

        // Outlier Node 4 is excluded from average, but receives adjustment toward consensus (107 - 5000 = -4893)
        NodeAdjustment adj4 = result.adjustments().stream().filter(a -> a.nodeId() == 4).findFirst().orElseThrow();
        assertThat(adj4.outlier()).isTrue();
        assertThat(adj4.adjustmentMillis()).isEqualTo(-4893L);
        assertThat(adj4.afterOffsetMillis()).isEqualTo(107L);

        // Valid nodes converge to target offset (107 ms)
        for (int id : List.of(1, 2, 3)) {
            NodeAdjustment adj = result.adjustments().stream().filter(a -> a.nodeId() == id).findFirst().orElseThrow();
            assertThat(adj.outlier()).isFalse();
            assertThat(adj.afterOffsetMillis()).isEqualTo(107L);
        }

        // Spread across ALL participating nodes (including adjusted outlier) is 0
        assertThat(result.spreadAfterMillis()).isZero();
    }

    @Test
    @DisplayName("applies Cristian RTT compensation when estimating peer clock offsets")
    void appliesRttCompensation() {
        // Daemon is Node 1 (offset = 0 ms, RTT = 0)
        // Node 2: reported offset = 50 ms, RTT = 20.0 ms -> estimated offset = 50 + 10 = 60 ms
        // Node 3: reported offset = -40 ms, RTT = 10.0 ms -> estimated offset = -40 + 5 = -35 ms
        List<NodeClockReading> readings = List.of(
                new NodeClockReading(1, 0L, 0.0),
                new NodeClockReading(2, 50L, 20.0),
                new NodeClockReading(3, -40L, 10.0)
        );

        BerkeleyRoundResult result = coordinator.computeRound(1, readings, 500L);

        // Average = (0 + 60 - 35) / 3 = round(25 / 3) = 8 ms
        assertThat(result.averageOffsetMillis()).isEqualTo(8L);

        NodeAdjustment adj2 = result.adjustments().stream().filter(a -> a.nodeId() == 2).findFirst().orElseThrow();
        assertThat(adj2.rttMillis()).isEqualTo(20.0);
        // Adjustment brings estimated (60 ms) to target (8 ms): adjustment = 8 - 60 = -52 ms
        assertThat(adj2.adjustmentMillis()).isEqualTo(-52L);
        // Node's actual synchronized after offset = target (8 ms)
        assertThat(adj2.afterOffsetMillis()).isEqualTo(8L);
    }

    @Test
    @DisplayName("done when: one Berkeley round visibly reduces the spread between node offsets")
    void visiblyReducesSpread() {
        List<NodeClockReading> readings = List.of(
                new NodeClockReading(1, 0L),
                new NodeClockReading(2, 150L),
                new NodeClockReading(3, -120L)
        );

        BerkeleyRoundResult result = coordinator.computeRound(1, readings, 500L);

        assertThat(result.spreadBeforeMillis()).isEqualTo(270L);
        assertThat(result.spreadAfterMillis()).isEqualTo(0L);
        assertThat(result.spreadAfterMillis()).isLessThan(result.spreadBeforeMillis());
    }

    @Test
    @DisplayName("handles already synchronized nodes with zero adjustments")
    void alreadySynchronizedNodes() {
        List<NodeClockReading> readings = List.of(
                new NodeClockReading(1, 50L),
                new NodeClockReading(2, 50L),
                new NodeClockReading(3, 50L)
        );

        BerkeleyRoundResult result = coordinator.computeRound(1, readings, 100L);

        assertThat(result.spreadBeforeMillis()).isZero();
        assertThat(result.spreadAfterMillis()).isZero();
        assertThat(result.averageOffsetMillis()).isEqualTo(50L);
        for (NodeAdjustment adj : result.adjustments()) {
            assertThat(adj.adjustmentMillis()).isZero();
            assertThat(adj.afterOffsetMillis()).isEqualTo(50L);
        }
    }
}
