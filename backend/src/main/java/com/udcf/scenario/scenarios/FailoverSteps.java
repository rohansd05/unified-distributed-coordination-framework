package com.udcf.scenario.scenarios;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.RoleProvider;
import com.udcf.core.events.ClusterEvent;
import com.udcf.modules.faulttolerance.FaultToleranceConflictException;
import com.udcf.modules.faulttolerance.FaultToleranceModule;
import com.udcf.modules.faulttolerance.dto.CrashDto;
import com.udcf.modules.faulttolerance.dto.FaultToleranceStatusDto;
import com.udcf.modules.faulttolerance.dto.StreamCommand;
import com.udcf.modules.faulttolerance.dto.StreamDto;
import com.udcf.modules.replication.ConsistencyModel;
import com.udcf.scenario.EventData;
import com.udcf.scenario.Narration;
import com.udcf.scenario.RunMemory;
import com.udcf.scenario.ScenarioStep;
import com.udcf.scenario.StepContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Experiment 8 steps shared by scenarios (reusable by any scenario): start a synchronous update
 * stream on the elected leader, crash the leader node, and follow detection, election, promotion and
 * the restored service. Each step body is also public, so a scenario can wrap it with its own
 * narration.
 *
 * <p>Every step reads only observed state and events; consequences of the crash are awaited from the
 * {@value #CRASH} anchor, so an event published while an earlier step was still waiting is accepted.
 * The crash is {@code FaultToleranceModule.crashPrimary()}, which crashes the whole node on every
 * protocol ({@code Cluster.crash}), so Experiment 4 sees its leader fail.</p>
 *
 * <p>Run memory written here: {@value #LEADER} (Integer, the first leader and primary), {@value #EPOCH}
 * (Long), {@value #NEW_LEADER} (Integer), {@value #NEW_EPOCH} (Long), {@value #LEADER_CHANGED}
 * (the {@code LEADER_CHANGED} event that elected the new leader), and the anchors {@value #START} and
 * {@value #CRASH}.</p>
 */
@Component
public class FailoverSteps {

    public static final String LEADER = "leader";
    public static final String EPOCH = "epoch";
    public static final String NEW_LEADER = "newLeader";
    public static final String NEW_EPOCH = "newEpoch";
    public static final String LEADER_CHANGED = "leaderChangedEvent";
    public static final String START = "start";
    public static final String CRASH = "crash";

    static final String FAULT_TOLERANCE = FaultToleranceModule.ID;
    static final String CLUSTER = "cluster";
    /** Confirmed updates before the crash, so the crash happens mid-write with a record to lose. */
    static final int CONFIRMED_BEFORE_CRASH = 3;

    private final FaultToleranceModule faultTolerance;
    private final RoleProvider roles;
    private final Cluster cluster;

    public FailoverSteps(FaultToleranceModule faultTolerance, RoleProvider roles, Cluster cluster) {
        this.faultTolerance = Objects.requireNonNull(faultTolerance, "faultTolerance must not be null");
        this.roles = Objects.requireNonNull(roles, "roles must not be null");
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
    }

    // ------------------------------------------------------------------ steps with their default narration

    public ScenarioStep startSynchronousStream() {
        return ScenarioStep.of("We start Experiment 8 and send it a steady stream of synchronous updates. The leader "
                + "elected by Experiment 4 becomes the replication primary.", this::startStream);
    }

    public ScenarioStep crashLeader() {
        return ScenarioStep.of("We crash the leader, which is also the primary, while updates are still being "
                + "written.", this::crash);
    }

    public ScenarioStep detection() {
        return ScenarioStep.of("The other nodes stop hearing its heartbeats, and a failure detector suspects it.",
                this::detect);
    }

    public ScenarioStep election() {
        return ScenarioStep.of("Experiment 4 runs a Bully election among the live nodes, and they agree on a new "
                + "leader.", this::elect);
    }

    public ScenarioStep promotion() {
        return ScenarioStep.of("The new leader copies what it is missing from the other backups and becomes the "
                + "primary at a higher epoch.", this::promote);
    }

    public ScenarioStep serviceRestored() {
        return ScenarioStep.of("Updates are accepted again, and every update the client was told had succeeded is "
                + "still there.", this::restoreWithoutLoss);
    }

    public ScenarioStep crashLeaderAndFailOver() {
        return ScenarioStep.of("We crash the leader and wait until the newly elected leader serves as the primary.",
                this::crashAndFailOver);
    }

    // ------------------------------------------------------------------ step bodies

    /** Starts a synchronous stream at Experiment 8's default rate; the leader must become the primary. */
    public String startStream(StepContext step) throws InterruptedException {
        int rate = faultTolerance.overview().settings().defaultRatePerSecond();
        long anchor = step.anchor(START);
        faultTolerance.startStream(new StreamCommand(ConsistencyModel.SYNCHRONOUS, rate));
        ClusterEvent promoted = step.awaitEvent(anchor, FAULT_TOLERANCE, "PRIMARY_PROMOTED", e -> true,
                "the first primary to be appointed");
        int primary = promoted.nodeId();
        Long epoch = EventData.longValue(promoted, "epoch");
        Integer leader = roles.leaderId().orElse(null);
        step.fact("leaderId", leader);
        step.fact("primaryId", primary);
        step.fact("epoch", epoch);
        step.fact("ratePerSecond", rate);
        if (!Integer.valueOf(primary).equals(leader)) {
            throw step.fail("Node " + primary + " became the primary, but the leader is "
                    + (leader == null ? "nobody" : "node " + leader) + ".");
        }
        FaultToleranceStatusDto status = step.awaitState(faultTolerance::currentState,
                s -> s.stream().acknowledged() >= CONFIRMED_BEFORE_CRASH && Objects.equals(s.currentPrimaryId(), primary),
                CONFIRMED_BEFORE_CRASH + " updates to be confirmed by node " + primary);
        step.fact("acknowledged", status.stream().acknowledged());
        step.memory().remember(LEADER, primary);
        step.memory().remember(EPOCH, epoch);
        return "Node " + primary + ", the leader elected by Experiment 4, is the primary at epoch " + epoch + "; "
                + status.stream().acknowledged() + " updates are confirmed so far.";
    }

    /** Crashes the primary node through Experiment 8; it must be the leader recorded by {@link #startStream}. */
    public String crash(StepContext step) throws InterruptedException {
        int leader = step.memory().recall(LEADER, Integer.class);
        int acknowledged = faultTolerance.currentState().stream().acknowledged();
        long anchor = step.anchor(CRASH);
        CrashDto crash = faultTolerance.crashPrimary();
        step.fact("crashedNodeId", crash.nodeId());
        step.fact("crashedEpoch", crash.epoch());
        step.fact("acknowledgedBeforeCrash", acknowledged);
        if (crash.nodeId() != leader) {
            throw step.fail("Experiment 8 crashed node " + crash.nodeId() + ", not the leader, node " + leader + ".");
        }
        ClusterEvent crashed = step.awaitEvent(anchor, FAULT_TOLERANCE, "PRIMARY_CRASHED", e -> e.nodeId() == leader,
                "node " + leader + "'s crash to be recorded");
        step.fact("crashedSequence", crashed.sequence());
        if (cluster.node(leader).isUp()) {
            throw step.fail("Node " + leader + " is still up after the crash.");
        }
        return "Node " + leader + " went down on every protocol at epoch " + crash.epoch() + ", after "
                + acknowledged + " confirmed updates.";
    }

    /** The first failure detector to suspect the crashed leader. */
    public String detect(StepContext step) throws InterruptedException {
        int leader = step.memory().recall(LEADER, Integer.class);
        ClusterEvent detected = step.awaitEvent(step.anchorOf(CRASH), FAULT_TOLERANCE, "FAILURE_DETECTED",
                e -> Objects.equals(e.peerId(), leader), "a failure detector to suspect node " + leader);
        Long silent = EventData.longValue(detected, "silentMillis");
        Long timeout = EventData.longValue(detected, "timeoutMillis");
        step.fact("detectedByNodeId", detected.nodeId());
        step.fact("silentMillis", silent);
        step.fact("timeoutMillis", timeout);
        step.fact("detectedSequence", detected.sequence());
        return "Node " + detected.nodeId() + " noticed first: no heartbeat from node " + leader + " for "
                + Narration.millis(silent) + " (the timeout is " + Narration.millis(timeout) + ").";
    }

    /** The {@code LEADER_CHANGED} that names a new, live leader after the crash. */
    public String elect(StepContext step) throws InterruptedException {
        ClusterEvent changed = awaitNewLeader(step);
        int newLeader = step.memory().recall(NEW_LEADER, Integer.class);
        List<Integer> live = liveIds();
        step.fact("liveNodeIds", live);
        boolean highest = !live.isEmpty() && live.get(live.size() - 1) == newLeader;
        return "Experiment 4 elected node " + newLeader + (highest ? ", the highest-numbered live node" : "")
                + " (event " + changed.sequence() + ").";
    }

    /** The new leader's promotion, at a higher epoch and after its election by bus sequence. */
    public String promote(StepContext step) throws InterruptedException {
        ClusterEvent changed = step.memory().recall(LEADER_CHANGED, ClusterEvent.class);
        int newLeader = step.memory().recall(NEW_LEADER, Integer.class);
        long oldEpoch = step.memory().recall(EPOCH, Long.class);
        ClusterEvent promoted = awaitPromotion(step, newLeader, oldEpoch);
        step.requireOrder("leaderChanged", changed, "promoted", promoted);
        return "Node " + newLeader + " became the primary at epoch " + step.memory().recall(NEW_EPOCH, Long.class)
                + " (the old primary held epoch " + oldEpoch + "), after its election: event " + promoted.sequence()
                + " came after event " + changed.sequence() + ".";
    }

    /** The new primary accepts an update, and no confirmed update was lost. */
    public String restoreWithoutLoss(StepContext step) throws InterruptedException {
        int newLeader = step.memory().recall(NEW_LEADER, Integer.class);
        ClusterEvent restored = awaitRestored(step, newLeader);
        ClusterEvent loss = step.awaitEvent(step.anchorOf(CRASH), FAULT_TOLERANCE, "DATA_LOSS_MEASURED",
                e -> e.nodeId() == newLeader, "the data loss to be measured on node " + newLeader);
        Integer acknowledged = EventData.integer(loss, "acknowledged");
        Integer lost = EventData.integer(loss, "lost");
        step.fact("acknowledged", acknowledged);
        step.fact("lost", lost);
        step.fact("simulated", loss.data().get("simulated"));
        if (lost == null) {
            throw step.fail("The data loss could not be assessed on node " + newLeader + " ("
                    + loss.data().get("notAssessed") + ").");
        }
        if (lost != 0) {
            throw step.fail("Node " + newLeader + " lacks " + lost + " of " + acknowledged + " confirmed updates.");
        }
        return "Node " + newLeader + " accepted update '" + restored.data().get("key") + "' after an outage of "
                + Narration.millis(EventData.decimal(restored, "outageMillis")) + ". " + lost + " of " + acknowledged
                + " confirmed updates were lost.";
    }

    /** Crash, election, promotion and the restored service in one step. */
    public String crashAndFailOver(StepContext step) throws InterruptedException {
        String crashed = crash(step);
        ClusterEvent changed = awaitNewLeader(step);
        int newLeader = step.memory().recall(NEW_LEADER, Integer.class);
        ClusterEvent promoted = awaitPromotion(step, newLeader, step.memory().recall(EPOCH, Long.class));
        step.requireOrder("leaderChanged", changed, "promoted", promoted);
        ClusterEvent restored = awaitRestored(step, newLeader);
        return crashed + " Experiment 4 elected node " + newLeader + ", which took over at epoch "
                + step.memory().recall(NEW_EPOCH, Long.class) + " and accepted update '" + restored.data().get("key")
                + "' after an outage of " + Narration.millis(EventData.decimal(restored, "outageMillis")) + ".";
    }

    /** Cleanup: stops the update stream if it runs. @return a sentence for the end state, or null if none ran */
    public String stopStream(RunMemory memory) {
        StreamDto stream = faultTolerance.currentState().stream();
        if ("RUNNING".equals(stream.state())) {
            try {
                stream = faultTolerance.stopStream();
            } catch (FaultToleranceConflictException e) {
                stream = faultTolerance.currentState().stream();   // it stopped by itself meanwhile
            }
        } else if (stream.sent() == 0) {
            return null;
        }
        return "The update stream is stopped after " + stream.sent() + " updates (" + stream.acknowledged()
                + " confirmed).";
    }

    // ------------------------------------------------------------------ shared waits

    private ClusterEvent awaitNewLeader(StepContext step) throws InterruptedException {
        int leader = step.memory().recall(LEADER, Integer.class);
        ClusterEvent changed = step.awaitEvent(step.anchorOf(CRASH), CLUSTER, "LEADER_CHANGED", e -> {
            Integer id = EventData.integer(e, "leaderId");
            return id != null && id != leader;
        }, "Experiment 4 to elect a new leader");
        int newLeader = EventData.integer(changed, "leaderId");
        step.fact("newLeaderId", newLeader);
        step.fact("leaderChangedSequence", changed.sequence());
        step.memory().remember(NEW_LEADER, newLeader);
        step.memory().remember(LEADER_CHANGED, changed);
        return changed;
    }

    private ClusterEvent awaitPromotion(StepContext step, int newLeader, long oldEpoch) throws InterruptedException {
        ClusterEvent promoted = step.awaitEvent(step.anchorOf(CRASH), FAULT_TOLERANCE, "PRIMARY_PROMOTED",
                e -> e.nodeId() == newLeader, "node " + newLeader + " to become the primary");
        Long epoch = EventData.longValue(promoted, "epoch");
        step.fact("primaryId", newLeader);
        step.fact("epoch", epoch);
        step.fact("previousEpoch", oldEpoch);
        step.fact("caughtUpFrom", promoted.data().get("caughtUpFrom"));
        if (epoch == null || epoch <= oldEpoch) {
            throw step.fail("Node " + newLeader + " became the primary at epoch " + epoch + ", not above epoch "
                    + oldEpoch + ".");
        }
        step.memory().remember(NEW_EPOCH, epoch);
        return promoted;
    }

    private ClusterEvent awaitRestored(StepContext step, int newLeader) throws InterruptedException {
        ClusterEvent restored = step.awaitEvent(step.anchorOf(CRASH), FAULT_TOLERANCE, "SERVICE_RESTORED",
                e -> e.nodeId() == newLeader, "node " + newLeader + " to accept an update");
        step.fact("restoredKey", restored.data().get("key"));
        step.fact("outageMillis", EventData.decimal(restored, "outageMillis"));
        step.fact("restoredSequence", restored.sequence());
        return restored;
    }

    private List<Integer> liveIds() {
        return cluster.nodes().stream().filter(ClusterNode::isUp).map(ClusterNode::id).sorted().toList();
    }
}
