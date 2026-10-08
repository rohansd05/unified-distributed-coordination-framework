package com.udcf.modules.replication;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.module.ExperimentModule;
import com.udcf.core.module.ModuleActionGuard;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.replication.dto.AntiEntropyCommand;
import com.udcf.modules.replication.dto.AntiEntropyDto;
import com.udcf.modules.replication.dto.CatchUpDto;
import com.udcf.modules.replication.dto.CellDto;
import com.udcf.modules.replication.dto.HealthRowDto;
import com.udcf.modules.replication.dto.InjectStaleCommand;
import com.udcf.modules.replication.dto.InjectionDto;
import com.udcf.modules.replication.dto.ItemDto;
import com.udcf.modules.replication.dto.KeyRowDto;
import com.udcf.modules.replication.dto.ModelDto;
import com.udcf.modules.replication.dto.NodeReplicaDto;
import com.udcf.modules.replication.dto.PushDto;
import com.udcf.modules.replication.dto.ReadDto;
import com.udcf.modules.replication.dto.ReplicaColumnDto;
import com.udcf.modules.replication.dto.ReplicaState;
import com.udcf.modules.replication.dto.ReplicasDto;
import com.udcf.modules.replication.dto.ReplicationOverviewDto;
import com.udcf.modules.replication.dto.ReplicationState;
import com.udcf.modules.replication.dto.TakeoverDto;
import com.udcf.modules.replication.dto.WriteCommand;
import com.udcf.modules.replication.dto.WriteDto;
import com.udcf.web.InvalidParameterException;
import com.udcf.web.NodeStateConflictException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Experiment 5 (lab 5) on the shared cluster: synchronous and asynchronous writes through the
 * primary, a read of any replica, the replicas side by side, crash or recover a backup,
 * anti-entropy, and a stale update delivered out of order. Built on {@link ReplicaSet} and the
 * nodes' {@link ReplicationNodeService}s (TCP, 710k).
 *
 * <p><b>Actions.</b> Write, crash, recover, anti-entropy and stale injection each take the
 * module's {@link ModuleActionGuard} for their whole duration, so a second action gets HTTP
 * 409. Parameters are checked before the guard is taken; node states inside it. Only these
 * actions can change roles: a takeover (catch-up, then push; see {@link ReplicaSet}) happens only
 * inside a write, anti-entropy, stale injection or recover, never in a read.</p>
 *
 * <p><b>Reads.</b> {@link #overview()}, {@link #replicas()} and {@link #read} never start a
 * service, change a role or run a takeover. Replica contents are read over TCP as a
 * cluster-level client, so a crashed replica is UNREACHABLE, never a cached value.</p>
 *
 * <p><b>Crash and recover</b> go through the core path ({@code Cluster.crash} and
 * {@code Cluster.recover}, as the cluster page and the other modules use), so node status,
 * cluster events and every service on the node stay in step (R10). This module crashes backups
 * only; the primary is crashed from the cluster page.</p>
 *
 * <p><b>Status.</b> BUSY while an action holds the guard; RUNNING while asynchronous pushes are
 * still pending; otherwise IDLE.</p>
 *
 * <p><b>Honesty (R7).</b> The asynchronous delay is simulated and labelled wherever it appears;
 * every latency is a measured TCP round trip without it; anything not measured is null.</p>
 */
@Component
public class ReplicationModule implements ExperimentModule {

    public static final String ID = ReplicationNodeService.MODULE;

    static final String TITLE = "Consistency and Replication";

    static final String ASYNC_DELAY_REASON = "On one computer a replication round trip takes well under a "
            + "millisecond, so the moment when a backup still returns the old value would be too short to see. "
            + "Each asynchronous push therefore waits this long first, standing in for the network delay of a real "
            + "deployment. The wait is simulated; every latency shown is measured without it.";

    static final String CONFLICT_RULE_NOTE = "Replicas settle conflicting versions by last writer wins: the "
            + "version with the higher Lamport time wins, and the node id breaks a tie. In this experiment the "
            + "epoch never changes, so nothing else is compared. An asynchronous write that never left a primary "
            + "before it crashed can therefore beat a write made later on the new primary, if its Lamport time is "
            + "higher: the write that happened later in real time is then lost. That is what last writer wins "
            + "means, not a fault; the epochs of Experiment 8 prevent it.";

    static final List<ModelDto> MODELS = List.of(
            new ModelDto(ConsistencyModel.SYNCHRONOUS,
                    "The primary stores the write, sends it to every backup and waits for each one to answer "
                            + "before it tells the client the write succeeded.",
                    "Once the client hears success, every backup that was up holds the new value. The client "
                            + "waits for the slowest backup.",
                    false, null),
            new ModelDto(ConsistencyModel.ASYNCHRONOUS,
                    "The primary stores the write and tells the client at once; it sends the write to the "
                            + "backups afterwards.",
                    "The client gets a fast answer, but for a short time a backup can still return the old "
                            + "value. The replicas agree in the end.",
                    true, ASYNC_DELAY_REASON));

    private final Cluster cluster;
    private final ReplicationProperties properties;
    private final ReplicaSet replicas;
    private final ReplicationMetrics metrics;
    private final ModuleActionGuard guard = new ModuleActionGuard(ID);
    private final AtomicInteger pendingAsync = new AtomicInteger();
    private final AtomicReference<WriteDto> latestWrite = new AtomicReference<>();

    private volatile AntiEntropyDto latestAntiEntropy;
    private volatile InjectionDto latestInjection;

    public ReplicationModule(Cluster cluster, ReplicationProperties properties, ClusterEventBus bus,
                             MeterRegistry meterRegistry) {
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.replicas = new ReplicaSet(cluster, properties, Objects.requireNonNull(bus, "bus must not be null"));
        this.metrics = new ReplicationMetrics(meterRegistry, cluster);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int labNumber() {
        return 5;
    }

    @Override
    public String title() {
        return TITLE;
    }

    @Override
    public ModuleStatus status() {
        if (guard.isBusy()) {
            return ModuleStatus.BUSY;
        }
        return pendingAsync.get() > 0 ? ModuleStatus.RUNNING : ModuleStatus.IDLE;
    }

    /**
     * Clean slate for this module: every started replication service is reset in two passes
     * ({@link ReplicationNodeService#resetState()}: a running one is closed, cleared and
     * reopened; one on a crashed node is only cleared and stays closed), so a push from a node
     * not yet reset in the first pass cannot survive the second; then the replica set forgets
     * its primary, and the module forgets its latest write, anti-entropy and injection. Never
     * starts a service and publishes nothing; the cluster reset clears the event history and
     * decides which nodes recover.
     */
    @Override
    public void reset() {
        try (ModuleActionGuard.ActionTicket ignored = guard.begin("Reset")) {
            for (int pass = 0; pass < 2; pass++) {
                for (ClusterNode node : cluster.nodes()) {
                    ReplicationNodeService.find(node).ifPresent(ReplicationNodeService::resetState);
                }
            }
            replicas.reset();
            latestWrite.set(null);
            latestAntiEntropy = null;
            latestInjection = null;
        }
    }

    /** For tests: holding the guard makes every action answer 409. */
    ModuleActionGuard guard() {
        return guard;
    }

    // ------------------------------------------------------------------ actions

    /**
     * A client write through the primary. SYNCHRONOUS returns once every backup answered or
     * failed; ASYNCHRONOUS returns at once with its pushes PENDING (they follow after the
     * simulated delay, and {@link #overview()} shows them complete).
     *
     * @throws InvalidParameterException  invalid key, value or model (400)
     * @throws NodeStateConflictException every node is crashed, or the primary was superseded (409)
     * @throws NodeDownException          the primary went down during the write (409)
     * @throws com.udcf.core.module.ModuleBusyException another action is running (409)
     */
    public WriteDto write(WriteCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.model() == null) {
            throw new InvalidParameterException("model", "is required");
        }
        checkItem(command.key(), command.value(), "value");
        ConsistencyModel model = command.model();
        try (ModuleActionGuard.ActionTicket ignored = guard.begin("Write of '" + command.key() + "' ["
                + model.name().toLowerCase(Locale.ROOT) + "]")) {
            requireLiveNode();
            Optional<TakeoverReport> before = replicas.lastTakeover();
            WriteResult result;
            try {
                result = replicas.write(command.key(), command.value(), model);
            } catch (NotPrimaryException e) {
                throw new NodeStateConflictException(e.nodeId(), e.getMessage());
            }
            TakeoverDto takeover = newTakeover(before);
            metrics.recordWrite(result.item().originNode(), model);
            String writeId = UUID.randomUUID().toString().substring(0, 8);
            if (model == ConsistencyModel.SYNCHRONOUS) {
                List<PushOutcome> outcomes = result.replication().join();
                outcomes.forEach(outcome -> metrics.recordPush(outcome, model.name()));
                WriteDto done = writeDto(writeId, result, ReplicationState.COMPLETE, outcomes, takeover);
                latestWrite.set(done);
                return done;
            }
            WriteDto pending = writeDto(writeId, result, ReplicationState.PENDING, List.of(), takeover);
            latestWrite.set(pending);
            pendingAsync.incrementAndGet();
            result.replication().whenComplete((outcomes, error) -> {
                try {
                    if (outcomes != null) {
                        outcomes.forEach(outcome -> metrics.recordPush(outcome, model.name()));
                        // Only if this write is still the latest: a reset or a newer write wins.
                        latestWrite.compareAndSet(pending,
                                writeDto(writeId, result, ReplicationState.COMPLETE, outcomes, takeover));
                    }
                } finally {
                    pendingAsync.decrementAndGet();
                }
            });
            return pending;
        }
    }

    /**
     * Crashes a backup through the core path ({@code Cluster.crash}): every service on the node
     * closes, as from the cluster page.
     *
     * @throws com.udcf.core.cluster.UnknownNodeException no such node (404)
     * @throws InvalidParameterException  the node is the replication primary (400; crash it from the cluster page)
     * @throws NodeStateConflictException the node is already crashed (409)
     */
    public NodeReplicaDto crashBackup(int nodeId) {
        ClusterNode node = cluster.node(nodeId);
        try (ModuleActionGuard.ActionTicket ignored = guard.begin("Crash node " + nodeId)) {
            if (!node.isUp()) {
                throw new NodeStateConflictException(nodeId, "Node " + nodeId + " is already crashed");
            }
            if (replicas.selectedPrimaryId().filter(primary -> primary == nodeId).isPresent()) {
                throw new InvalidParameterException("nodeId", "Node " + nodeId + " is the replication primary. "
                        + "This page crashes backups only; crash the primary from the Cluster page.");
            }
            cluster.crash(nodeId);
            return nodeDto(node);
        }
    }

    /**
     * Recovers any crashed node through the core path ({@code Cluster.recover}). If a primary
     * was made before and the selector now picks another node (a lower-id node came back), the
     * takeover runs here, inside this guarded action.
     *
     * @throws com.udcf.core.cluster.UnknownNodeException no such node (404)
     * @throws NodeStateConflictException the node is already up (409)
     */
    public NodeReplicaDto recover(int nodeId) {
        ClusterNode node = cluster.node(nodeId);
        try (ModuleActionGuard.ActionTicket ignored = guard.begin("Recover node " + nodeId)) {
            if (node.isUp()) {
                throw new NodeStateConflictException(nodeId, "Node " + nodeId + " is already up");
            }
            cluster.recover(nodeId);
            if (replicas.takeoverPending()) {
                try {
                    replicas.primary();
                } catch (NotPrimaryException e) {
                    throw new NodeStateConflictException(e.nodeId(), e.getMessage());
                }
            }
            return nodeDto(node);
        }
    }

    /**
     * Anti-entropy from the primary to one backup.
     *
     * @throws com.udcf.core.cluster.UnknownNodeException no such node (404)
     * @throws InvalidParameterException the target is the primary (400)
     * @throws NodeDownException         the target is crashed (409)
     */
    public AntiEntropyDto antiEntropy(AntiEntropyCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.targetNodeId() == null) {
            throw new InvalidParameterException("targetNodeId", "is required");
        }
        int targetId = command.targetNodeId();
        ClusterNode target = cluster.node(targetId);
        try (ModuleActionGuard.ActionTicket ignored = guard.begin("Anti-entropy to node " + targetId)) {
            requireLiveNode();
            requireLiveBackup(target, "targetNodeId", "anti-entropy pushes from the primary to a backup");
            ReplicationNodeService primary = primaryForAction();
            if (!target.isUp()) {
                throw new NodeDownException(targetId);
            }
            AntiEntropyDto dto = antiEntropyDto(primary.nodeId(), primary.antiEntropy(targetId));
            latestAntiEntropy = dto;
            return dto;
        }
    }

    /**
     * Delivers to one backup a version of a key one Lamport tick older than the primary's, after
     * the newer one: the backup should refuse it as STALE.
     *
     * @throws com.udcf.core.cluster.UnknownNodeException no such node (404)
     * @throws InvalidParameterException the backup is the primary, the primary does not hold the key,
     *                                   or the stale value is invalid (400)
     * @throws NodeDownException         the backup is crashed (409)
     */
    public InjectionDto injectStale(InjectStaleCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.backupNodeId() == null) {
            throw new InvalidParameterException("backupNodeId", "is required");
        }
        checkItem(command.key(), command.staleValue(), "staleValue");
        int backupId = command.backupNodeId();
        ClusterNode backup = cluster.node(backupId);
        try (ModuleActionGuard.ActionTicket ignored = guard.begin("Stale update to node " + backupId)) {
            requireLiveNode();
            requireLiveBackup(backup, "backupNodeId", "a stale update is delivered to a backup");
            ReplicationNodeService primary = primaryForAction();
            if (!backup.isUp()) {
                throw new NodeDownException(backupId);
            }
            DataItem current = primary.get(command.key()).orElseThrow(() -> new InvalidParameterException("key",
                    "The primary (node " + primary.nodeId() + ") does not hold key '" + command.key()
                            + "'; write it first"));
            DataItem stale;
            try {
                stale = OutOfOrderInjector.staleVersionOf(current, command.staleValue());
            } catch (IllegalArgumentException e) {
                throw new InvalidParameterException("key", e.getMessage());
            }
            PushOutcome outcome = primary.deliverOutOfOrder(backupId, stale);
            metrics.recordPush(outcome, ReplicationMetrics.OUT_OF_ORDER);
            InjectionDto dto = new InjectionDto(primary.nodeId(), backupId, command.key(), itemDto(current),
                    itemDto(stale), pushDto(outcome), outcome.result().equals(Optional.of(ApplyResult.STALE)));
            latestInjection = dto;
            return dto;
        }
    }

    // ------------------------------------------------------------------ reads (never start, never take over)

    /** Everything the page needs. Never starts a service, changes a role or takes over. */
    public ReplicationOverviewDto overview() {
        Optional<Integer> selected = replicas.selectedPrimaryId();
        Optional<Integer> current = replicas.currentPrimaryId();
        List<NodeReplicaDto> nodes = cluster.nodes().stream()
                .map(node -> nodeDto(node, selected))
                .toList();
        Integer measuredBy = current.orElse(null);
        List<HealthRowDto> health = measuredBy == null ? List.of() : health(measuredBy);
        return new ReplicationOverviewDto(status(), guard.currentAction().orElse(null), selected.orElse(null),
                measuredBy, replicas.takeoverPending(), replicas.lastTakeover().map(this::takeoverDto).orElse(null),
                properties.asyncDelayMillis(), ASYNC_DELAY_REASON, properties.timeoutMillis(), properties.batchSize(),
                CONFLICT_RULE_NOTE, MODELS, nodes, measuredBy, health, latestWrite.get(), latestAntiEntropy,
                latestInjection);
    }

    /**
     * Reads {@code key} from one replica over TCP and compares it with the reference replica
     * (the node last made primary, also read over TCP). Never starts a service.
     *
     * @throws com.udcf.core.cluster.UnknownNodeException no such node (404)
     * @throws InvalidParameterException                  invalid key (400)
     */
    public ReadDto read(int nodeId, String key) {
        cluster.node(nodeId);
        checkKey(key);
        Integer referenceId = replicas.currentPrimaryId().orElse(null);
        Read replica = readOver(nodeId, key);
        Read reference = referenceId == null ? null : referenceId == nodeId ? replica : readOver(referenceId, key);
        ReplicaState state;
        if (!replica.reachable()) {
            state = ReplicaState.UNREACHABLE;
        } else if (reference == null || !reference.reachable()) {
            state = null;
        } else {
            Map<Integer, Map<String, DataItem>> stores = new HashMap<>();
            stores.put(referenceId, reference.asStore(key));
            stores.put(nodeId, replica.asStore(key));
            state = stateOf(ConsistencyCheck.compare(referenceId, stores), nodeId, key, replica.item() != null);
        }
        return new ReadDto(nodeId, key, referenceId, replica.reachable(), itemDto(replica.item()),
                reference == null ? null : itemDto(reference.item()), state, replica.error());
    }

    /** Every replica's store side by side, read over TCP. Never starts a service. */
    public ReplicasDto replicas() {
        Integer referenceId = replicas.currentPrimaryId().orElse(null);
        Map<Integer, ReplicaDump> dumps = new LinkedHashMap<>();
        Map<Integer, String> errors = new HashMap<>();
        for (ClusterNode node : cluster.nodes()) {
            try {
                dumps.put(node.id(), replicas.dump(node.id()));
            } catch (IOException e) {
                errors.put(node.id(), describe(e));
            }
        }
        boolean referenceReadable = referenceId != null && dumps.containsKey(referenceId);
        ConsistencyReport report = null;
        if (referenceReadable) {
            Map<Integer, Map<String, DataItem>> stores = new HashMap<>();
            dumps.forEach((id, dump) -> stores.put(id, dump.items()));
            report = ConsistencyCheck.compare(referenceId, stores);
        }
        TreeSet<String> keys = new TreeSet<>();
        dumps.values().forEach(dump -> keys.addAll(dump.items().keySet()));
        List<KeyRowDto> rows = new ArrayList<>();
        for (String key : keys) {
            List<CellDto> cells = new ArrayList<>();
            for (ClusterNode node : cluster.nodes()) {
                ReplicaDump dump = dumps.get(node.id());
                DataItem item = dump == null ? null : dump.items().get(key);
                ReplicaState state = dump == null ? ReplicaState.UNREACHABLE
                        : report == null ? null : stateOf(report, node.id(), key, item != null);
                cells.add(new CellDto(node.id(), itemDto(item), state));
            }
            rows.add(new KeyRowDto(key, cells));
        }
        List<ReplicaColumnDto> columns = cluster.nodes().stream().map(node -> {
            ReplicaDump dump = dumps.get(node.id());
            return new ReplicaColumnDto(node.id(), node.status(), Objects.equals(referenceId, node.id()), dump != null,
                    dump == null ? null : dump.storeEpoch(), dump == null ? null : dump.items().size(),
                    errors.get(node.id()));
        }).toList();
        return new ReplicasDto(referenceId, report == null ? null : report.consistent(),
                report == null ? null : report.divergences().size(), columns, rows);
    }

    // ------------------------------------------------------------------ helpers

    private record Read(boolean reachable, DataItem item, String error) {
        Map<String, DataItem> asStore(String key) {
            return item == null ? Map.of() : Map.of(key, item);
        }
    }

    private Read readOver(int nodeId, String key) {
        try {
            return new Read(true, replicas.read(nodeId, key).item().orElse(null), null);
        } catch (IOException e) {
            return new Read(false, null, describe(e));
        }
    }

    private static ReplicaState stateOf(ConsistencyReport report, int nodeId, String key, boolean holdsKey) {
        for (ReplicaDivergence divergence : report.divergences()) {
            if (divergence.nodeId() == nodeId && divergence.key().equals(key)) {
                return switch (divergence.kind()) {
                    case MISSING -> ReplicaState.MISSING;
                    case STALE -> ReplicaState.STALE;
                    case AHEAD -> ReplicaState.AHEAD;
                    case CONFLICT -> ReplicaState.CONFLICT;
                };
            }
        }
        return holdsKey ? ReplicaState.CURRENT : ReplicaState.ABSENT;
    }

    private ReplicationNodeService primaryForAction() {
        try {
            return replicas.primary();
        } catch (NotPrimaryException e) {
            throw new NodeStateConflictException(e.nodeId(), e.getMessage());
        }
    }

    private void requireLiveNode() {
        if (cluster.upCount() == 0) {
            throw new NodeStateConflictException(0, "Every node is crashed: recover a node first");
        }
    }

    private void requireLiveBackup(ClusterNode node, String parameter, String why) {
        if (replicas.selectedPrimaryId().filter(primary -> primary == node.id()).isPresent()) {
            throw new InvalidParameterException(parameter, "Node " + node.id() + " is the replication primary: "
                    + why);
        }
        if (!node.isUp()) {
            throw new NodeDownException(node.id());
        }
    }

    /** The {@link DataItem} rules; a violation is a 400 naming the offending field. */
    private static void checkItem(String key, String value, String valueParameter) {
        try {
            new DataItem(key, value, 0, 1, DataStore.INITIAL_EPOCH);
        } catch (IllegalArgumentException e) {
            String message = String.valueOf(e.getMessage());
            throw new InvalidParameterException(message.startsWith("key") ? "key" : valueParameter, message);
        }
    }

    private static void checkKey(String key) {
        checkItem(key, "", "value");
    }

    private TakeoverDto newTakeover(Optional<TakeoverReport> before) {
        Optional<TakeoverReport> after = replicas.lastTakeover();
        if (after.isEmpty() || (before.isPresent() && before.get() == after.get())) {
            return null;
        }
        return takeoverDto(after.get());
    }

    private NodeReplicaDto nodeDto(ClusterNode node) {
        return nodeDto(node, replicas.selectedPrimaryId());
    }

    private NodeReplicaDto nodeDto(ClusterNode node, Optional<Integer> selected) {
        Optional<ReplicationNodeService> service = ReplicationNodeService.find(node);
        boolean running = service.map(ReplicationNodeService::isRunning).orElse(false);
        Integer itemCount = null;
        if (running) {
            try {
                itemCount = service.get().snapshot().size();
            } catch (NodeDownException e) {
                running = false;   // crashed between the check and the read
            }
        }
        String role = selected.map(primary -> primary == node.id() ? "PRIMARY" : "BACKUP").orElse(null);
        return new NodeReplicaDto(node.id(), node.status(), role,
                service.map(ReplicationNodeService::isPrimary).orElse(false), running,
                node.ports().replication(), service.map(ReplicationNodeService::epoch).orElse(null), itemCount);
    }

    private List<HealthRowDto> health(int measuredBy) {
        Optional<ReplicationNodeService> primary = ReplicationNodeService.find(cluster.node(measuredBy));
        SortedMap<Integer, ReplicationStatsSnapshot> stats = primary.map(ReplicationNodeService::stats)
                .orElse(Collections.emptySortedMap());
        List<HealthRowDto> rows = new ArrayList<>();
        for (ClusterNode node : cluster.nodes()) {
            if (node.id() == measuredBy) {
                continue;
            }
            ReplicationStatsSnapshot s = stats.get(node.id());
            rows.add(s == null
                    ? new HealthRowDto(node.id(), 0, 0, 0, 0, 0, 0, null, null, null)
                    : new HealthRowDto(node.id(), s.acks(), s.applied(), s.duplicates(), s.staleRejections(),
                    s.staleEpochRejections(), s.failures(), round(s.averageLatencyMillis()),
                    round(s.maxLatencyMillis()), s.lastSync().orElse(null)));
        }
        return rows;
    }

    private WriteDto writeDto(String writeId, WriteResult result, ReplicationState state, List<PushOutcome> outcomes,
                              TakeoverDto takeover) {
        return new WriteDto(writeId, result.model(), result.item().originNode(), itemDto(result.item()),
                result.localResult(), round2(result.confirmMillis()), result.simulated(), result.simulatedDelayMillis(),
                result.simulated() ? ASYNC_DELAY_REASON : null, state, result.backupIds(),
                outcomes.stream().map(ReplicationModule::pushDto).toList(), takeover);
    }

    private TakeoverDto takeoverDto(TakeoverReport report) {
        return new TakeoverDto(report.previousPrimaryId().orElse(null), report.newPrimaryId(),
                report.appliedFromCatchUp(),
                report.catchUps().stream().map(ReplicationModule::catchUpDto).toList(),
                report.pushes().stream().map(push -> antiEntropyDto(report.newPrimaryId(), push)).toList(),
                report.lamportTime());
    }

    private static CatchUpDto catchUpDto(CatchUpReport report) {
        Optional<AntiEntropyResult> m = report.merged();
        return new CatchUpDto(report.sourceNodeId(), report.completed(),
                m.map(AntiEntropyResult::pushed).orElse(null), m.map(AntiEntropyResult::applied).orElse(null),
                m.map(AntiEntropyResult::alreadyCurrent).orElse(null), m.map(AntiEntropyResult::stale).orElse(null),
                m.map(AntiEntropyResult::staleEpoch).orElse(null),
                report.sourceEpoch().isPresent() ? report.sourceEpoch().getAsLong() : null,
                round2(report.latencyMillis()), report.failure().orElse(null));
    }

    private static AntiEntropyDto antiEntropyDto(int sourceNodeId, AntiEntropyReport report) {
        AntiEntropyResult m = report.merged();
        return new AntiEntropyDto(sourceNodeId, report.targetNodeId(), report.completed(), m.pushed(), m.applied(),
                m.alreadyCurrent(), m.stale(), m.staleEpoch(), report.chunksPlanned(), report.chunksAcknowledged(),
                round2(report.latencyMillis()), report.failure().orElse(null));
    }

    private static PushDto pushDto(PushOutcome outcome) {
        return new PushDto(outcome.backupId(), outcome.status(), outcome.result().orElse(null),
                outcome.backupEpoch().isPresent() ? outcome.backupEpoch().getAsLong() : null,
                round(outcome.latencyMillis()), outcome.detail().orElse(null));
    }

    private static ItemDto itemDto(DataItem item) {
        return item == null ? null
                : new ItemDto(item.key(), item.value(), item.lamportTime(), item.originNode(), item.epoch());
    }

    private static String describe(IOException e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static Double round(OptionalDouble value) {
        return value.isPresent() ? round2(value.getAsDouble()) : null;
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
