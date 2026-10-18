package com.udcf.scenario.scenarios;

import com.udcf.core.events.ClusterEvent;
import com.udcf.modules.faulttolerance.FaultToleranceModule;
import com.udcf.modules.faulttolerance.dto.FaultToleranceStatusDto;
import com.udcf.modules.faulttolerance.dto.RecoverDto;
import com.udcf.scenario.EventData;
import com.udcf.scenario.Narration;
import com.udcf.scenario.RunMemory;
import com.udcf.scenario.Scenario;
import com.udcf.scenario.ScenarioStep;
import com.udcf.scenario.StepContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * "Split-brain that never happens": after a failover, the old leader recovers. It demotes itself,
 * resynchronises, wins the election again and takes over through a planned handover at a higher
 * epoch. Experiment 8's no-two-primaries check is sampled throughout, from the first step to the last.
 */
@Component
public class SplitBrainNeverHappensScenario implements Scenario {

    public static final String ID = "split-brain-never-happens";
    static final String SAMPLER = "sampler";
    static final String RECOVER = "recover";
    static final String LEADER_BACK = "leaderBackEvent";
    static final long SAMPLE_INTERVAL_MILLIS = 100;

    private final FailoverSteps failover;
    private final FaultToleranceModule faultTolerance;
    private final List<ScenarioStep> steps;

    public SplitBrainNeverHappensScenario(FailoverSteps failover, FaultToleranceModule faultTolerance) {
        this.failover = failover;
        this.faultTolerance = faultTolerance;
        this.steps = List.of(
                ScenarioStep.of("We start Experiment 8 with a stream of synchronous updates on the elected leader. From "
                        + "now on we check many times a second how many nodes act as the primary.", this::start),
                failover.crashLeaderAndFailOver(),
                ScenarioStep.of("We bring the old leader back. It asks the other nodes for its role, learns that it "
                        + "is out of date and steps down to a backup.", this::recover),
                ScenarioStep.of("It copies the current primary's store before it takes part again.", this::resync),
                ScenarioStep.of("Experiment 4 elects it again, because Bully prefers the highest-numbered live node.",
                        this::reelected),
                ScenarioStep.of("It takes over through a planned handover: the current primary stops serving first, "
                        + "then the old leader becomes the primary at a higher epoch.", this::handover),
                ScenarioStep.of("Throughout, there was never more than one primary.", this::neverTwo));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Split-brain that never happens";
    }

    @Override
    public String summary() {
        return "The crashed leader comes back while another node is primary. Watch it step down, catch up and take "
                + "over again at a higher epoch, without two primaries at any moment.";
    }

    @Override
    public List<String> modules() {
        return List.of("election", "replication", "faulttolerance");
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public List<ScenarioStep> steps() {
        return steps;
    }

    @Override
    public String cleanup(RunMemory memory) {
        memory.find(SAMPLER, SplitBrainSampler.class).ifPresent(SplitBrainSampler::stop);
        return failover.stopStream(memory);
    }

    // ------------------------------------------------------------------ steps

    private String start(StepContext step) throws InterruptedException {
        SplitBrainSampler sampler = new SplitBrainSampler(() -> faultTolerance.currentState().splitBrain(),
                SAMPLE_INTERVAL_MILLIS, System::nanoTime);
        step.memory().remember(SAMPLER, sampler);
        sampler.start();
        return failover.startStream(step);
    }

    private String recover(StepContext step) throws InterruptedException {
        int old = step.memory().recall(FailoverSteps.LEADER, Integer.class);
        long anchor = step.anchor(RECOVER);
        RecoverDto recovered = faultTolerance.recoverOldPrimary();
        step.fact("recoveredNodeId", recovered.nodeId());
        if (recovered.nodeId() != old) {
            throw step.fail("Experiment 8 recovered node " + recovered.nodeId() + ", not the old leader, node " + old + ".");
        }
        ClusterEvent demoted = step.awaitEvent(anchor, FailoverSteps.FAULT_TOLERANCE, "OLD_PRIMARY_DEMOTED",
                e -> e.nodeId() == old, "node " + old + " to step down");
        Long previous = EventData.longValue(demoted, "previousEpoch");
        Long epoch = EventData.longValue(demoted, "epoch");
        Integer primary = EventData.integer(demoted, "primaryId");
        step.fact("previousEpoch", previous);
        step.fact("epoch", epoch);
        step.fact("primaryId", primary);
        step.fact("demotedSequence", demoted.sequence());
        return "Node " + old + " was the primary at epoch " + previous + "; it saw epoch " + epoch + " held by node "
                + primary + " and stays a backup.";
    }

    private String resync(StepContext step) throws InterruptedException {
        int old = step.memory().recall(FailoverSteps.LEADER, Integer.class);
        ClusterEvent synced = step.awaitEvent(step.anchorOf(RECOVER), FailoverSteps.FAULT_TOLERANCE, "RESYNCHRONISED",
                e -> e.nodeId() == old, "node " + old + " to copy the primary's store");
        Integer pulled = EventData.integer(synced, "pulled");
        Integer applied = EventData.integer(synced, "applied");
        Long storeEpoch = EventData.longValue(synced, "storeEpoch");
        step.fact("sourceNodeId", synced.peerId());
        step.fact("pulled", pulled);
        step.fact("applied", applied);
        step.fact("storeEpoch", storeEpoch);
        step.fact("resynchronisedSequence", synced.sequence());
        return "Node " + old + " copied node " + synced.peerId() + "'s store: " + applied + " of " + pulled
                + " items applied; its store is now at epoch " + storeEpoch + ".";
    }

    private String reelected(StepContext step) throws InterruptedException {
        int old = step.memory().recall(FailoverSteps.LEADER, Integer.class);
        ClusterEvent changed = step.awaitEvent(step.anchorOf(RECOVER), FailoverSteps.CLUSTER, "LEADER_CHANGED",
                e -> EventData.is(e, "leaderId", old), "Experiment 4 to elect node " + old + " again");
        step.memory().remember(LEADER_BACK, changed);
        step.fact("leaderId", old);
        step.fact("previousLeaderId", changed.data().get("previousLeaderId"));
        step.fact("leaderChangedSequence", changed.sequence());
        return "Experiment 4 elected node " + old + " again (event " + changed.sequence() + ").";
    }

    private String handover(StepContext step) throws InterruptedException {
        int old = step.memory().recall(FailoverSteps.LEADER, Integer.class);
        long failoverEpoch = step.memory().recall(FailoverSteps.NEW_EPOCH, Long.class);
        ClusterEvent leaderBack = step.memory().recall(LEADER_BACK, ClusterEvent.class);
        ClusterEvent handover = step.awaitEvent(step.anchorOf(RECOVER), FailoverSteps.FAULT_TOLERANCE,
                "HANDOVER_STARTED", e -> EventData.is(e, "leaderId", old), "the handover to node " + old + " to start");
        ClusterEvent promoted = step.awaitEvent(handover.sequence(), FailoverSteps.FAULT_TOLERANCE, "PRIMARY_PROMOTED",
                e -> e.nodeId() == old, "node " + old + " to become the primary again");
        Long epoch = EventData.longValue(promoted, "epoch");
        step.fact("fencedNodeId", handover.nodeId());
        step.fact("epoch", epoch);
        step.fact("previousEpoch", failoverEpoch);
        step.requireOrder("leaderChanged", leaderBack, "handoverStarted", handover);
        step.requireOrder("handoverStarted", handover, "promoted", promoted);
        if (epoch == null || epoch <= failoverEpoch) {
            throw step.fail("Node " + old + " became the primary at epoch " + epoch + ", not above epoch "
                    + failoverEpoch + ".");
        }
        FaultToleranceStatusDto status = step.awaitState(faultTolerance::currentState,
                s -> Objects.equals(s.currentPrimaryId(), old), "node " + old + " to serve updates as the primary");
        step.fact("primaryEpoch", status.primaryEpoch());
        return "Node " + handover.nodeId() + " stopped serving (event " + handover.sequence() + "), then node " + old
                + " became the primary at epoch " + epoch + " (event " + promoted.sequence() + "), above epoch "
                + failoverEpoch + ".";
    }

    private String neverTwo(StepContext step) {
        SplitBrainSampler.Summary summary = step.memory().recall(SAMPLER, SplitBrainSampler.class).stop();
        int seen = faultTolerance.currentState().splitBrain().violationsSeen();
        step.fact("samples", summary.samples());
        step.fact("sampledMillis", summary.elapsedMillis());
        step.fact("maxLivePrimaries", summary.maxLivePrimaries());
        step.fact("violations", summary.violations());
        step.fact("sampleErrors", summary.errors());
        step.fact("violationsSeenByExperiment8", seen);
        if (summary.samples() == 0) {
            throw step.fail("No sample could be taken, so nothing was checked.");
        }
        if (!summary.violations().isEmpty() || summary.maxLivePrimaries() > 1 || seen > 0) {
            throw step.fail("Two primaries were seen: " + String.join("; ", summary.violations())
                    + " (most at once: " + summary.maxLivePrimaries() + "; Experiment 8 recorded " + seen + ").");
        }
        return summary.samples() + " checks over " + Narration.millis(summary.elapsedMillis())
                + ": at most " + summary.maxLivePrimaries() + " primary at a time and no violation. Experiment 8's own "
                + "check recorded none either.";
    }
}
