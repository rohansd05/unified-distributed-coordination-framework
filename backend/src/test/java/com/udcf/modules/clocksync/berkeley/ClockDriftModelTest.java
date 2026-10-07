package com.udcf.modules.clocksync.berkeley;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClockDriftModelTest {

    private final Instant baseTime = Instant.parse("2026-10-07T12:00:00Z");

    @Test
    @DisplayName("rejects invalid node IDs")
    void rejectsInvalidNodeId() {
        assertThatThrownBy(() -> new ClockDriftModel(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nodeId must be >= 1");
    }

    @Test
    @DisplayName("initializes with zero offset and zero drift by default")
    void defaultInitialization() {
        ClockDriftModel model = new ClockDriftModel(1, 0L, 0.0, baseTime);

        assertThat(model.nodeId()).isEqualTo(1);
        assertThat(model.currentOffsetMillis(baseTime)).isZero();
        assertThat(model.simulatedTime(baseTime)).isEqualTo(baseTime);
        assertThat(model.driftRateMsPerSec()).isZero();
    }

    @Test
    @DisplayName("simulated time reflects static initial offset")
    void staticInitialOffset() {
        // Node 2 is ahead by 150 ms
        ClockDriftModel model = new ClockDriftModel(2, 150L, 0.0, baseTime);

        assertThat(model.currentOffsetMillis(baseTime)).isEqualTo(150L);
        assertThat(model.simulatedTime(baseTime)).isEqualTo(baseTime.plusMillis(150));

        // 10 seconds later, with 0 drift rate, offset remains strictly 150 ms
        Instant later = baseTime.plusSeconds(10);
        assertThat(model.currentOffsetMillis(later)).isEqualTo(150L);
        assertThat(model.simulatedTime(later)).isEqualTo(later.plusMillis(150));
    }

    @Test
    @DisplayName("simulated time accumulates drift over elapsed time according to drift rate")
    void accumulatesDriftOverTime() {
        // Drift rate: +2.5 ms per second
        ClockDriftModel model = new ClockDriftModel(1, 100L, 2.5, baseTime);

        // At t = 0
        assertThat(model.currentOffsetMillis(baseTime)).isEqualTo(100L);

        // At t = 4 seconds: accumulated drift = 2.5 * 4 = 10 ms -> total offset = 110 ms
        Instant tPlus4 = baseTime.plusSeconds(4);
        assertThat(model.currentOffsetMillis(tPlus4)).isEqualTo(110L);
        assertThat(model.simulatedTime(tPlus4)).isEqualTo(tPlus4.plusMillis(110));

        // Negative drift rate: -1.0 ms per second
        ClockDriftModel negativeDrift = new ClockDriftModel(3, 50L, -1.0, baseTime);
        Instant tPlus10 = baseTime.plusSeconds(10);
        // Accumulated drift = -10 ms -> total offset = 40 ms
        assertThat(negativeDrift.currentOffsetMillis(tPlus10)).isEqualTo(40L);
    }

    @Test
    @DisplayName("applyAdjustment updates base offset and resets drift anchor")
    void applyAdjustmentUpdatesOffset() {
        ClockDriftModel model = new ClockDriftModel(1, 100L, 2.0, baseTime);

        // Advance 5 seconds: offset is 100 + (2.0 * 5) = 110 ms
        Instant tSync = baseTime.plusSeconds(5);
        assertThat(model.currentOffsetMillis(tSync)).isEqualTo(110L);

        // Daemon computes adjustment of -60 ms to bring it toward average (110 - 60 = 50 ms)
        model.applyAdjustment(-60L, tSync);

        // Immediately after adjustment at tSync, offset should be 50 ms
        assertThat(model.currentOffsetMillis(tSync)).isEqualTo(50L);

        // 2 seconds after sync: offset is 50 + (2.0 * 2) = 54 ms
        Instant tAfterSync = tSync.plusSeconds(2);
        assertThat(model.currentOffsetMillis(tAfterSync)).isEqualTo(54L);
    }

    @Test
    @DisplayName("snapshot returns complete state with simulated=true per R7")
    void snapshotContainsSimulatedFlag() {
        ClockDriftModel model = new ClockDriftModel(1, 75L, 1.5, baseTime);
        NodeDriftSnapshot snap = model.snapshot(baseTime);

        assertThat(snap.nodeId()).isEqualTo(1);
        assertThat(snap.offsetMillis()).isEqualTo(75L);
        assertThat(snap.driftRateMsPerSec()).isEqualTo(1.5);
        assertThat(snap.simulatedTime()).isEqualTo(baseTime.plusMillis(75));
        assertThat(snap.simulated()).isTrue();
    }

    @Test
    @DisplayName("reset returns offset and drift rate to zero")
    void resetClearsOffsetAndDrift() {
        ClockDriftModel model = new ClockDriftModel(1, 250L, 5.0, baseTime);
        Instant tLater = baseTime.plusSeconds(10);

        model.reset(tLater);

        assertThat(model.currentOffsetMillis(tLater)).isZero();
        assertThat(model.driftRateMsPerSec()).isZero();
        assertThat(model.simulatedTime(tLater)).isEqualTo(tLater);
    }
}
