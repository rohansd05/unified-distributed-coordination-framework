package com.udcf.modules.faulttolerance;

import com.udcf.core.events.ClusterEvent;
import com.udcf.core.metrics.MetricNames;
import com.udcf.core.module.ModuleActionGuard;
import com.udcf.core.module.ModuleBusyException;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.faulttolerance.dto.CrashDto;
import com.udcf.modules.faulttolerance.dto.FaultToleranceOverviewDto;
import com.udcf.modules.faulttolerance.dto.FaultToleranceStatusDto;
import com.udcf.modules.faulttolerance.dto.MilestoneDto;
import com.udcf.modules.faulttolerance.dto.ModeCommand;
import com.udcf.modules.faulttolerance.dto.NodeStateDto;
import com.udcf.modules.faulttolerance.dto.RecoverDto;
import com.udcf.modules.faulttolerance.dto.RunDto;
import com.udcf.modules.faulttolerance.dto.StartDto;
import com.udcf.modules.faulttolerance.dto.StreamCommand;
import com.udcf.modules.faulttolerance.dto.StreamDto;
import com.udcf.modules.replication.ConsistencyModel;
import com.udcf.web.InvalidParameterException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FaultToleranceModule} on a real cluster ({@link FaultToleranceModuleHarness}: own
 * ports 26460-26495, election 26473-26477 and replication 26479-26483 bind). Only the update
 * stream has a scripted test of its own (UpdateStreamTest).
 */
class FaultToleranceModuleTest {

    private static final int RATE = 50;

    private FaultToleranceModuleHarness h;

    @AfterEach
    void tearDown() {
        if (h != null) {
            h.close();
        }
    }

    private FaultToleranceModuleHarness harness(int size) {
        h = new FaultToleranceModuleHarness(size);
        return h;
    }

    private static NodeStateDto node(FaultToleranceStatusDto status, int nodeId) {
        return status.nodes().stream().filter(n -> n.nodeId() == nodeId).findFirst().orElseThrow();
    }

    private static List<String> kinds(RunDto run) {
        return run.milestones().stream().map(MilestoneDto::kind).toList();
    }

    private void streamUntilAcknowledged(ConsistencyModel model, int acknowledged) {
        h.module.startStream(new StreamCommand(model, RATE));
        FaultToleranceModuleHarness.within().until(() -> h.module.currentState().stream().acknowledged() >= acknowledged);
    }

    @Test
    @DisplayName("a fresh module is IDLE; reads start nothing; start is idempotent; BUSY only while the guard is held")
    void statusAndIdempotentStart() {
        harness(3);
        FaultToleranceOverviewDto before = h.module.overview();
        assertThat(h.module.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(before.current().started()).isFalse();
        assertThat(before.latestRun()).isNull();
        assertThat(before.current().dataLossNow()).as("no primary store before start").isNull();
        assertThat(h.cluster.nodes()).allSatisfy(n -> assertThat(n.runningServices()).isEmpty());
        assertThat(before.current().nodes()).allSatisfy(n -> {
            assertThat(n.role()).isNull();
            assertThat(n.epoch()).isNull();
        });

        StartDto first = h.module.start();
        assertThat(first.outcome()).isEqualTo(FaultToleranceModule.STARTED);
        assertThat(first.reason()).isNull();
        StartDto second = h.module.start();
        assertThat(second.outcome()).isEqualTo(FaultToleranceModule.ALREADY_STARTED);
        assertThat(second.reason()).contains("already running");
        h.awaitPrimary(3);
        assertThat(h.module.status()).isEqualTo(ModuleStatus.RUNNING);
        FaultToleranceStatusDto state = h.module.currentState();
        assertThat(state.currentPrimaryId()).isEqualTo(3);
        assertThat(state.primaryEpoch()).isEqualTo(2L);
        assertThat(node(state, 3).role()).isEqualTo("PRIMARY");
        assertThat(node(state, 2).role()).isEqualTo("BACKUP");
        assertThat(node(state, 2).epoch()).isEqualTo(2L);

        try (ModuleActionGuard.ActionTicket ignored = h.module.guard().begin("test")) {
            assertThat(h.module.status()).isEqualTo(ModuleStatus.BUSY);
            assertThat(h.module.currentState().actionInProgress()).isEqualTo("test");
            assertThatThrownBy(h.module::crashPrimary).isInstanceOf(ModuleBusyException.class);
            assertThatThrownBy(h.module::start).isInstanceOf(ModuleBusyException.class);
        }
        assertThat(h.module.status()).isEqualTo(ModuleStatus.RUNNING);
    }

    @Test
    @DisplayName("before start: every conflict and every invalid input is refused and changes nothing")
    void conflictsAndInvalidInputBeforeStart() {
        harness(3);
        assertThatThrownBy(h.module::crashPrimary).isInstanceOfSatisfying(FaultToleranceConflictException.class, e -> {
            assertThat(e.reason()).isEqualTo(FaultToleranceConflictException.Reason.NO_SERVING_PRIMARY);
            assertThat(e.getMessage()).contains("has not started");
        });
        assertThatThrownBy(h.module::recoverOldPrimary).isInstanceOfSatisfying(FaultToleranceConflictException.class,
                e -> assertThat(e.reason()).isEqualTo(FaultToleranceConflictException.Reason.NOTHING_TO_RECOVER));
        assertThatThrownBy(h.module::stopStream).isInstanceOfSatisfying(FaultToleranceConflictException.class,
                e -> assertThat(e.reason()).isEqualTo(FaultToleranceConflictException.Reason.STREAM_NOT_RUNNING));
        assertThatThrownBy(h.module::latestRun).isInstanceOfSatisfying(UnknownFailoverRunException.class,
                e -> assertThat(e.runId()).isNull());
        assertThatThrownBy(() -> h.module.run(9)).isInstanceOfSatisfying(UnknownFailoverRunException.class,
                e -> assertThat(e.runId()).isEqualTo(9L));
        assertThatThrownBy(() -> h.module.startStream(new StreamCommand(ConsistencyModel.SYNCHRONOUS, 0)))
                .isInstanceOfSatisfying(InvalidParameterException.class,
                        e -> assertThat(e.parameter()).isEqualTo("ratePerSecond"));
        assertThatThrownBy(() -> h.module.startStream(new StreamCommand(ConsistencyModel.SYNCHRONOUS,
                FaultToleranceModuleHarness.MAX_RATE + 1))).isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("between 1 and " + FaultToleranceModuleHarness.MAX_RATE);
        assertThatThrownBy(() -> h.module.startStream(new StreamCommand(null, 1)))
                .isInstanceOfSatisfying(InvalidParameterException.class, e -> assertThat(e.parameter()).isEqualTo("mode"));
        assertThat(h.module.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(h.module.runs()).isEmpty();
        assertThat(h.cluster.nodes()).allSatisfy(n -> assertThat(n.runningServices()).isEmpty());
        assertThat(h.bus.query(FaultToleranceModule.ID, null, 100)).isEmpty();
    }

    @Test
    @DisplayName("with every node down no leader can be elected: start answers 409 NO_LEADER after the election bound, never a fallback")
    void startWithoutLeaderIs409() {
        harness(2);
        h.cluster.crash(1);
        h.cluster.crash(2);
        long bound = FaultToleranceModuleHarness.ELECTION.okTimeoutMillis()
                + FaultToleranceModuleHarness.ELECTION.coordinatorTimeoutMillis();

        long start = System.nanoTime();
        assertThatThrownBy(h.module::start).isInstanceOfSatisfying(FaultToleranceConflictException.class, e -> {
            assertThat(e.reason()).isEqualTo(FaultToleranceConflictException.Reason.NO_LEADER);
            assertThat(e.getMessage()).startsWith("No leader could be elected");
        });
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isGreaterThanOrEqualTo(bound - 20).isLessThan(bound + 2000);
        assertThat(h.module.guard().isBusy()).isFalse();
        assertThat(h.failover.currentPrimary()).isEmpty();
        assertThat(h.roles.leaderId()).isEmpty();
        assertThat(h.events("PRIMARY_PROMOTED")).isEmpty();
    }

    @Test
    @DisplayName("stream start on a never-started module starts it inside one guard, and updates are acknowledged")
    void streamStartStartsTheModule() {
        harness(3);
        StreamDto started = h.module.startStream(new StreamCommand(ConsistencyModel.SYNCHRONOUS, RATE));
        assertThat(started.state()).isEqualTo("RUNNING");
        assertThat(started.ratePerSecond()).isEqualTo(RATE);
        assertThat(h.module.guard().isBusy()).isFalse();
        assertThat(h.failover.snapshot().started()).isTrue();
        FaultToleranceModuleHarness.within().until(() -> h.module.currentState().stream().acknowledged() >= 3);
        assertThat(h.events("STREAM_STARTED")).singleElement().satisfies(e -> {
            assertThat(e.nodeId()).isZero();
            assertThat(e.data()).containsEntry("mode", "SYNCHRONOUS").containsEntry("ratePerSecond", RATE);
        });
        assertThatThrownBy(() -> h.module.startStream(new StreamCommand(ConsistencyModel.SYNCHRONOUS, RATE)))
                .isInstanceOfSatisfying(FaultToleranceConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(FaultToleranceConflictException.Reason.STREAM_RUNNING));
        StreamDto mode = h.module.setMode(new ModeCommand(ConsistencyModel.ASYNCHRONOUS));
        assertThat(mode.mode()).isEqualTo(ConsistencyModel.ASYNCHRONOUS);
        assertThat(h.events("MODE_CHANGED")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("previousMode", "SYNCHRONOUS"));
        StreamDto stopped = h.module.stopStream();
        assertThat(stopped.state()).isEqualTo("STOPPED");
        assertThat(stopped.stopReason()).isEqualTo("REQUESTED");
        assertThat(h.events("STREAM_STOPPED")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("reason", "REQUESTED"));
        assertThat(h.events("SPLIT_BRAIN_CHECKED")).isNotEmpty()
                .allSatisfy(e -> assertThat(e.data()).containsEntry("passed", true));
    }

    @Test
    @DisplayName("synchronous: crash, failover to the new leader, zero loss (a real 0); recovery demotes the old primary, "
            + "which then takes over again as the leader; nulls while unknown")
    void synchronousFailoverLosesNothingAndOldPrimaryDemotes() {
        harness(3);
        streamUntilAcknowledged(ConsistencyModel.SYNCHRONOUS, 5);
        h.awaitPrimary(3);

        CrashDto crash = h.module.crashPrimary();
        assertThat(crash.nodeId()).isEqualTo(3);
        assertThat(crash.epoch()).isEqualTo(2L);
        assertThat(crash.crashSource()).isEqualTo("ACTION");
        FaultToleranceStatusDto during = crash.status();
        assertThat(during.dataLossNow()).as("no primary store during a failover").isNull();
        assertThat(during.currentPrimaryId()).isNull();
        assertThat(during.primaryEpoch()).isNull();
        assertThat(node(during, 3).role()).isNull();
        assertThat(node(during, 3).epoch()).isNull();
        assertThatThrownBy(h.module::crashPrimary).isInstanceOfSatisfying(FaultToleranceConflictException.class,
                e -> assertThat(e.getMessage()).contains("failover is in progress"));

        FaultToleranceModuleHarness.within().until(() -> h.module.latestRun().dataLoss() != null);
        RunDto restored = h.module.latestRun();
        assertThat(restored.outcome()).isEqualTo("RESTORED");
        assertThat(restored.newPrimaryId()).isEqualTo(2);
        assertThat(restored.newEpoch()).isEqualTo(3L);
        assertThat(restored.dataLoss().lost()).as("a measured zero stays 0").isZero();
        assertThat(restored.dataLoss().lostSynchronous()).isZero();
        assertThat(restored.dataLoss().notAssessed()).isNull();
        assertThat(restored.dataLoss().simulated()).isFalse();
        assertThat(restored.dataLoss().simulatedDelayMillis()).isNull();
        assertThat(restored.measurements().detectionMillis()).isPositive();
        assertThat(restored.measurements().failoverMillis()).isNotNull();
        assertThat(restored.measurements().outageMillis()).isPositive();
        assertThat(restored.measurements().recoveryMillis()).isNull();
        assertThat(kinds(restored)).containsSubsequence("CRASHED", "DETECTED", "ELECTED", "PROMOTED", "SERVICE_RESTORED");
        assertThat(restored.milestones().get(0).offsetMillis()).isZero();
        FaultToleranceStatusDto after = h.module.currentState();
        assertThat(after.dataLossNow()).isNotNull();
        assertThat(after.dataLossNow().lost()).isZero();
        assertThat(h.events("FAILOVER_MEASURED")).singleElement().satisfies(e -> {
            assertThat(e.nodeId()).isEqualTo(2);
            assertThat(e.peerId()).isEqualTo(3);
            assertThat(e.data()).containsEntry("recoveryMillis", null).containsKey("outageMillis");
        });
        assertThat(h.events("DATA_LOSS_MEASURED")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("lost", 0).containsEntry("simulated", false));
        assertThat(h.registry.find(MetricNames.FAILOVER_LOST_UPDATES_TOTAL).tag(MetricNames.NODE_ID, "2")
                .tag("model", "SYNCHRONOUS").counter()).isNotNull()
                .satisfies(c -> assertThat(c.count()).isZero());

        RecoverDto recovered = h.module.recoverOldPrimary();
        assertThat(recovered.nodeId()).isEqualTo(3);
        assertThat(recovered.previousEpoch()).isEqualTo(2L);
        FaultToleranceModuleHarness.within().until(() -> !h.events("RECOVERY_MEASURED").isEmpty());
        RunDto done = h.module.latestRun();
        assertThat(done.measurements().recoveryMillis()).isNotNull();
        assertThat(kinds(done)).contains("OLD_PRIMARY_RECOVERED", "DEMOTED", "RESYNCHRONISED");
        h.awaitPrimary(3);   // node 3 won Bully again: the planned handover follows its resync
        FaultToleranceStatusDto end = h.module.currentState();
        assertThat(node(end, 3).role()).isEqualTo("PRIMARY");
        assertThat(node(end, 3).epoch()).isEqualTo(4L);
        assertThat(node(end, 2).role()).isEqualTo("BACKUP");
        assertThat(end.currentPrimaryId()).isEqualTo(3);
        assertThat(end.primaryEpoch()).isEqualTo(4L);
        assertThat(end.splitBrain().passed()).isTrue();
        assertThat(end.splitBrain().violationsSeen()).isZero();
        assertThat(h.events("OLD_PRIMARY_RECOVERED")).singleElement()
                .satisfies(e -> assertThat(e.nodeId()).isEqualTo(3));
        assertThat(h.events("HANDOVER_STARTED")).singleElement().satisfies(e -> {
            assertThat(e.nodeId()).isEqualTo(2);
            assertThat(e.peerId()).isEqualTo(3);
        });
        assertThatThrownBy(h.module::recoverOldPrimary).isInstanceOfSatisfying(FaultToleranceConflictException.class,
                e -> assertThat(e.getMessage()).contains("already up"));
        h.module.stopStream();
        assertEventsWellFormed();
    }

    @Test
    @DisplayName("asynchronous with a 5000 ms simulated delay: the in-flight loss is measured, none of it synchronous")
    void asynchronousFailoverShowsInFlightLoss() {
        h = new FaultToleranceModuleHarness(3, 10, 400, 5000);
        h.module.start();
        h.awaitPrimary(3);
        streamUntilAcknowledged(ConsistencyModel.ASYNCHRONOUS, 1);
        h.module.crashPrimary();
        FaultToleranceModuleHarness.within().until(() -> h.module.latestRun().dataLoss() != null);
        RunDto run = h.module.latestRun();
        assertThat(run.dataLoss().lost()).isGreaterThanOrEqualTo(1);
        assertThat(run.dataLoss().lostSynchronous()).isZero();
        assertThat(run.dataLoss().lostAsynchronous()).isEqualTo(run.dataLoss().lost());
        assertThat(run.dataLoss().lostKeys()).hasSize(run.dataLoss().lost());
        assertThat(run.dataLoss().simulated()).isTrue();
        assertThat(run.dataLoss().simulatedDelayMillis()).isEqualTo(5000L);
        assertThat(run.dataLoss().simulatedReason()).contains("simulated");
        assertThat(h.events("DATA_LOSS_MEASURED")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("simulated", true)
                        .containsEntry("simulatedDelayMillis", 5000L));
        assertThat(h.module.overview().notes()).anySatisfy(n -> assertThat(n).contains("waits 5000 ms"));
        h.module.stopStream();
    }

    @Test
    @DisplayName("the old primary returns before anyone is chosen: the run ends PRIMARY_RETURNED (no peer could detect it)")
    void oldPrimaryReturnsBeforePromotion() {
        harness(2);
        h.module.start();
        h.awaitPrimary(2);
        h.cluster.crash(1);   // nobody is left to detect node 2's crash, so no one can be chosen
        h.module.crashPrimary();
        h.module.recoverOldPrimary();
        RunDto run = h.module.latestRun();
        assertThat(run.outcome()).isEqualTo("PRIMARY_RETURNED");
        assertThat(run.newPrimaryId()).isNull();
        assertThat(run.detectedByNodeId()).isNull();
        assertThat(kinds(run)).containsExactly("CRASHED", "OLD_PRIMARY_RECOVERED");
        assertThat(run.measurements().detectionMillis()).isNull();
        assertThat(run.dataLoss()).isNull();
    }

    @Test
    @DisplayName("history keeps history-limit runs, newest first; an evicted run is 404")
    void historyIsBounded() {
        h = new FaultToleranceModuleHarness(3, 1, 400, FailoverFixture.REPLICATION.asyncDelayMillis());
        streamUntilAcknowledged(ConsistencyModel.SYNCHRONOUS, 3);
        h.awaitPrimary(3);
        h.module.crashPrimary();
        FaultToleranceModuleHarness.within().until(() -> h.module.latestRun().dataLoss() != null);
        h.module.recoverOldPrimary();
        FaultToleranceModuleHarness.within().until(() -> h.module.latestRun().measurements().recoveryMillis() != null);
        h.awaitPrimary(3);   // the planned handover back to node 3, the leader again
        h.module.crashPrimary();
        FaultToleranceModuleHarness.within().until(() -> h.module.latestRun().runId() == 2
                && h.module.latestRun().dataLoss() != null);
        assertThat(h.module.runs()).extracting(RunDto::runId).containsExactly(2L);
        assertThatThrownBy(() -> h.module.run(1)).isInstanceOf(UnknownFailoverRunException.class);
        assertThat(h.module.run(2).dataLoss()).isNotNull();
        h.module.stopStream();
    }

    @Test
    @DisplayName("the budget: the stream stops LIMIT_REACHED, a new start is 409 with the reset hint; reset restores it")
    void budgetAndReset() {
        h = new FaultToleranceModuleHarness(3, 10, 4, FailoverFixture.REPLICATION.asyncDelayMillis());
        h.module.startStream(new StreamCommand(ConsistencyModel.SYNCHRONOUS, RATE));
        FaultToleranceModuleHarness.within().until(() -> !h.events("STREAM_STOPPED").isEmpty());
        StreamDto stopped = h.module.currentState().stream();
        assertThat(stopped.stopReason()).isEqualTo("LIMIT_REACHED");
        assertThat(stopped.remainingUpdates()).isZero();
        assertThat(stopped.maxUpdates()).isEqualTo(4);
        assertThatThrownBy(() -> h.module.startStream(new StreamCommand(ConsistencyModel.SYNCHRONOUS, RATE)))
                .isInstanceOfSatisfying(FaultToleranceConflictException.class, e -> {
                    assertThat(e.reason()).isEqualTo(FaultToleranceConflictException.Reason.UPDATE_LIMIT_REACHED);
                    assertThat(e.getMessage()).contains("A cluster reset gives a fresh budget");
                });
        FaultToleranceModuleHarness.within().until(() -> h.module.currentState().stream().inFlightSequence() == null);
        int eventsBefore = h.bus.query(FaultToleranceModule.ID, null, 5000).size();

        h.module.reset();
        FaultToleranceStatusDto after = h.module.currentState();
        // only Experiment 8's own log: the primary stepping down is Exp 5's PRIMARY_STEPPED_DOWN
        assertThat(h.bus.query(FaultToleranceModule.ID, null, 5000)).as("reset publishes nothing").hasSize(eventsBefore);
        assertThat(after.started()).isFalse();
        assertThat(after.stream().remainingUpdates()).isEqualTo(4);
        assertThat(after.stream().stopReason()).isNull();
        assertThat(after.acknowledgedUpdates()).isZero();
        assertThat(h.module.runs()).isEmpty();
        assertThat(h.module.status()).isEqualTo(ModuleStatus.IDLE);

        h.module.startStream(new StreamCommand(ConsistencyModel.SYNCHRONOUS, RATE));
        FaultToleranceModuleHarness.within().until(() -> !h.failover.acknowledged().isEmpty());
        assertThat(h.failover.acknowledged().get(0).sequence()).as("sequences go on after a reset").isGreaterThan(4);
    }

    @Test
    @DisplayName("reads publish nothing and record nothing")
    void readsPublishNothing() {
        harness(3);
        streamUntilAcknowledged(ConsistencyModel.SYNCHRONOUS, 3);
        h.module.stopStream();
        FaultToleranceModuleHarness.within().until(() -> h.module.currentState().stream().inFlightSequence() == null);
        long before = h.bus.publishedCount();
        int seen = h.module.currentState().splitBrain().violationsSeen();
        for (int i = 0; i < 100; i++) {
            h.module.currentState();
            h.module.overview();
            h.module.runs();
        }
        assertThat(h.bus.publishedCount()).isEqualTo(before);
        assertThat(h.module.currentState().splitBrain().violationsSeen()).isEqualTo(seen);
    }

    @Test
    @DisplayName("Exp 5 events per update fit the event buffer: 400 updates on 5 nodes, 200 on 3 (application YAML)")
    void eventVolumePerUpdateFitsTheBuffer() {
        assertThat(eventsPerUpdate(5) * 400).as("local: 5 nodes, buffer 5000").isLessThanOrEqualTo(5000);
        assertThat(eventsPerUpdate(3) * 200).as("public: 3 nodes, buffer 1500").isLessThanOrEqualTo(1500);
    }

    /** Every event the cluster published per update of a 20-update synchronous stream, the stream's own events included. */
    private double eventsPerUpdate(int size) {
        if (h != null) {
            h.close();
        }
        h = new FaultToleranceModuleHarness(size, 10, 20, FailoverFixture.REPLICATION.asyncDelayMillis());
        h.module.start();
        h.awaitPrimary(size);
        FaultToleranceModuleHarness.within().until(() -> h.module.currentState().nodes().stream()
                .allMatch(n -> Objects.equals(n.rejoin(), "READY")));
        long before = h.bus.publishedCount();
        h.module.startStream(new StreamCommand(ConsistencyModel.SYNCHRONOUS, RATE));
        FaultToleranceModuleHarness.within().until(() -> !h.events("STREAM_STOPPED").isEmpty());
        FaultToleranceModuleHarness.within().until(() -> h.module.currentState().stream().inFlightSequence() == null);
        double perUpdate = (double) (h.bus.publishedCount() - before) / 20;
        System.out.printf(java.util.Locale.ROOT, "Experiment 8 event volume: %d nodes, %.2f events per update%n", size,
                perUpdate);
        return perUpdate;
    }

    private void assertEventsWellFormed() {
        List<ClusterEvent> all = h.bus.query(FaultToleranceModule.ID, null, 5000);
        assertThat(all).isNotEmpty().allSatisfy(event -> {
            assertThat(FailoverFixture.ALL_CAPS_WORD.matcher(Objects.requireNonNull(event.message())).find())
                    .as(event.message()).isFalse();
            assertThat(event.lamportTime()).as(event.type()).isPositive();
        });
    }
}
