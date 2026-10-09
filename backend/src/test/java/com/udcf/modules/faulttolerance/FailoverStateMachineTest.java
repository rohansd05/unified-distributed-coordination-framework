package com.udcf.modules.faulttolerance;

import com.udcf.modules.faulttolerance.FailoverStateMachine.EventResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The failover state machine with injected instants: no clock, no threads. */
class FailoverStateMachineTest {

    private static final long MS = 1_000_000L;

    private final FailoverStateMachine machine = new FailoverStateMachine(5);

    /** Node 5 is primary at epoch 2 (the first appointment, a handover with no run). */
    @BeforeEach
    void appointInitialPrimary() {
        assertThat(machine.onPromotionChosen(new Promotion(5, 2, null, 0))).isEqualTo(EventResult.APPLIED);
        assertThat(machine.onPromoted(5, 2, MS)).isEqualTo(EventResult.APPLIED);
    }

    @Test
    @DisplayName("a full failover passes steady, suspected, promoting and restored, recording every instant")
    void fullFailover() {
        assertThat(machine.onPrimaryCrashed(5, InstantSource.ACTION, 1_000 * MS)).isEqualTo(EventResult.APPLIED);
        assertThat(machine.phase()).isEqualTo(FailoverPhase.STEADY);
        assertThat(machine.onSuspected(3, 5, 3_500 * MS)).isEqualTo(EventResult.APPLIED);
        assertThat(machine.phase()).isEqualTo(FailoverPhase.SUSPECTED);
        assertThat(machine.onPromotionChosen(new Promotion(4, 3, 5, 3_600 * MS))).isEqualTo(EventResult.APPLIED);
        assertThat(machine.phase()).isEqualTo(FailoverPhase.PROMOTING);
        assertThat(machine.onPromoted(4, 3, 3_700 * MS)).isEqualTo(EventResult.APPLIED);
        assertThat(machine.phase()).isEqualTo(FailoverPhase.PROMOTING);
        assertThat(machine.onWriteAccepted(4, 3, 3_750 * MS)).isEqualTo(EventResult.APPLIED);
        assertThat(machine.phase()).isEqualTo(FailoverPhase.RESTORED);
        assertThat(machine.onOldPrimaryRecovered(5, 6_000 * MS)).isEqualTo(EventResult.APPLIED);
        assertThat(machine.onDemoted(5, 3, 6_100 * MS)).isEqualTo(EventResult.APPLIED);
        assertThat(machine.onResynchronised(5, 6_300 * MS)).isEqualTo(EventResult.APPLIED);

        FailoverRun run = machine.latestRun().orElseThrow();
        assertThat(run.runId()).isEqualTo(1);
        assertThat(run.oldPrimaryId()).isEqualTo(5);
        assertThat(run.oldEpoch()).isEqualTo(2);
        assertThat(run.crashAtNanos()).isEqualTo(1_000 * MS);
        assertThat(run.crashSource()).isEqualTo(InstantSource.ACTION);
        assertThat(run.detectedAtNanos()).isEqualTo(3_500 * MS);
        assertThat(run.detectedByNodeId()).isEqualTo(3);
        assertThat(run.electedAtNanos()).isEqualTo(3_600 * MS);
        assertThat(run.newPrimaryId()).isEqualTo(4);
        assertThat(run.newEpoch()).isEqualTo(3);
        assertThat(run.promotedAtNanos()).isEqualTo(3_700 * MS);
        assertThat(run.restoredAtNanos()).isEqualTo(3_750 * MS);
        assertThat(run.outcome()).isEqualTo(FailoverRun.Outcome.RESTORED);

        FailoverMeasurements m = run.measurements();
        assertThat(m.detectionMillis()).isEqualTo(2_500.0);
        assertThat(m.failoverMillis()).isEqualTo(200.0);
        assertThat(m.serviceRestoredMillis()).isEqualTo(50.0);
        assertThat(m.outageMillis()).isEqualTo(2_750.0);
        assertThat(m.recoveryMillis()).isEqualTo(300.0);
        assertThat(machine.primaryId()).contains(4);
        assertThat(machine.primaryEpoch()).hasValue(3);
        assertThat(machine.rejectedEventCount()).isZero();
    }

    @Test
    @DisplayName("only the first suspicion of the primary counts")
    void onlyFirstSuspicionCounts() {
        machine.onPrimaryCrashed(5, InstantSource.ACTION, 1_000 * MS);
        machine.onSuspected(2, 5, 3_400 * MS);

        assertThat(machine.onSuspected(1, 5, 3_450 * MS)).isEqualTo(EventResult.IGNORED);
        FailoverRun run = machine.latestRun().orElseThrow();
        assertThat(run.detectedAtNanos()).isEqualTo(3_400 * MS);
        assertThat(run.detectedByNodeId()).isEqualTo(2);
    }

    @Test
    @DisplayName("a crash or suspicion of a node that is not the primary is ignored")
    void nonPrimaryEventsIgnored() {
        assertThat(machine.onPrimaryCrashed(3, InstantSource.ACTION, 1_000 * MS)).isEqualTo(EventResult.IGNORED);
        assertThat(machine.onSuspected(1, 3, 3_000 * MS)).isEqualTo(EventResult.IGNORED);
        assertThat(machine.runs()).isEmpty();
        assertThat(machine.phase()).isEqualTo(FailoverPhase.STEADY);
        assertThatThrownBy(() -> machine.onSuspected(5, 5, 3_000 * MS)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a write before the promotion is recorded, at the old epoch, or by another node does not restore")
    void onlyQualifyingWriteRestores() {
        machine.onPrimaryCrashed(5, InstantSource.ACTION, 1_000 * MS);
        machine.onSuspected(3, 5, 3_500 * MS);
        machine.onPromotionChosen(new Promotion(4, 3, 5, 3_600 * MS));

        assertThat(machine.onWriteAccepted(4, 3, 3_650 * MS)).isEqualTo(EventResult.IGNORED);
        machine.onPromoted(4, 3, 3_700 * MS);
        assertThat(machine.onWriteAccepted(4, 2, 3_710 * MS)).isEqualTo(EventResult.IGNORED);
        assertThat(machine.onWriteAccepted(3, 3, 3_720 * MS)).isEqualTo(EventResult.IGNORED);
        assertThat(machine.phase()).isEqualTo(FailoverPhase.PROMOTING);
        assertThat(machine.latestRun().orElseThrow().restoredAtNanos()).isNull();

        assertThat(machine.onWriteAccepted(4, 3, 3_800 * MS)).isEqualTo(EventResult.APPLIED);
        assertThat(machine.latestRun().orElseThrow().measurements().serviceRestoredMillis()).isEqualTo(100.0);
        assertThat(machine.onWriteAccepted(4, 3, 3_900 * MS)).isEqualTo(EventResult.IGNORED);
    }

    @Test
    @DisplayName("a planned handover changes the primary and epoch without opening a run")
    void plannedHandoverOpensNoRun() {
        assertThat(machine.onPromotionChosen(new Promotion(4, 3, 5, 10 * MS))).isEqualTo(EventResult.APPLIED);
        assertThat(machine.onPromoted(4, 3, 11 * MS)).isEqualTo(EventResult.APPLIED);

        assertThat(machine.runs()).isEmpty();
        assertThat(machine.phase()).isEqualTo(FailoverPhase.STEADY);
        assertThat(machine.primaryId()).contains(4);
        assertThat(machine.primaryEpoch()).hasValue(3);
        assertThat(machine.onPromotionChosen(new Promotion(4, 4, null, 12 * MS))).isEqualTo(EventResult.IGNORED);
    }

    @Test
    @DisplayName("a primary answering again before anyone was chosen keeps its role, and failover stays unmeasured")
    void primaryReturnsBeforePromotion() {
        machine.onPrimaryCrashed(5, InstantSource.ACTION, 1_000 * MS);
        machine.onSuspected(3, 5, 3_500 * MS);

        assertThat(machine.onPrimaryAlive(5, 3_900 * MS)).isEqualTo(EventResult.APPLIED);
        FailoverRun run = machine.latestRun().orElseThrow();
        assertThat(run.outcome()).isEqualTo(FailoverRun.Outcome.PRIMARY_RETURNED);
        assertThat(run.measurements().detectionMillis()).isEqualTo(2_500.0);
        assertThat(run.measurements().failoverMillis()).isNull();
        assertThat(run.measurements().serviceRestoredMillis()).isNull();
        assertThat(run.measurements().outageMillis()).isNull();
        assertThat(machine.phase()).isEqualTo(FailoverPhase.STEADY);
        assertThat(machine.primaryId()).contains(5);
    }

    @Test
    @DisplayName("an old primary recovered before it was even detected ends the run with detection never measured")
    void recoveredBeforeDetection() {
        machine.onPrimaryCrashed(5, InstantSource.ACTION, 1_000 * MS);

        assertThat(machine.onOldPrimaryRecovered(5, 1_500 * MS)).isEqualTo(EventResult.APPLIED);
        FailoverRun run = machine.latestRun().orElseThrow();
        assertThat(run.outcome()).isEqualTo(FailoverRun.Outcome.PRIMARY_RETURNED);
        assertThat(run.measurements().detectionMillis()).isNull();
        assertThat(run.measurements().recoveryMillis()).isNull();
        assertThat(machine.onSuspected(3, 5, 3_500 * MS)).isEqualTo(EventResult.APPLIED);
        assertThat(machine.latestRun().orElseThrow().runId()).isEqualTo(2);
    }

    @Test
    @DisplayName("a failed promotion goes back to suspected and another node can be chosen")
    void promotionFailure() {
        machine.onPrimaryCrashed(5, InstantSource.ACTION, 1_000 * MS);
        machine.onSuspected(3, 5, 3_500 * MS);
        machine.onPromotionChosen(new Promotion(4, 3, 5, 3_600 * MS));

        assertThat(machine.onPromotionFailed(4, 3_650 * MS)).isEqualTo(EventResult.APPLIED);
        assertThat(machine.phase()).isEqualTo(FailoverPhase.SUSPECTED);
        assertThat(machine.latestRun().orElseThrow().newPrimaryId()).isNull();
        assertThat(machine.onPromoted(4, 3, 3_700 * MS)).isEqualTo(EventResult.IGNORED);

        assertThat(machine.onPromotionChosen(new Promotion(3, 4, 5, 3_800 * MS))).isEqualTo(EventResult.APPLIED);
        assertThat(machine.onPromoted(3, 4, 3_850 * MS)).isEqualTo(EventResult.APPLIED);
        assertThat(machine.latestRun().orElseThrow().measurements().failoverMillis()).isEqualTo(350.0);
    }

    @Test
    @DisplayName("recovery is measured only once the old primary is both demoted and resynchronised")
    void recoveryNeedsDemotionAndResync() {
        restoreWithNewPrimary4();
        machine.onOldPrimaryRecovered(5, 6_000 * MS);

        assertThat(machine.onDemoted(5, 2, 6_050 * MS)).isEqualTo(EventResult.IGNORED);   // not a higher epoch
        machine.onDemoted(5, 3, 6_100 * MS);
        assertThat(machine.latestRun().orElseThrow().measurements().recoveryMillis()).isNull();

        machine.onResynchronised(5, 6_400 * MS);
        assertThat(machine.latestRun().orElseThrow().measurements().recoveryMillis()).isEqualTo(400.0);
        assertThat(machine.onDemoted(3, 3, 6_500 * MS)).isEqualTo(EventResult.IGNORED);   // not the old primary
    }

    @Test
    @DisplayName("an instant earlier than the one it follows is rejected, counted, and never recorded")
    void outOfOrderInstantsAreRejectedAndCounted() {
        machine.onPrimaryCrashed(5, InstantSource.ACTION, 1_000 * MS);

        assertThat(machine.onSuspected(3, 5, 900 * MS)).isEqualTo(EventResult.REJECTED_OUT_OF_ORDER);
        assertThat(machine.latestRun().orElseThrow().detectedAtNanos()).isNull();
        machine.onSuspected(3, 5, 3_500 * MS);
        assertThat(machine.onPromotionChosen(new Promotion(4, 3, 5, 3_000 * MS)))
                .isEqualTo(EventResult.REJECTED_OUT_OF_ORDER);
        machine.onPromotionChosen(new Promotion(4, 3, 5, 3_600 * MS));
        assertThat(machine.onPromoted(4, 3, 3_550 * MS)).isEqualTo(EventResult.REJECTED_OUT_OF_ORDER);
        machine.onPromoted(4, 3, 3_700 * MS);
        assertThat(machine.onWriteAccepted(4, 3, 3_650 * MS)).isEqualTo(EventResult.REJECTED_OUT_OF_ORDER);
        machine.onWriteAccepted(4, 3, 3_750 * MS);
        assertThat(machine.onOldPrimaryRecovered(5, 500 * MS)).isEqualTo(EventResult.REJECTED_OUT_OF_ORDER);

        assertThat(machine.rejectedEventCount()).isEqualTo(5);
        FailoverMeasurements m = machine.latestRun().orElseThrow().measurements();
        assertThat(m.detectionMillis()).isEqualTo(2_500.0);
        assertThat(m.failoverMillis()).isEqualTo(200.0);
        assertThat(m.serviceRestoredMillis()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("a crash recorded after the detection it should precede is rejected")
    void lateCrashRejected() {
        machine.onSuspected(3, 5, 3_500 * MS);

        assertThat(machine.onPrimaryCrashed(5, InstantSource.OBSERVED, 3_600 * MS))
                .isEqualTo(EventResult.REJECTED_OUT_OF_ORDER);
        assertThat(machine.onPrimaryCrashed(5, InstantSource.OBSERVED, 1_000 * MS)).isEqualTo(EventResult.APPLIED);
        FailoverRun run = machine.latestRun().orElseThrow();
        assertThat(run.crashSource()).isEqualTo(InstantSource.OBSERVED);
        assertThat(run.measurements().detectionMillis()).isEqualTo(2_500.0);
        assertThat(machine.onPrimaryCrashed(5, InstantSource.ACTION, 1_100 * MS)).isEqualTo(EventResult.IGNORED);
        assertThat(machine.rejectedEventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a suspicion without a recorded crash opens a run whose detection and outage stay unmeasured")
    void suspicionWithoutCrash() {
        machine.onSuspected(3, 5, 3_500 * MS);

        FailoverRun run = machine.latestRun().orElseThrow();
        assertThat(run.crashAtNanos()).isNull();
        assertThat(run.crashSource()).isNull();
        assertThat(run.measurements().detectionMillis()).isNull();
        assertThat(machine.phase()).isEqualTo(FailoverPhase.SUSPECTED);
    }

    @Test
    @DisplayName("a failure of the new primary before the service was restored interrupts the run and opens another")
    void secondFailureInterruptsUnfinishedRun() {
        machine.onPrimaryCrashed(5, InstantSource.ACTION, 1_000 * MS);
        machine.onSuspected(3, 5, 3_500 * MS);
        machine.onPromotionChosen(new Promotion(4, 3, 5, 3_600 * MS));
        machine.onPromoted(4, 3, 3_700 * MS);

        assertThat(machine.onPrimaryCrashed(4, InstantSource.ACTION, 3_710 * MS)).isEqualTo(EventResult.APPLIED);
        assertThat(machine.runs()).hasSize(2);
        FailoverRun first = machine.runs().get(0);
        assertThat(first.outcome()).isEqualTo(FailoverRun.Outcome.INTERRUPTED);
        assertThat(first.measurements().serviceRestoredMillis()).isNull();
        FailoverRun second = machine.runs().get(1);
        assertThat(second.oldPrimaryId()).isEqualTo(4);
        assertThat(second.oldEpoch()).isEqualTo(3);
        assertThat(machine.phase()).isEqualTo(FailoverPhase.STEADY);
    }

    @Test
    @DisplayName("history keeps only the newest runs")
    void historyIsBounded() {
        FailoverStateMachine small = new FailoverStateMachine(2);
        small.onPromotionChosen(new Promotion(5, 2, null, 0));
        small.onPromoted(5, 2, 0);
        int primary = 5;
        long epoch = 2;
        long t = 0;
        for (int i = 0; i < 3; i++) {
            int next = primary == 5 ? 4 : 5;
            small.onPrimaryCrashed(primary, InstantSource.ACTION, t += MS);
            small.onSuspected(3, primary, t += MS);
            small.onPromotionChosen(new Promotion(next, epoch + 1, primary, t += MS));
            small.onPromoted(next, epoch + 1, t += MS);
            small.onWriteAccepted(next, epoch + 1, t += MS);
            primary = next;
            epoch++;
        }
        assertThat(small.runs()).extracting(FailoverRun::runId).containsExactly(2L, 3L);
        assertThatThrownBy(() -> new FailoverStateMachine(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reset forgets the primary, the runs and the rejected count")
    void resetClearsEverything() {
        machine.onPrimaryCrashed(5, InstantSource.ACTION, 1_000 * MS);
        machine.onSuspected(3, 5, 900 * MS);
        machine.reset();

        assertThat(machine.primaryId()).isEmpty();
        assertThat(machine.primaryEpoch()).isEmpty();
        assertThat(machine.runs()).isEmpty();
        assertThat(machine.latestRun()).isEmpty();
        assertThat(machine.rejectedEventCount()).isZero();
        assertThat(machine.phase()).isEqualTo(FailoverPhase.STEADY);
    }

    private void restoreWithNewPrimary4() {
        machine.onPrimaryCrashed(5, InstantSource.ACTION, 1_000 * MS);
        machine.onSuspected(3, 5, 3_500 * MS);
        machine.onPromotionChosen(new Promotion(4, 3, 5, 3_600 * MS));
        machine.onPromoted(4, 3, 3_700 * MS);
        machine.onWriteAccepted(4, 3, 3_750 * MS);
    }
}
