package com.udcf.modules.faulttolerance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Each interval from its two instants; null whenever either is missing, never 0. */
class FailoverMeasurementsTest {

    private static final long MS = 1_000_000L;

    @Test
    @DisplayName("every interval is computed from its own two instants")
    void everyInterval() {
        FailoverMeasurements m = run(1_000L, 3_600L, 3_850L, 3_870L, 9_000L, 9_100L, 9_450L).measurements();

        assertThat(m.detectionMillis()).isEqualTo(2_600.0);
        assertThat(m.failoverMillis()).isEqualTo(250.0);
        assertThat(m.serviceRestoredMillis()).isEqualTo(20.0);
        assertThat(m.outageMillis()).isEqualTo(2_870.0);
        assertThat(m.recoveryMillis()).isEqualTo(450.0);
    }

    @Test
    @DisplayName("sub-millisecond precision is kept, not rounded")
    void keepsFractions() {
        FailoverRun run = new FailoverRun(1, 5, 2, 0L, InstantSource.ACTION, 1_234_567L, 3, null, null, null,
                null, null, null, null, null, FailoverRun.Outcome.IN_PROGRESS);
        assertThat(run.measurements().detectionMillis()).isEqualTo(1.234567);
    }

    @Test
    @DisplayName("never detected: detection and failover are null, never 0")
    void neverDetected() {
        FailoverMeasurements m = run(1_000L, null, null, null, null, null, null).measurements();

        assertThat(m.detectionMillis()).isNull();
        assertThat(m.failoverMillis()).isNull();
        assertThat(m.serviceRestoredMillis()).isNull();
        assertThat(m.outageMillis()).isNull();
        assertThat(m.recoveryMillis()).isNull();
    }

    @Test
    @DisplayName("promoted but never restored: service restored and outage are null")
    void neverRestored() {
        FailoverMeasurements m = run(1_000L, 3_500L, 3_700L, null, null, null, null).measurements();

        assertThat(m.detectionMillis()).isEqualTo(2_500.0);
        assertThat(m.failoverMillis()).isEqualTo(200.0);
        assertThat(m.serviceRestoredMillis()).isNull();
        assertThat(m.outageMillis()).isNull();
    }

    @Test
    @DisplayName("no crash recorded: detection and outage are null though the rest is measured")
    void noCrashRecorded() {
        FailoverMeasurements m = run(null, 3_500L, 3_700L, 3_750L, null, null, null).measurements();

        assertThat(m.detectionMillis()).isNull();
        assertThat(m.outageMillis()).isNull();
        assertThat(m.failoverMillis()).isEqualTo(200.0);
        assertThat(m.serviceRestoredMillis()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("outage comes from the crash and the first write directly, not from a sum")
    void outageIsDirect() {
        // Promoted without a detection (detection and failover null), yet the outage is measured.
        FailoverMeasurements m = run(1_000L, null, 3_700L, 3_750L, null, null, null).measurements();

        assertThat(m.detectionMillis()).isNull();
        assertThat(m.failoverMillis()).isNull();
        assertThat(m.outageMillis()).isEqualTo(2_750.0);
    }

    @Test
    @DisplayName("recovery is null until both demotion and resync are recorded, and ends at the later one")
    void recoveryEndsAtLaterOfDemotionAndResync() {
        assertThat(run(1_000L, 3_500L, 3_700L, 3_750L, 9_000L, 9_100L, null).measurements().recoveryMillis()).isNull();
        assertThat(run(1_000L, 3_500L, 3_700L, 3_750L, 9_000L, null, 9_100L).measurements().recoveryMillis()).isNull();
        assertThat(run(1_000L, 3_500L, 3_700L, 3_750L, 9_000L, 9_300L, 9_100L).measurements().recoveryMillis())
                .isEqualTo(300.0);
        assertThat(run(1_000L, 3_500L, 3_700L, 3_750L, null, 9_300L, 9_100L).measurements().recoveryMillis()).isNull();
    }

    @Test
    @DisplayName("a negative interval is never produced")
    void negativeIntervalRejected() {
        assertThatThrownBy(() -> run(4_000L, 3_500L, null, null, null, null, null).measurements())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("detection");
        assertThatThrownBy(() -> new FailoverMeasurements(-1.0, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FailoverMeasurements(null, Double.NaN, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A run of old primary 5 (epoch 2) replaced by node 4 (epoch 3); instants in milliseconds, null if absent. */
    private static FailoverRun run(Long crash, Long detected, Long promoted, Long restored, Long recovered,
                                   Long demoted, Long resynced) {
        boolean chosen = promoted != null;
        return new FailoverRun(1, 5, 2, nanos(crash), crash == null ? null : InstantSource.ACTION, nanos(detected),
                detected == null ? null : 3, chosen ? nanos(promoted) : null, chosen ? 4 : null, chosen ? 3L : null,
                nanos(promoted), nanos(restored), nanos(recovered), nanos(demoted), nanos(resynced),
                restored == null ? FailoverRun.Outcome.IN_PROGRESS : FailoverRun.Outcome.RESTORED);
    }

    private static Long nanos(Long millis) {
        return millis == null ? null : millis * MS;
    }
}
