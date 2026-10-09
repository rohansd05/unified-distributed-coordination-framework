package com.udcf.modules.faulttolerance;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.modules.election.ElectionNodeService;
import com.udcf.modules.election.ElectionProperties;
import com.udcf.modules.replication.ConsistencyModel;
import com.udcf.modules.replication.DataItem;
import com.udcf.modules.replication.ReplicationNodeService;
import com.udcf.modules.replication.ReplicationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Experiment 8 on the shared cluster: failover of the replication primary (Exp 5's service,
 * TCP 710k) driven by the shared failure detector (link L2), with the E8a rules. One instance
 * per cluster; E8c's module owns it. Every node runs a {@link FaultToleranceNodeService}.
 *
 * <h2>What happens</h2>
 * <ol>
 *   <li>{@link #start()} starts, on every live node, the election service (its failure detector
 *       is the shared one; E8b never halts or stops it: the node and the election module own it),
 *       the faulttolerance service and the replication service, then appoints the first primary
 *       (epoch 2) with the module-local {@link FailoverRoleSelector} ({@code // TODO(L1)}).</li>
 *   <li>A crash of the primary is stamped: ACTION by {@link #crashPrimary()}, otherwise OBSERVED
 *       inside the faulttolerance service's {@code crash()}.</li>
 *   <li>The first node whose detector suspects the primary records the detection; later
 *       suspicions are ignored, so a failure promotes once. The selector chooses, the
 *       {@link EpochAuthority} issues the epoch, and the chosen node promotes itself on its own
 *       worker (catch-up, {@code becomePrimary}, term record; see
 *       {@link FaultToleranceNodeService#promote}). If it fails, the next candidate is tried.</li>
 *   <li>The first client update the new primary accepts restores the service.</li>
 *   <li>A recovered node rejoins: role query, then demote or adopt, then resynchronise.</li>
 * </ol>
 *
 * <p><b>Instants.</b> Every instant comes from one shared monotonic nano clock ({@link #now()}).
 * <b>Events</b> (module {@value FaultToleranceNodeService#MODULE}) carry the publishing node's
 * Lamport time; cluster-level ones (node 0) the cluster clock. The bus never blocks the caller.</p>
 *
 * <p><b>Threads and locks.</b> The detector listener (election worker), the crash and recover
 * hooks (under a node's lifecycle lock) and the {@code NODE_RECOVERED} handler (event
 * dispatcher) only stamp, call leaf-locked E8a objects, publish and hand work to a node's worker:
 * none of them waits. Services are looked up in this object's own maps, never through another
 * node's lifecycle lock. {@code startLock} serialises start, reset and the recovery handler;
 * {@code promotionLock} serialises the choice of a primary; neither is held while a thread is
 * joined. Client updates run on one thread, {@value #CLIENT_THREAD}; retry delays are scheduled,
 * never slept.</p>
 *
 * <p>Covered by FailoverClusterTest.</p>
 */
public class FailoverCluster implements AutoCloseable {

    static final String CLIENT_THREAD = "udcf-faulttolerance-client";

    private static final Duration EXIT_TIMEOUT = FaultToleranceNodeService.EXIT_TIMEOUT;
    private static final String CLUSTER_MODULE = "cluster";
    private static final String NODE_RECOVERED = "NODE_RECOVERED";
    private static final Logger log = LoggerFactory.getLogger(FailoverCluster.class);

    /** Test seam: lets a test hold a promotion on the chosen node's worker. Production uses {@link #NO_PROBE}. */
    interface PromotionProbe {
        void beforePromotion(int nodeId);
    }

    static final PromotionProbe NO_PROBE = nodeId -> {
    };

    private final Cluster cluster;
    private final FaultToleranceProperties properties;
    private final ElectionProperties electionProperties;
    private final ReplicationProperties replicationProperties;
    private final ClusterEventBus bus;
    private final LongSupplier nanoClock;
    private final PromotionProbe probe;
    private final EpochAuthority authority = new EpochAuthority();
    private final FailoverStateMachine machine;
    private final AcknowledgedLedger ledger = new AcknowledgedLedger();
    private final FaultToleranceMetrics metrics;
    private final RoleQuery roleQuery;
    private final Map<Integer, FaultToleranceNodeService> services = new ConcurrentHashMap<>();
    private final Map<Integer, ReplicationNodeService> replications = new ConcurrentHashMap<>();
    private final Set<Long> recoveryRecorded = ConcurrentHashMap.newKeySet();
    private final ScheduledThreadPoolExecutor client;
    private final ClusterEventBus.Subscription subscription;
    private final Object startLock = new Object();
    private final Object promotionLock = new Object();

    private volatile boolean started;                 // written under startLock
    private volatile long ignoreRecoveriesUpTo;       // written under startLock: bus sequence at the last reset

    /**
     * @throws IllegalStateException if the client retry window is shorter than the worst-case
     *                               failover ({@link FaultToleranceProperties#requireWindowCovers})
     */
    public FailoverCluster(Cluster cluster, FaultToleranceProperties properties, ElectionProperties electionProperties,
                           ReplicationProperties replicationProperties, ClusterEventBus bus, MeterRegistry registry) {
        this(cluster, properties, electionProperties, replicationProperties, bus, registry, System::nanoTime, NO_PROBE);
    }

    /** For tests: an injected nano clock and promotion probe. */
    FailoverCluster(Cluster cluster, FaultToleranceProperties properties, ElectionProperties electionProperties,
                    ReplicationProperties replicationProperties, ClusterEventBus bus, MeterRegistry registry,
                    LongSupplier nanoClock, PromotionProbe probe) {
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.electionProperties = Objects.requireNonNull(electionProperties, "electionProperties must not be null");
        this.replicationProperties = Objects.requireNonNull(replicationProperties,
                "replicationProperties must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        this.probe = Objects.requireNonNull(probe, "probe must not be null");
        properties.requireWindowCovers(electionProperties, replicationProperties);
        this.machine = new FailoverStateMachine(properties.historyLimit());
        this.metrics = new FaultToleranceMetrics(registry);
        this.roleQuery = new RoleQuery(replicationProperties.timeoutMillis());
        this.client = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, CLIENT_THREAD);
            thread.setDaemon(true);
            return thread;
        });
        this.client.setRemoveOnCancelPolicy(true);
        this.subscription = bus.subscribe(this::onClusterEvent);
    }

    // ------------------------------------------------------------------ actions (E8c)

    /**
     * Starts every service Experiment 8 needs on every live node (election, then faulttolerance,
     * then replication), learns their epochs, and appoints the first primary if there is none.
     * The appointment completes asynchronously on the chosen node ({@code PRIMARY_PROMOTED}).
     * Idempotent. A node whose service cannot start is reported ({@code SERVICE_START_FAILED})
     * and left out.
     */
    public void start() {
        synchronized (startLock) {
            started = true;
            for (ClusterNode node : cluster.nodes()) {
                if (node.isUp()) {
                    ensureNode(node);
                }
            }
            observeLiveEpochs();
            if (authority.current().isEmpty()) {
                tryPromote(Set.of());
            }
        }
    }

    /**
     * Crashes the current primary on every protocol, stamping the crash instant ({@link InstantSource#ACTION})
     * immediately before {@link Cluster#crash}.
     *
     * @return the crashed node
     * @throws IllegalStateException if no primary has been appointed
     */
    public int crashPrimary() {
        int primary = machine.primaryId().orElseThrow(() -> new IllegalStateException("No primary has been appointed"));
        long at = now();
        if (apply("crash of node " + primary, machine.onPrimaryCrashed(primary, InstantSource.ACTION, at))
                == FailoverStateMachine.EventResult.APPLIED) {
            publishCrash(primary, InstantSource.ACTION);
        }
        cluster.crash(primary);
        return primary;
    }

    /**
     * Sends one update as a client would: to the known primary, following redirects, discovering
     * the primary and retrying after the configured delay, for at most the configured attempts
     * (E8a {@link UpdateAttempt}). An accepted update is recorded in the acknowledged ledger.
     *
     * @return completes when the update was accepted or the attempts are used up
     */
    public CompletableFuture<UpdateOutcome> submit(SystemUpdate update, ConsistencyModel model) {
        Objects.requireNonNull(update, "update must not be null");
        Objects.requireNonNull(model, "model must not be null");
        CompletableFuture<UpdateOutcome> done = new CompletableFuture<>();
        UpdateAttempt attempt = properties.retryPolicy().begin(currentPrimary().orElse(null));
        try {
            client.execute(() -> step(update, model, attempt, attempt.first(), done));
        } catch (RejectedExecutionException e) {
            done.completeExceptionally(new IllegalStateException("Experiment 8 is closed", e));
        }
        return done;
    }

    /**
     * Forgets every run, the epoch authority and the ledger, and gives every faulttolerance service
     * a clean state (a primary it served as steps down). Starts and stops nothing; recoveries
     * published before it are ignored. After it, {@link #start()} appoints a primary again.
     */
    public void reset() {
        synchronized (startLock) {
            synchronized (promotionLock) {
                ignoreRecoveriesUpTo = bus.publishedCount();
                started = false;
                services.values().forEach(FaultToleranceNodeService::resetState);
                machine.reset();
                authority.reset();
                ledger.clear();
                recoveryRecorded.clear();
            }
        }
    }

    /** Stops the client thread and the bus subscription. The node services stop with the cluster. */
    @Override
    public void close() {
        subscription.close();
        client.shutdownNow();
        try {
            if (!client.awaitTermination(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(CLIENT_THREAD + " did not finish within " + EXIT_TIMEOUT.toMillis() + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ reads (E8c)

    /** The primary client updates go to: appointed, up, and serving (its term record written). */
    public Optional<Integer> currentPrimary() {
        return machine.primaryId().filter(id -> {
            FaultToleranceNodeService service = services.get(id);
            return service != null && service.isRunning() && service.isServing() && cluster.node(id).isUp();
        });
    }

    public FailoverSnapshot snapshot() {
        List<NodeFailoverState> nodes = new ArrayList<>();
        for (ClusterNode node : cluster.nodes()) {
            FaultToleranceNodeService service = services.get(node.id());
            ReplicationNodeService replication = replications.get(node.id());
            boolean acting = replication != null && replication.isPrimary();
            Long epoch = replication == null ? null : epochOf(replication);
            boolean serving = service != null && service.isRunning() && service.isServing() && acting;
            nodes.add(new NodeFailoverState(node.id(), node.status().name(), service != null && service.isRunning(),
                    acting, serving, epoch, service == null ? null : service.believedPrimaryId(),
                    service == null ? null : service.rejoinState()));
        }
        Optional<Integer> primary = machine.primaryId();
        return new FailoverSnapshot(started, machine.phase(), primary.orElse(null),
                primary.isPresent() ? machine.primaryEpoch().getAsLong() : null, authority.highestEpoch(), nodes,
                machine.latestRun().orElse(null), machine.runs(), machine.rejectedEventCount(), ledger.size());
    }

    /** The latest run's intervals, nulls kept; empty before the first failover. */
    public Optional<FailoverMeasurements> measurements() {
        return machine.latestRun().map(FailoverRun::measurements);
    }

    /** Every node's acting replication role and epoch, as the split-brain checker reads them. */
    public List<NodeRoleSnapshot> roleSnapshot() {
        List<NodeRoleSnapshot> snapshot = new ArrayList<>();
        for (ClusterNode node : cluster.nodes()) {
            ReplicationNodeService replication = replications.get(node.id());
            if (replication == null) {
                continue;
            }
            boolean primary = replication.isPrimary();
            snapshot.add(new NodeRoleSnapshot(node.id(), node.isUp(), primary ? FailoverRole.PRIMARY : FailoverRole.BACKUP,
                    epochOf(replication)));
        }
        return snapshot;
    }

    /** E8a {@link SplitBrainChecker} over {@link #roleSnapshot()}. */
    public SplitBrainReport splitBrain() {
        return SplitBrainChecker.check(roleSnapshot());
    }

    /**
     * Acknowledged updates missing from (or older on) the current primary. Not assessed while no
     * primary serves. Asynchronous losses carry the simulated delay reported by the replication service.
     */
    public DataLossReport dataLoss() {
        Map<String, DataItem> store = currentPrimary().map(replications::get).map(replication -> {
            try {
                return (Map<String, DataItem>) replication.snapshot();
            } catch (NodeDownException e) {
                return null;
            }
        }).orElse(null);
        return DataLoss.measure(ledger.snapshot(), store);
    }

    /** Every acknowledged update, in sequence order. */
    public List<AcknowledgedUpdate> acknowledged() {
        return ledger.snapshot();
    }

    // ------------------------------------------------------------------ hooks from the node services

    /** Election worker of {@code observerId}: its detector suspects {@code peerId}. Stamps and hands off only. */
    void onSuspected(int observerId, int peerId, long silentMillis) {
        if (!machine.primaryId().equals(Optional.of(peerId))) {
            return;
        }
        long at = now();
        if (apply("suspicion of node " + peerId, machine.onSuspected(observerId, peerId, at))
                != FailoverStateMachine.EventResult.APPLIED) {
            return;   // a later suspicion of the same failure
        }
        metrics.failureDetected(peerId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("silentMillis", silentMillis);
        data.put("timeoutMillis", electionProperties.heartbeatTimeoutMillis());
        data.put("epoch", machine.primaryEpoch().isPresent() ? machine.primaryEpoch().getAsLong() : null);
        publish(observerId, "FAILURE_DETECTED", peerId, "Node " + observerId + " detected that primary node " + peerId
                + " failed: no heartbeat for " + silentMillis + " ms", data);
        tryPromote(Set.of(peerId));
    }

    /** Election worker of {@code observerId}: a suspected peer answers heartbeats again. */
    void onAlive(int observerId, int peerId) {
        if (machine.primaryId().equals(Optional.of(peerId))) {
            apply("node " + peerId + " alive again", machine.onPrimaryAlive(peerId, now()));
        }
    }

    /** Under the node's lifecycle lock: its faulttolerance service is crashing. */
    void nodeCrashed(int nodeId) {
        long at = now();
        if (machine.primaryId().equals(Optional.of(nodeId))
                && apply("crash of node " + nodeId, machine.onPrimaryCrashed(nodeId, InstantSource.OBSERVED, at))
                == FailoverStateMachine.EventResult.APPLIED) {
            publishCrash(nodeId, InstantSource.OBSERVED);
        }
        promotionFailed(nodeId, "node " + nodeId + " crashed before it took over");
    }

    /** Under the node's lifecycle lock: its faulttolerance service recovered. */
    void nodeRecovered(int nodeId) {
        apply("recovery of node " + nodeId, machine.onOldPrimaryRecovered(nodeId, now()));
    }

    /** The node chosen to take over could not; tries the next candidate. Does nothing unless it was the chosen one. */
    void promotionFailed(int nodeId, String reason) {
        if (machine.onPromotionFailed(nodeId, now()) != FailoverStateMachine.EventResult.APPLIED) {
            return;
        }
        publish(nodeId, "PROMOTION_FAILED", null, "Node " + nodeId + " could not take over as primary: " + reason,
                Map.of("reason", reason));
        Set<Integer> excluded = new HashSet<>();
        excluded.add(nodeId);
        machine.latestRun().filter(run -> run.outcome() == FailoverRun.Outcome.IN_PROGRESS)
                .ifPresent(run -> excluded.add(run.oldPrimaryId()));
        tryPromote(excluded);
    }

    /** A node finished rejoining: if a failure is waiting for a primary, or none was ever appointed, choose now. */
    void nodeReady(int nodeId) {
        if (!started) {
            return;
        }
        if (machine.phase() == FailoverPhase.SUSPECTED) {
            Set<Integer> excluded = new HashSet<>();
            machine.latestRun().ifPresent(run -> excluded.add(run.oldPrimaryId()));
            tryPromote(excluded);
        } else if (authority.current().isEmpty()) {
            tryPromote(Set.of());
        }
    }

    /** The old primary of the latest run finished resynchronising: records its recovery interval once. */
    void oldPrimaryRecovered(int nodeId) {
        machine.latestRun()
                .filter(run -> run.oldPrimaryId() == nodeId && recoveryRecorded.add(run.runId()))
                .ifPresent(metrics::oldPrimaryRecovered);
    }

    // ------------------------------------------------------------------ shared parts for the node services

    long now() {
        return nanoClock.getAsLong();
    }

    FailoverStateMachine machine() {
        return machine;
    }

    EpochAuthority authority() {
        return authority;
    }

    FaultToleranceProperties properties() {
        return properties;
    }

    RoleQuery roleQuery() {
        return roleQuery;
    }

    PromotionProbe probe() {
        return probe;
    }

    List<Integer> nodeIds() {
        return cluster.nodes().stream().map(ClusterNode::id).toList();
    }

    int replicationPort(int nodeId) {
        return cluster.node(nodeId).ports().replication();
    }

    Optional<ReplicationNodeService> replicationOf(int nodeId) {
        return Optional.ofNullable(replications.get(nodeId));
    }

    /** Every other node, crashed ones included (a crashed backup's push simply fails). */
    List<Integer> backupsOf(int nodeId) {
        return nodeIds().stream().filter(id -> id != nodeId).toList();
    }

    /**
     * Peers a new primary catches up from: up, with a running, ready faulttolerance service; not
     * itself and not the old primary. Whether their replication port answers is found out by the
     * catch-up itself: an unreachable or silent one is skipped with an event.
     */
    List<Integer> catchUpSources(int nodeId, Integer oldPrimaryId) {
        List<Integer> sources = new ArrayList<>();
        for (ClusterNode node : cluster.nodes()) {
            FaultToleranceNodeService service = services.get(node.id());
            if (node.id() != nodeId && !Objects.equals(node.id(), oldPrimaryId) && node.isUp() && service != null
                    && service.isRunning() && service.rejoinState() == RejoinState.READY) {
                sources.add(node.id());
            }
        }
        return sources;
    }

    /** Logs an event the state machine rejected as out of order (E8a note 4); returns the result. */
    FailoverStateMachine.EventResult apply(String what, FailoverStateMachine.EventResult result) {
        if (result == FailoverStateMachine.EventResult.REJECTED_OUT_OF_ORDER) {
            log.warn("Experiment 8: the state machine rejected the {} as out of order ({} rejected so far)", what,
                    machine.rejectedEventCount());
        }
        return result;
    }

    /** Publishes under module faulttolerance with the node's Lamport time (node 0: the cluster clock). */
    void publish(int nodeId, String type, Integer peer, String message, Map<String, Object> data) {
        long lamport = nodeId == 0 ? cluster.clusterClock().tick() : cluster.node(nodeId).clock().tick();
        bus.publish(EventDraft.of(FaultToleranceNodeService.MODULE, nodeId, type, lamport)
                .withPeer(peer).withMessage(message).withData(data));
    }

    // ------------------------------------------------------------------ internals

    /** Chooses a primary (TODO(L1) selector), issues its epoch and hands the promotion to its worker. */
    private void tryPromote(Set<Integer> excluded) {
        synchronized (promotionLock) {
            Optional<Integer> chosen = FailoverRoleSelector.select(candidates(), excluded);
            if (chosen.isEmpty()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("excluded", excluded.stream().sorted().toList());
                publish(0, "NO_CANDIDATE", null, "No live node is ready to take over as primary", data);
                return;
            }
            observeLiveEpochs();
            Optional<Promotion> promotion = authority.onLeaderElected(chosen.get(), now());
            if (promotion.isEmpty()) {
                return;
            }
            apply("choice of node " + chosen.get(), machine.onPromotionChosen(promotion.get()));
            FaultToleranceNodeService target = services.get(chosen.get());
            if (target == null || !target.execute("promotion", () -> target.promote(promotion.get()))) {
                promotionFailed(chosen.get(), "node " + chosen.get() + " is down");
            }
        }
    }

    private List<FailoverRoleSelector.Candidate> candidates() {
        List<FailoverRoleSelector.Candidate> candidates = new ArrayList<>();
        for (ClusterNode node : cluster.nodes()) {
            FaultToleranceNodeService service = services.get(node.id());
            ReplicationNodeService replication = replications.get(node.id());
            boolean eligible = node.isUp() && service != null && service.isRunning()
                    && service.rejoinState() == RejoinState.READY && replication != null && replication.isRunning();
            candidates.add(new FailoverRoleSelector.Candidate(node.id(), eligible));
        }
        return candidates;
    }

    private void observeLiveEpochs() {
        replications.values().stream().filter(ReplicationNodeService::isRunning)
                .forEach(replication -> authority.observeEpoch(replication.epoch()));
    }

    /** The epoch a node acts at if primary, else its store epoch. */
    private static long epochOf(ReplicationNodeService replication) {
        return replication.primaryEpoch().orElse(replication.epoch());
    }

    /** Election, then faulttolerance, then replication. Returns false if the node is or went down, or a service failed. */
    private boolean ensureNode(ClusterNode node) {
        String step = ElectionNodeService.NAME;
        try {
            ElectionNodeService.on(node, cluster, electionProperties, bus);
            step = FaultToleranceNodeService.NAME;
            services.put(node.id(), node.ensureService(FaultToleranceNodeService.NAME,
                    n -> new FaultToleranceNodeService(n, this)));
            step = ReplicationNodeService.NAME;
            replications.put(node.id(), ReplicationNodeService.on(node, cluster, replicationProperties, bus));
            return true;
        } catch (NodeDownException e) {
            return false;
        } catch (RuntimeException e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("service", step);
            data.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            publish(node.id(), "SERVICE_START_FAILED", null, "Node " + node.id() + " left out of failover: its "
                    + step + " service could not start", data);
            log.warn("Experiment 8: node {} {} service could not start", node.id(), step, e);
            return false;
        }
    }

    /** Event dispatcher thread: a recovered node rejoins (and joins, if it was down when Experiment 8 started). */
    void onClusterEvent(ClusterEvent event) {
        if (!CLUSTER_MODULE.equals(event.module()) || !NODE_RECOVERED.equals(event.type())) {
            return;
        }
        try {
            synchronized (startLock) {
                if (!started || event.sequence() <= ignoreRecoveriesUpTo) {
                    return;
                }
                ClusterNode node = cluster.node(event.nodeId());
                if (!node.isUp()) {
                    return;
                }
                FaultToleranceNodeService service = services.get(node.id());
                if ((service == null || replications.get(node.id()) == null) && !ensureNode(node)) {
                    return;
                }
                services.get(node.id()).startRejoin();
            }
        } catch (RuntimeException e) {
            log.warn("Experiment 8: node {} could not rejoin", event.nodeId(), e);
        }
    }

    private void publishCrash(int nodeId, InstantSource source) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", source.name());
        data.put("epoch", machine.primaryEpoch().isPresent() ? machine.primaryEpoch().getAsLong() : null);
        publish(nodeId, "PRIMARY_CRASHED", null, source == InstantSource.ACTION
                ? "Primary node " + nodeId + " crashed (crash started by this experiment)"
                : "Primary node " + nodeId + " crashed (crash seen as the node went down)", data);
    }

    // ------------------------------------------------------------------ client updates (client thread)

    private void step(SystemUpdate update, ConsistencyModel model, UpdateAttempt attempt, RetryDecision decision,
                      CompletableFuture<UpdateOutcome> done) {
        try {
            switch (decision.action()) {
                case SEND, DISCOVER -> later(decision.delayMillis(), () -> perform(update, model, attempt, decision, done));
                case GIVE_UP -> done.complete(UpdateOutcome.gaveUp(update, model, attempt.attempts()));
                case DONE -> throw new IllegalStateException("an accepted update is completed where it was accepted");
            }
        } catch (RuntimeException e) {
            done.completeExceptionally(e);
        }
    }

    private void perform(SystemUpdate update, ConsistencyModel model, UpdateAttempt attempt, RetryDecision decision,
                         CompletableFuture<UpdateOutcome> done) {
        try {
            if (decision.action() == RetryDecision.Action.DISCOVER) {
                step(update, model, attempt, attempt.afterDiscovery(currentPrimary()), done);
                return;
            }
            int target = decision.targetNodeId();
            FaultToleranceNodeService service = services.get(target);
            FaultToleranceNodeService.WriteAttempt sent = service == null
                    ? new FaultToleranceNodeService.WriteAttempt(AttemptOutcome.unreachable(target), null)
                    : service.write(update, model);
            if (sent.result() != null) {
                accepted(target, update, sent);
                done.complete(UpdateOutcome.accepted(update, target, attempt.attempts(), sent.result()));
                return;
            }
            step(update, model, attempt, attempt.after(sent.outcome()), done);
        } catch (RuntimeException e) {
            done.completeExceptionally(e);
        }
    }

    private void accepted(int nodeId, SystemUpdate update, FaultToleranceNodeService.WriteAttempt sent) {
        long at = now();
        ledger.record(update.sequence(), sent.result(), at);
        long epoch = sent.result().item().epoch();
        if (apply("write accepted by node " + nodeId, machine.onWriteAccepted(nodeId, epoch, at))
                != FailoverStateMachine.EventResult.APPLIED) {
            return;
        }
        FailoverRun run = machine.latestRun().orElseThrow();
        metrics.runRestored(run);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("epoch", epoch);
        data.put("key", update.key());
        data.put("sequence", update.sequence());
        data.put("outageMillis", run.measurements().outageMillis());
        publish(nodeId, "SERVICE_RESTORED", run.oldPrimaryId(), "Node " + nodeId + " accepted update '" + update.key()
                + "' at epoch " + epoch + ": service restored", data);
    }

    private void later(long delayMillis, Runnable task) {
        if (delayMillis == 0) {
            task.run();
        } else {
            client.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
        }
    }
}
