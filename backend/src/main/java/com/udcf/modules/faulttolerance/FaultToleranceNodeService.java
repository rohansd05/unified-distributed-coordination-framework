package com.udcf.modules.faulttolerance;

import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.cluster.NodeService;
import com.udcf.core.failure.FailureDetector;
import com.udcf.core.failure.FailureListener;
import com.udcf.modules.election.ElectionNodeService;
import com.udcf.modules.replication.ApplyResult;
import com.udcf.modules.replication.CatchUpReport;
import com.udcf.modules.replication.ConsistencyModel;
import com.udcf.modules.replication.DataItem;
import com.udcf.modules.replication.NotPrimaryException;
import com.udcf.modules.replication.PushOutcome;
import com.udcf.modules.replication.ReplicationNodeService;
import com.udcf.modules.replication.WriteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Experiment 8 on one node: the node's "faulttolerance" service, which applies the E8a rules to
 * this node's real events. It owns no socket and no port: it watches the node's shared failure
 * detector (link L2, on the election channel), sends its role query over the replication channel
 * ({@link RoleQuery}), and promotes, demotes and resynchronises the node's replication service.
 * The cluster-wide parts (one {@link EpochAuthority}, one {@link FailoverStateMachine}, the
 * ledger, the shared nano clock) are its {@link FailoverCluster}'s.
 *
 * <p><b>Threads (hard rule 7).</b> The detector calls its {@link FailureListener} on the
 * election worker; the listener only hands the suspicion to {@link FailoverCluster}, which stamps
 * it, applies it to the state machine (leaf locks) and hands the promotion to the chosen node's
 * worker. Every blocking step (catch-up, {@code becomePrimary}, the term record write, the role
 * query, resynchronisation) runs on this node's single worker, {@code udcf-faulttolerance-n<k>-worker},
 * or on its virtual query threads ({@code udcf-faulttolerance-n<k>-query-*}). Asynchronous
 * replication outcomes arrive on replication threads and are handed to the worker too.</p>
 *
 * <p><b>Lifecycle (R10).</b> It starts, crashes, recovers and stops with its node through
 * {@link ClusterNode#ensureService}. {@code start} needs the node's election service (its
 * detector). {@code crash} reports the crash (stamped OBSERVED unless Experiment 8 started it),
 * steps the replication service down (a crashed node holds no role, so a recovered old primary
 * can never be seen acting as a second primary), then shuts down and joins its threads, each
 * wait bounded at 5 s ({@link IllegalStateException} if one is still alive). Every socket read
 * it starts is bounded by the replication timeout, so nothing it waits for outlives a crash by
 * more than that. {@code recover} reopens the threads and marks the node REJOINING: it never
 * serves as primary until its role query returned and it resynchronised; the rejoin itself starts
 * when the cluster publishes {@code NODE_RECOVERED}, after every service of the node is back.</p>
 *
 * <p>Covered by FaultToleranceNodeServiceTest and FailoverClusterTest.</p>
 */
public class FaultToleranceNodeService implements NodeService {

    public static final String NAME = "faulttolerance";
    public static final String MODULE = "faulttolerance";

    static final Duration EXIT_TIMEOUT = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(FaultToleranceNodeService.class);

    /** One send of a client update: the write result if accepted, otherwise the outcome for the retry policy. */
    record WriteAttempt(AttemptOutcome outcome, WriteResult result) {
    }

    private final ClusterNode node;
    private final FailoverCluster owner;
    private final String threadPrefix;
    private final FailureListener listener = new FailureListener() {
        @Override
        public void onSuspected(int peerId, long silentMillis) {
            if (running) {
                owner.onSuspected(node.id(), peerId, silentMillis);
            }
        }

        @Override
        public void onAlive(int peerId, long silentMillis) {
            if (running) {
                owner.onAlive(node.id(), peerId);
            }
        }
    };

    private volatile boolean running;
    private volatile ScheduledThreadPoolExecutor worker;
    private volatile List<Thread> workerThreads = List.of();
    private volatile ExecutorService queries;
    private FailureDetector.Registration registration;   // guarded by this

    private volatile boolean serving;
    private volatile boolean wasPrimaryAtCrash;
    private volatile RejoinState rejoin = RejoinState.READY;

    FaultToleranceNodeService(ClusterNode node, FailoverCluster owner) {
        this.node = Objects.requireNonNull(node, "node must not be null");
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.threadPrefix = "udcf-faulttolerance-n" + node.id() + "-";
    }

    /** The node's service if it is registered; never starts one. */
    public static Optional<FaultToleranceNodeService> find(ClusterNode node) {
        return node.service(NAME).map(FaultToleranceNodeService.class::cast);
    }

    // ------------------------------------------------------------------ NodeService

    @Override
    public String name() {
        return NAME;
    }

    /**
     * Opens the worker and registers with the node's failure detector.
     *
     * @throws IllegalStateException if the node's election service is not registered (its detector is the shared one)
     */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        ElectionNodeService election = ElectionNodeService.find(node).orElseThrow(() -> new IllegalStateException(
                "Node " + node.id() + ": the election service must start first; its failure detector is the shared one"
                        + " (link L2)"));
        open();
        if (registration == null) {
            registration = election.failureDetector().addListener(listener);
        }
    }

    @Override
    public synchronized void crash() {
        if (!running) {
            return;
        }
        Optional<ReplicationNodeService> replication = owner.replicationOf(node.id());
        boolean acting = replication.map(ReplicationNodeService::isPrimary).orElse(false);
        running = false;
        serving = false;
        rejoin = RejoinState.REJOINING;
        wasPrimaryAtCrash = acting;
        owner.nodeCrashed(node.id());
        // A crashed node holds no role (as ClusterNode clears its roles): when it comes back it is not primary.
        replication.filter(ReplicationNodeService::isPrimary).ifPresent(ReplicationNodeService::stepDown);
        IllegalStateException failure = close();
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public synchronized void recover() {
        if (running) {
            return;
        }
        rejoin = RejoinState.REJOINING;
        open();
        owner.nodeRecovered(node.id());
    }

    @Override
    public synchronized void stop() {
        serving = false;
        IllegalStateException failure = close();
        if (registration != null) {
            registration.close();
            registration = null;
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ state

    public int nodeId() {
        return node.id();
    }

    /** True while Experiment 8 sends client updates to this node: promoted and its term record written. */
    public boolean isServing() {
        return serving;
    }

    public RejoinState rejoinState() {
        return rejoin;
    }

    /** Whether the node acted as primary when it last crashed (the role it reports to its role query). */
    public boolean wasPrimaryAtCrash() {
        return wasPrimaryAtCrash;
    }

    /**
     * The primary named by this node's own replicated term record at its store epoch; null if it
     * holds none at that epoch or the node is down.
     */
    public Integer believedPrimaryId() {
        Optional<ReplicationNodeService> replication = owner.replicationOf(node.id());
        if (replication.isEmpty() || !replication.get().isRunning()) {
            return null;
        }
        try {
            ReplicationNodeService service = replication.get();
            long epoch = service.epoch();
            return service.get(RoleQuery.TERM_KEY).filter(item -> item.epoch() == epoch)
                    .map(DataItem::originNode).orElse(null);
        } catch (NodeDownException e) {
            return null;
        }
    }

    /** Clean slate for a module reset: not serving, never crashed, ready. Steps down a primary it was serving as. */
    synchronized void resetState() {
        if (serving) {
            owner.replicationOf(node.id()).ifPresent(ReplicationNodeService::stepDown);
        }
        serving = false;
        wasPrimaryAtCrash = false;
        rejoin = RejoinState.READY;
    }

    /** Package-visible for tests: true once every thread of the last generation has finished. */
    boolean threadsTerminated() {
        ScheduledThreadPoolExecutor current = worker;
        ExecutorService currentQueries = queries;
        return (current == null || current.isTerminated()) && (currentQueries == null || currentQueries.isTerminated());
    }

    // ------------------------------------------------------------------ promotion (worker thread)

    /**
     * Takes over as primary at {@code promotion.epoch()}: catches up from every other live, ready
     * peer except the old primary (in parallel, bounded by the catch-up timeout; a peer not done by
     * then is skipped with an event), acts as primary at the new epoch, records it, and writes the
     * term record synchronously to every backup (each push bounded by the replication timeouts).
     * Only then does it serve client updates. The first appointment skips the catch-up.
     */
    void promote(Promotion promotion) {
        int id = node.id();
        long epoch = promotion.epoch();
        owner.probe().beforePromotion(id);
        ReplicationNodeService replication = owner.replicationOf(id).orElse(null);
        if (!running || replication == null || !replication.isRunning()) {
            owner.promotionFailed(id, "node " + id + " is down");
            return;
        }
        List<Integer> caughtUp = new ArrayList<>();
        List<Integer> skipped = new ArrayList<>();
        if (promotion.previousPrimaryId() != null && !catchUp(replication, promotion, caughtUp, skipped)) {
            return;   // interrupted: the node is going down, and its crash reports the failed promotion
        }
        try {
            replication.becomePrimary(epoch);
        } catch (NodeDownException | NotPrimaryException e) {
            owner.promotionFailed(id, e.getMessage());
            return;
        }
        owner.apply("promoted node " + id, owner.machine().onPromoted(id, epoch, owner.now()));
        try {
            replication.write(RoleQuery.TERM_KEY, RoleQuery.termValue(id, epoch), ConsistencyModel.SYNCHRONOUS,
                    owner.backupsOf(id));
        } catch (NodeDownException e) {
            return;   // crashed while announcing: its crash starts the next failover
        } catch (NotPrimaryException e) {
            publish("PROMOTION_FAILED", null, "Node " + id + " could not announce epoch " + epoch
                    + ": it already learned a newer epoch", data("epoch", epoch, "reason", e.getMessage()));
            return;
        }
        serving = true;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("epoch", epoch);
        data.put("previousPrimaryId", promotion.previousPrimaryId());
        data.put("caughtUpFrom", List.copyOf(caughtUp));
        data.put("skipped", List.copyOf(skipped));
        publish("PRIMARY_PROMOTED", promotion.previousPrimaryId(), promotion.previousPrimaryId() == null
                ? "Node " + id + " is the first primary, at epoch " + epoch
                : "Node " + id + " took over from node " + promotion.previousPrimaryId() + " at epoch " + epoch, data);
    }

    /** @return false if interrupted (the node is going down) */
    private boolean catchUp(ReplicationNodeService replication, Promotion promotion, List<Integer> caughtUp,
                            List<Integer> skipped) {
        long timeout = owner.properties().promotion().catchUpTimeoutMillis();
        Map<Integer, CompletableFuture<CatchUpReport>> futures = new LinkedHashMap<>();
        for (int peer : owner.catchUpSources(node.id(), promotion.previousPrimaryId())) {
            try {
                futures.put(peer, CompletableFuture.supplyAsync(() -> replication.catchUpFrom(peer), queries));
            } catch (RejectedExecutionException e) {
                return false;
            }
        }
        try {
            CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new))
                    .get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException | ExecutionException e) {
            // Judged per peer below.
        }
        futures.forEach((peer, future) -> {
            String reason;
            if (!future.isDone()) {
                reason = "no answer within " + timeout + " ms";
            } else {
                CatchUpReport report = future.isCompletedExceptionally() ? null : future.join();
                reason = report == null ? "it failed" : report.failure().orElse(null);
            }
            if (reason == null) {
                caughtUp.add(peer);
                return;
            }
            skipped.add(peer);
            publish("CATCH_UP_SKIPPED", peer, "Node " + node.id() + " skipped catching up from node " + peer
                    + " before taking over: " + reason, data("epoch", promotion.epoch(), "reason", reason));
        });
        return true;
    }

    // ------------------------------------------------------------------ rejoin (worker thread)

    /** Marks the node as rejoining and queues its first role query. False if the node is down. */
    boolean startRejoin() {
        rejoin = RejoinState.REJOINING;
        serving = false;
        return execute("rejoin", () -> rejoin(1));
    }

    /**
     * The role query and what follows it (E8a {@link EpochRules#resolveRejoin}): a former primary
     * that sees a higher epoch demotes and resynchronises from the new primary; a backup adopts the
     * epoch and resynchronises; with no answer the node stays non-primary and asks again later.
     */
    void rejoin(int attempt) {
        int id = node.id();
        ReplicationNodeService replication = owner.replicationOf(id).orElse(null);
        if (!running || replication == null) {
            return;
        }
        FailoverRole ownRole = wasPrimaryAtCrash ? FailoverRole.PRIMARY : FailoverRole.BACKUP;
        long ownEpoch = replication.epoch();
        List<Integer> peers = owner.nodeIds().stream().filter(peer -> peer != id).toList();
        RoleQuery.Answers answers;
        try {
            answers = owner.roleQuery().ask(id, node.clock(), peers, owner::replicationPort, queries);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!running) {
            return;
        }
        answers.reports().forEach(report -> owner.authority().observeEpoch(report.epoch()));
        RejoinDecision decision = EpochRules.resolveRejoin(id, ownRole, ownEpoch, answers.reports());
        int maxAttempts = owner.properties().roleQuery().maxAttempts();
        if (decision.action() == RejoinDecision.Action.NO_ANSWER) {
            rejoin = RejoinState.WAITING_FOR_ANSWER;
            boolean again = attempt < maxAttempts;
            long delay = owner.properties().roleQuery().retryDelayMillis();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("attempt", attempt);
            data.put("maxAttempts", maxAttempts);
            data.put("asked", peers);
            data.put("ownRole", ownRole.name());
            data.put("ownEpoch", ownEpoch);
            data.put("retryInMillis", again ? delay : null);
            publish("ROLE_QUERY_FAILED", null, "No node answered node " + id + "'s role query (attempt " + attempt
                    + " of " + maxAttempts + "); it stays non-primary" + (again ? " and asks again in " + delay + " ms" : ""),
                    data);
            if (again) {
                schedule("rejoin", () -> rejoin(attempt + 1), delay);
            }
            return;
        }
        publishRoleQuery(decision, ownRole, ownEpoch, answers);
        if (decision.resync()) {
            replication.observeEpoch(decision.epoch());
            if (decision.action() == RejoinDecision.Action.DEMOTE_AND_RESYNC) {
                owner.apply("demoted node " + id, owner.machine().onDemoted(id, decision.epoch(), owner.now()));
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("previousEpoch", ownEpoch);
                data.put("epoch", decision.epoch());
                data.put("primaryId", decision.primaryId());
                publish("OLD_PRIMARY_DEMOTED", decision.primaryId(), "Node " + id + " was primary at epoch " + ownEpoch
                        + "; it saw epoch " + decision.epoch() + " and stays a backup", data);
            }
            wasPrimaryAtCrash = false;
        } else if (ownRole == FailoverRole.PRIMARY) {
            if (resume(replication, ownEpoch)) {
                return;
            }
            wasPrimaryAtCrash = false;
        }
        Integer source = decision.primaryId() == null || decision.primaryId() == id ? null : decision.primaryId();
        resync(replication, source, attempt, maxAttempts);
    }

    /** No peer knows a newer epoch: a former primary resumes only if the promotion in force still names it. */
    private boolean resume(ReplicationNodeService replication, long ownEpoch) {
        int id = node.id();
        Optional<Promotion> inForce = owner.authority().current();
        if (inForce.isEmpty() || inForce.get().nodeId() != id) {
            return false;
        }
        long epoch = inForce.get().epoch();
        try {
            replication.becomePrimary(epoch);
        } catch (NodeDownException | NotPrimaryException e) {
            return false;
        }
        wasPrimaryAtCrash = false;
        rejoin = RejoinState.READY;
        serving = true;
        publish("PRIMARY_RESUMED", null, "Node " + id + " is still primary at epoch " + epoch
                + ": no node knows a newer epoch", data("epoch", epoch, "ownEpoch", ownEpoch));
        return true;
    }

    private void resync(ReplicationNodeService replication, Integer source, int attempt, int maxAttempts) {
        int id = node.id();
        if (source == null) {
            rejoin = RejoinState.READY;   // no primary named yet: nothing to copy
            owner.nodeReady(id);
            return;
        }
        CatchUpReport report;
        try {
            report = replication.catchUpFrom(source);
        } catch (NodeDownException e) {
            report = null;
        }
        if (!running) {
            return;
        }
        if (report != null && report.completed()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pulled", report.merged().get().pushed());
            data.put("applied", report.merged().get().applied());
            data.put("sourceEpoch", report.sourceEpoch().isPresent() ? report.sourceEpoch().getAsLong() : null);
            data.put("storeEpoch", replication.epoch());
            publish("RESYNCHRONISED", source, "Node " + id + " copied node " + source + "'s store: "
                    + report.merged().get().applied() + " of " + report.merged().get().pushed() + " items applied", data);
            if (owner.machine().onResynchronised(id, owner.now()) == FailoverStateMachine.EventResult.APPLIED) {
                owner.oldPrimaryRecovered(id);
            }
            rejoin = RejoinState.READY;
            owner.nodeReady(id);
            return;
        }
        String reason = report == null ? "its replication service is not running" : report.failure().orElse("unknown");
        boolean again = attempt < maxAttempts;
        long delay = owner.properties().roleQuery().retryDelayMillis();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason);
        data.put("attempt", attempt);
        data.put("maxAttempts", maxAttempts);
        data.put("retryInMillis", again ? delay : null);
        publish("RESYNC_FAILED", source, "Node " + id + " could not copy node " + source + "'s store: " + reason
                + "; it stays non-primary", data);
        if (again) {
            schedule("rejoin", () -> rejoin(attempt + 1), delay);
        }
    }

    private void publishRoleQuery(RejoinDecision decision, FailoverRole ownRole, long ownEpoch, RoleQuery.Answers answers) {
        int id = node.id();
        String outcome = switch (decision.action()) {
            case DEMOTE_AND_RESYNC -> "a newer epoch " + decision.epoch() + " exists, so it steps down";
            case ADOPT_AND_RESYNC -> "a newer epoch " + decision.epoch() + " exists, so it adopts it";
            case STAY -> "no node knows a newer epoch than " + ownEpoch;
            case NO_ANSWER -> "no node answered";
        };
        String primary = decision.primaryId() == null ? "no primary is named" : "node " + decision.primaryId() + " is primary";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", decision.action().name());
        data.put("epoch", decision.epoch());
        data.put("primaryId", decision.primaryId());
        data.put("ownRole", ownRole.name());
        data.put("ownEpoch", ownEpoch);
        data.put("replies", answers.describe());
        data.put("silent", answers.silent());
        publish("ROLE_QUERY", null, "Node " + id + " asked " + (answers.reports().size() + answers.silent().size())
                + " nodes who is in charge (" + answers.reports().size() + " answered): " + outcome + "; " + primary, data);
    }

    // ------------------------------------------------------------------ client updates (client thread)

    /**
     * Sends one client update to this node. Accepted only while it serves as primary; otherwise
     * not-primary (naming the primary of its own term record) or unreachable.
     */
    WriteAttempt write(SystemUpdate update, ConsistencyModel model) {
        int id = node.id();
        ReplicationNodeService replication = owner.replicationOf(id).orElse(null);
        if (!running || replication == null || !replication.isRunning()) {
            return new WriteAttempt(AttemptOutcome.unreachable(id), null);
        }
        if (!serving) {
            return new WriteAttempt(AttemptOutcome.notPrimary(id, hint()), null);
        }
        long epoch = replication.primaryEpoch().orElse(0);
        try {
            WriteResult result = replication.write(update.key(), update.value(), model, owner.backupsOf(id));
            if (model == ConsistencyModel.ASYNCHRONOUS) {
                result.replication().whenComplete((outcomes, error) -> {
                    if (outcomes != null) {
                        onAsyncOutcomes(result.item(), outcomes);
                    }
                });
            }
            return new WriteAttempt(AttemptOutcome.accepted(id), result);
        } catch (NotPrimaryException e) {
            serving = false;
            if (epoch > 0 && e.storeEpoch() > epoch) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("key", update.key());
                data.put("senderEpoch", epoch);
                data.put("backupEpoch", null);
                data.put("knownEpoch", e.storeEpoch());
                publish("STALE_EPOCH_REFUSED", null, "Update '" + update.key() + "' from node " + id + " at epoch " + epoch
                        + " was refused: the cluster already knows epoch " + e.storeEpoch(), data);
            }
            return new WriteAttempt(AttemptOutcome.notPrimary(id, hint()), null);
        } catch (NodeDownException e) {
            return new WriteAttempt(AttemptOutcome.unreachable(id), null);
        }
    }

    /** Replication thread: hands any stale-epoch refusal to the worker. */
    private void onAsyncOutcomes(DataItem item, List<PushOutcome> outcomes) {
        List<PushOutcome> refused = outcomes.stream()
                .filter(o -> o.result().equals(Optional.of(ApplyResult.STALE_EPOCH))).toList();
        if (refused.isEmpty()) {
            return;
        }
        execute("stale epoch refusal", () -> {
            serving = false;
            for (PushOutcome outcome : refused) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("key", item.key());
                data.put("senderEpoch", item.epoch());
                data.put("backupEpoch", outcome.backupEpoch().isPresent() ? outcome.backupEpoch().getAsLong() : null);
                data.put("knownEpoch", outcome.backupEpoch().isPresent() ? outcome.backupEpoch().getAsLong() : null);
                publish("STALE_EPOCH_REFUSED", outcome.backupId(), "Node " + outcome.backupId() + " refused update '"
                        + item.key() + "' from node " + node.id() + " at epoch " + item.epoch()
                        + ": it already knows a newer epoch", data);
            }
        });
    }

    private Integer hint() {
        Integer believed = believedPrimaryId();
        return believed == null || believed == node.id() ? null : believed;
    }

    // ------------------------------------------------------------------ threads

    /** Runs {@code task} on the worker; false (and nothing runs) if the node is down. */
    boolean execute(String what, Runnable task) {
        ScheduledThreadPoolExecutor current = worker;
        if (!running || current == null) {
            return false;
        }
        try {
            current.execute(guarded(what, task));
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    private void schedule(String what, Runnable task, long delayMillis) {
        ScheduledThreadPoolExecutor current = worker;
        if (!running || current == null) {
            return;
        }
        try {
            current.schedule(guarded(what, task), delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            log.debug("Node {}: worker stopped; dropped {}", node.id(), what);
        }
    }

    private Runnable guarded(String what, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("Node {}: fault tolerance {} failed", node.id(), what, e);
            }
        };
    }

    /** Creates this generation's threads, then sets {@code running}. */
    private void open() {
        List<Thread> threads = new CopyOnWriteArrayList<>();
        ScheduledThreadPoolExecutor fresh = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, threadPrefix + "worker");
            thread.setDaemon(true);
            threads.add(thread);
            return thread;
        });
        fresh.setRemoveOnCancelPolicy(true);
        worker = fresh;
        workerThreads = threads;
        queries = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(threadPrefix + "query-", 0).factory());
        running = true;
    }

    /** Shuts the threads down and waits for them, each wait bounded. Returns the first failure. */
    private IllegalStateException close() {
        running = false;
        ScheduledThreadPoolExecutor current = worker;
        ExecutorService currentQueries = queries;
        IllegalStateException failure = null;
        if (current != null) {
            current.shutdownNow();
        }
        if (currentQueries != null) {
            currentQueries.shutdownNow();
        }
        if (current != null) {
            failure = collect(failure, () -> awaitTermination(current, "worker"));
            for (Thread thread : workerThreads) {
                failure = collect(failure, () -> join(thread));
            }
        }
        if (currentQueries != null) {
            failure = collect(failure, () -> awaitTermination(currentQueries, "query threads"));
        }
        return failure;
    }

    private interface Wait {
        void run();
    }

    private static IllegalStateException collect(IllegalStateException first, Wait wait) {
        try {
            wait.run();
            return first;
        } catch (IllegalStateException e) {
            if (first == null) {
                return e;
            }
            first.addSuppressed(e);
            return first;
        }
    }

    private void join(Thread thread) {
        try {
            if (!thread.join(EXIT_TIMEOUT)) {
                throw new IllegalStateException("Node " + node.id() + ": thread " + thread.getName()
                        + " did not exit within " + EXIT_TIMEOUT.toMillis() + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Node " + node.id() + ": interrupted waiting for " + thread.getName(), e);
        }
    }

    private void awaitTermination(ExecutorService executor, String what) {
        try {
            if (!executor.awaitTermination(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Node " + node.id() + ": fault tolerance " + what
                        + " did not finish within " + EXIT_TIMEOUT.toMillis() + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Node " + node.id() + ": interrupted waiting for the fault tolerance "
                    + what, e);
        }
    }

    // ------------------------------------------------------------------ events

    private void publish(String type, Integer peer, String message, Map<String, Object> data) {
        owner.publish(node.id(), type, peer, message, data);
    }

    private static Map<String, Object> data(String key1, Object value1, String key2, Object value2) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(key1, value1);
        data.put(key2, value2);
        return data;
    }
}
