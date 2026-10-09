package com.udcf.modules.election;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.core.failure.FailureListener;
import com.udcf.core.module.ExperimentModule;
import com.udcf.core.module.ModuleActionGuard;
import com.udcf.core.module.ModuleBusyException;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.election.dto.ConsensusDto;
import com.udcf.modules.election.dto.ElectionNodeDto;
import com.udcf.modules.election.dto.ElectionOverviewDto;
import com.udcf.modules.election.dto.ElectionRoundDto;
import com.udcf.modules.election.dto.ElectionSettingsDto;
import com.udcf.modules.election.dto.StartElectionCommand;
import com.udcf.web.InvalidParameterException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Experiment 4 (lab 4) on the shared cluster: Bully or Ring from any node, the elected leader
 * published as the cluster role {@code LEADER}, the consensus check, and automatic
 * re-election when the leader crashes. Built on every node's {@link ElectionNodeService}
 * (UDP, 700k) and its shared {@link com.udcf.core.failure.FailureDetector} (link L2).
 *
 * <p><b>Lazy start.</b> Nothing starts the election services at boot: the first election
 * request starts them on every live node together. Until then the failure detector does not
 * run. GETs never start a service.</p>
 *
 * <p><b>Rounds.</b> A round opens on a manual start, when a node's detector suspects the
 * leader that node knows (LEADER_FAILURE: that node starts Bully; with every node doing the
 * same, Bully elects the highest live node), or when a recovered node starts Bully (RECOVERY,
 * E4a). Only one round is open at a time; a second manual start answers 409. A round ends
 * ELECTED, measured, once every live node agrees on one live coordinator after an election was
 * seen in it, or TIMED_OUT, unmeasured, after {@code round-timeout-millis}.</p>
 *
 * <p><b>Leader rule.</b> On every ELECTED or COORDINATOR_ACCEPTED event: if every live node
 * agrees on one live coordinator, it becomes the cluster leader ({@link Cluster#assignLeader}).
 * Otherwise the leader is left as it is; the core removes it when the leader's node crashes.</p>
 *
 * <p><b>Threads and locks.</b> Election and detector listeners run on each node's election
 * worker. They only open rounds, enqueue Bully, assign the leader and publish; they never crash,
 * stop or recover anything and never take {@code activationLock}. {@code activationLock}
 * serialises service start-up (first election, the {@code NODE_RECOVERED} handler on the event
 * dispatcher thread) and {@link #reset()}; it is never taken while a node's lifecycle lock or a
 * service's monitor is held. {@code reconcileLock} guards the leader rule and is never held
 * while a thread is joined.</p>
 */
@Component
public class ElectionModule implements ExperimentModule {

    public static final String ID = ElectionNodeService.MODULE;
    public static final String ROUND_STARTED = "ELECTION_ROUND_STARTED";
    public static final String ROUND_FINISHED = "ELECTION_ROUND_FINISHED";

    static final String TITLE = "Bully and Ring Election";
    private static final String CLUSTER_MODULE = "cluster";
    private static final String NODE_RECOVERED = "NODE_RECOVERED";
    private static final Logger log = LoggerFactory.getLogger(ElectionModule.class);

    private final Cluster cluster;
    private final ElectionProperties properties;
    private final ClusterEventBus bus;
    private final ElectionMetrics metrics;
    private final ElectionRoundTracker rounds;
    private final ModuleActionGuard guard = new ModuleActionGuard(ID);
    private final Object activationLock = new Object();
    private final Object reconcileLock = new Object();
    private final Map<Integer, ElectionNodeService> wired = new ConcurrentHashMap<>();
    private final ClusterEventBus.Subscription subscription;

    private volatile boolean activated;              // written under activationLock
    private volatile long ignoreRecoveriesUpTo;      // written under activationLock: bus sequence at the last reset

    @Autowired
    public ElectionModule(Cluster cluster, ElectionProperties properties, ClusterEventBus bus, MeterRegistry registry) {
        this(cluster, properties, bus, registry, System::nanoTime, Clock.systemUTC());
    }

    /** For tests: injected clocks for round durations and timeouts. */
    ElectionModule(Cluster cluster, ElectionProperties properties, ClusterEventBus bus, MeterRegistry registry,
                   LongSupplier nanoClock, Clock wallClock) {
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.metrics = new ElectionMetrics(registry);
        this.rounds = new ElectionRoundTracker(nanoClock, wallClock, properties.roundTimeoutMillis());
        this.subscription = bus.subscribe(this::onClusterEvent);
    }

    /** Stops listening to the event bus. The services themselves stop with the cluster. */
    @PreDestroy
    public void close() {
        subscription.close();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int labNumber() {
        return 4;
    }

    @Override
    public String title() {
        return TITLE;
    }

    /** BUSY while a round is open or an action runs; RUNNING once election services run; else IDLE. */
    @Override
    public ModuleStatus status() {
        expireOverdue();
        if (guard.isBusy() || rounds.current().isPresent()) {
            return ModuleStatus.BUSY;
        }
        return anyServiceRunning() ? ModuleStatus.RUNNING : ModuleStatus.IDLE;
    }

    /**
     * Clean slate: halts every election service (joining its threads, dropping every pending
     * timer), forgets the rounds and removes the leader, then gives every service fresh
     * algorithms and a fresh failure-detector view and reopens those that were running. Two
     * passes, so no message of an old election reaches a fresh node. Recoveries published before
     * the reset (the cluster reset recovers every node first) are ignored. Starts no election.
     */
    @Override
    public void reset() {
        try (ModuleActionGuard.ActionTicket ignored = guard.begin("Reset")) {
            synchronized (activationLock) {
                ignoreRecoveriesUpTo = bus.publishedCount();
                Map<ElectionNodeService, Boolean> wasRunning = new LinkedHashMap<>();
                for (ClusterNode node : cluster.nodes()) {
                    ElectionNodeService.find(node).ifPresent(service -> wasRunning.put(service, service.halt()));
                }
                synchronized (reconcileLock) {
                    rounds.clear();
                    cluster.assignLeader(null);
                }
                wasRunning.forEach(ElectionNodeService::resetState);
            }
        }
    }

    /** For tests: holding the guard makes a start answer 409. */
    ModuleActionGuard guard() {
        return guard;
    }

    // ------------------------------------------------------------------ actions

    /**
     * Starts Bully or Ring from a node; the outcome follows asynchronously (events, overview).
     * Starts the election services on every live node first if they are not running yet.
     *
     * @throws InvalidParameterException missing algorithm or node id (400)
     * @throws com.udcf.core.cluster.UnknownNodeException no such node (404)
     * @throws NodeDownException the node is crashed (409)
     * @throws ModuleBusyException a round is open or another action runs (409)
     */
    public ElectionRoundDto startElection(StartElectionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.algorithm() == null) {
            throw new InvalidParameterException("algorithm", "is required");
        }
        if (command.nodeId() == null || command.nodeId() < 1) {
            throw new InvalidParameterException("nodeId", "must be a node id, 1 or more");
        }
        int nodeId = command.nodeId();
        ClusterNode node = cluster.node(nodeId);
        ElectionAlgorithm algorithm = command.algorithm();
        String action = describe(algorithm) + " election from node " + nodeId;
        try (ModuleActionGuard.ActionTicket ignored = guard.begin(action)) {
            synchronized (activationLock) {
                expireOverdue();
                Optional<ElectionRound> open = rounds.current();
                if (open.isPresent()) {
                    throw new ModuleBusyException(ID, describe(open.get()));
                }
                if (!node.isUp()) {
                    throw new NodeDownException(nodeId);
                }
                ensureServices();
                ElectionRound round = rounds.open(algorithm, RoundTrigger.MANUAL, nodeId)
                        .orElseThrow(() -> new ModuleBusyException(ID, describe(rounds.current().orElseThrow())));
                publishRoundStarted(round);
                ElectionNodeService service = wired.get(nodeId);
                try {
                    if (service == null) {
                        throw new NodeDownException(nodeId);
                    }
                    if (algorithm == ElectionAlgorithm.BULLY) {
                        service.startBully();
                    } else {
                        service.startRing();
                    }
                } catch (NodeDownException e) {
                    rounds.cancel(round.roundId());
                    throw e;
                }
                return ElectionRoundDto.from(round);
            }
        }
    }

    // ------------------------------------------------------------------ reads (never start a service)

    public ElectionOverviewDto overview() {
        expireOverdue();
        List<ElectionNodeDto> nodes = new ArrayList<>();
        for (ClusterNode node : cluster.nodes()) {
            Optional<ElectionNodeService> running = runningService(node);
            nodes.add(new ElectionNodeDto(node.id(), node.status().name(), running.isPresent(),
                    running.map(ElectionNodeService::coordinatorId).orElse(null),
                    running.map(s -> s.failureDetector().suspectedPeers().stream().sorted().toList()).orElse(List.of()),
                    node.ports().election()));
        }
        return new ElectionOverviewDto(status().name(), cluster.leaderId().orElse(null), activated, List.copyOf(nodes),
                consensus(), ElectionRoundDto.from(rounds.current().orElse(null)),
                ElectionRoundDto.from(rounds.last().orElse(null)), ElectionSettingsDto.from(properties));
    }

    /** Every live node's coordinator compared (E4a ConsensusChecker), plus whether that coordinator is alive. */
    public ConsensusDto consensus() {
        ConsensusResult result = ConsensusChecker.check(participants());
        Integer coordinator = result.coordinatorId();
        boolean alive = coordinator != null && isLive(coordinator);
        return new ConsensusDto(result.reached(), coordinator, alive, result.reached() && alive,
                List.copyOf(result.disagreeingNodes()));
    }

    // ------------------------------------------------------------------ listeners (election worker threads)

    /** Election worker of {@code nodeId}. */
    void onElectionEvent(int nodeId, ElectionEvent event) {
        switch (event.type()) {
            // E4a: a recovering node's Bully starts an election. Ring emits RECOVER too; the round opens once.
            case RECOVER -> openAutomatic(RoundTrigger.RECOVERY, nodeId);
            case ELECTED -> rounds.markElected();
            default -> {
            }
        }
        if (event.type() == ElectionEventType.ELECTED || event.type() == ElectionEventType.COORDINATOR_ACCEPTED) {
            reconcile(nodeId);
        }
    }

    /**
     * Failure detector of {@code nodeId} suspects {@code peerId} (election worker of
     * {@code nodeId}). If that is the leader this node knows, open a LEADER_FAILURE round (once,
     * however many nodes call this) and start Bully here. Only enqueues work.
     */
    void onPeerSuspected(int nodeId, int peerId) {
        ElectionNodeService service = wired.get(nodeId);
        if (service == null || !Objects.equals(service.coordinatorId(), peerId)) {
            return;
        }
        openAutomatic(RoundTrigger.LEADER_FAILURE, nodeId);
        try {
            service.startBully();
        } catch (NodeDownException e) {
            log.debug("Node {} went down before its re-election started", nodeId);
        }
    }

    /** Event dispatcher thread (asynchronous; holds no node lock). */
    void onClusterEvent(ClusterEvent event) {
        if (!CLUSTER_MODULE.equals(event.module()) || !NODE_RECOVERED.equals(event.type())) {
            return;
        }
        try {
            synchronized (activationLock) {
                if (!activated || event.sequence() <= ignoreRecoveriesUpTo) {
                    return;
                }
                ClusterNode node = cluster.node(event.nodeId());
                if (!node.isUp() || ElectionNodeService.find(node).isPresent()) {
                    return;   // a registered service is recovered by the node itself, and its Bully starts (E4a)
                }
                // Crashed when the services first started: join now, and run Bully as a recovered node does.
                ElectionNodeService service = wire(ElectionNodeService.on(node, cluster, properties, bus));
                openAutomatic(RoundTrigger.RECOVERY, node.id());
                service.startBully();
            }
        } catch (RuntimeException e) {
            log.warn("Election could not start on recovered node {}", event.nodeId(), e);
        }
    }

    // ------------------------------------------------------------------ internals

    private void ensureServices() {   // caller holds activationLock
        for (ClusterNode node : cluster.nodes()) {
            if (!node.isUp()) {
                continue;
            }
            try {
                wire(ElectionNodeService.on(node, cluster, properties, bus));
            } catch (NodeDownException e) {
                log.debug("Node {} went down while the election services started", node.id());
            }
        }
        activated = true;
    }

    private ElectionNodeService wire(ElectionNodeService service) {
        int nodeId = service.getNodeId();
        if (wired.putIfAbsent(nodeId, service) == null) {
            service.addElectionListener(event -> onElectionEvent(nodeId, event));
            service.failureDetector().addListener(new FailureListener() {
                @Override
                public void onSuspected(int peerId, long silentMillis) {
                    onPeerSuspected(nodeId, peerId);
                }

                @Override
                public void onAlive(int peerId, long silentMillis) {
                    // Nothing to do: a recovered node runs its own Bully election.
                }
            });
        }
        return service;
    }

    private void openAutomatic(RoundTrigger trigger, int nodeId) {
        rounds.open(ElectionAlgorithm.BULLY, trigger, nodeId).ifPresent(this::publishRoundStarted);
    }

    /** The leader rule; completes the open round on agreement. */
    private void reconcile(int observerNodeId) {
        synchronized (reconcileLock) {
            ConsensusResult result = ConsensusChecker.check(participants());
            Integer coordinator = result.coordinatorId();
            if (result.reached() && coordinator != null && isLive(coordinator)) {
                cluster.assignLeader(coordinator);
                rounds.complete(coordinator).ifPresent(done -> {
                    metrics.record(done);
                    publishRoundFinished(done, observerNodeId);
                });
            }
        }
        expireOverdue();
    }

    private void expireOverdue() {
        rounds.expireIfOverdue().ifPresent(done -> publishRoundFinished(done, done.initiatorNodeId()));
    }

    private List<ElectionParticipant> participants() {
        List<ElectionParticipant> participants = new ArrayList<>();
        for (ClusterNode node : cluster.nodes()) {
            Integer coordinator = runningService(node).map(ElectionNodeService::coordinatorId).orElse(null);
            participants.add(new Participant(node.id(), !node.isUp(), coordinator));
        }
        return participants;
    }

    private boolean isLive(int nodeId) {
        ClusterNode node = cluster.node(nodeId);
        return node.isUp() && runningService(node).isPresent();
    }

    private static Optional<ElectionNodeService> runningService(ClusterNode node) {
        return ElectionNodeService.find(node).filter(ElectionNodeService::isRunning);
    }

    private boolean anyServiceRunning() {
        return cluster.nodes().stream().anyMatch(node -> runningService(node).isPresent());
    }

    private void publishRoundStarted(ElectionRound round) {
        ClusterNode node = cluster.node(round.initiatorNodeId());
        bus.publish(EventDraft.of(ID, node.id(), ROUND_STARTED, node.clock().tick())
                .withMessage(describe(round) + " started")
                .withData(roundData(round)));
    }

    private void publishRoundFinished(ElectionRound round, int observerNodeId) {
        ClusterNode node = cluster.node(observerNodeId);
        String message = round.outcome() == RoundOutcome.ELECTED
                ? describe(round) + ": every live node agrees on node " + round.leaderId()
                : describe(round) + ": no agreement within " + properties.roundTimeoutMillis() + " ms";
        bus.publish(EventDraft.of(ID, node.id(), ROUND_FINISHED, node.clock().tick())
                .withPeer(round.leaderId())
                .withMessage(message)
                .withData(roundData(round)));
    }

    private static Map<String, Object> roundData(ElectionRound round) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roundId", round.roundId());
        data.put("algorithm", round.algorithm().name());
        data.put("trigger", round.trigger().name());
        data.put("initiatorNodeId", round.initiatorNodeId());
        data.put("outcome", round.outcome().name());
        data.put("leaderId", round.leaderId());
        data.put("durationMillis", round.durationMillis());
        return data;
    }

    private static String describe(ElectionAlgorithm algorithm) {
        return algorithm == ElectionAlgorithm.BULLY ? "Bully" : "Ring";
    }

    private static String describe(ElectionRound round) {
        String cause = switch (round.trigger()) {
            case MANUAL -> "started by hand";
            case LEADER_FAILURE -> "after the leader failed";
            case RECOVERY -> "after a recovery";
        };
        return describe(round.algorithm()) + " election round " + round.roundId() + " from node "
                + round.initiatorNodeId() + " (" + cause + ")";
    }

    /** A node's view for the consensus check: crashed, or its election service's coordinator (null if none). */
    private record Participant(int nodeId, boolean crashed, Integer coordinatorId) implements ElectionParticipant {

        @Override
        public int getNodeId() {
            return nodeId;
        }

        @Override
        public boolean isCrashed() {
            return crashed;
        }

        @Override
        public Integer getCoordinatorId() {
            return coordinatorId;
        }
    }
}
