package com.udcf.modules.replication;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Experiment 5's replica set on the shared cluster: who is primary, who are the backups, and
 * the cluster-level operations on them. A thin facade over the nodes' replication services.
 *
 * <p><b>Roles.</b> The primary is the lowest-id live node and every other node, crashed ones
 * included, is a backup, both from the module-local {@link ReplicationRoleSelector}
 * (TODO(L1): replaced by the shared role provider in Phase 9A). In Experiment 5 the epoch
 * stays at 1; Experiment 8 promotes with new epochs through
 * {@link ReplicationNodeService#becomePrimary(long)} directly.</p>
 *
 * <p><b>Takeover.</b> Only {@link #primary()} (and the operations that call it) changes roles.
 * It remembers the node it last made primary; when the selector picks another node (the primary
 * crashed, or a lower-id node recovered), that node first <b>catches up</b>: it pulls the whole
 * store of every other live node over TCP and merges each with that node's store epoch
 * ({@link ReplicationNodeService#catchUpFrom}), so it holds every write it missed and its clock
 * is above every item it pulled. It then acts as primary at its (possibly raised) epoch, its
 * push statistics start afresh, and it <b>pushes</b> its whole store to every live backup
 * (anti-entropy), so every live replica holds the union. On the first selection, and after a
 * {@link #reset()}, no primary was made before and both steps are skipped. Every selection
 * publishes {@code PRIMARY_SELECTED} on the new primary.</p>
 *
 * <p><b>Read-only views.</b> {@link #selectedPrimaryId()}, {@link #currentPrimaryId()},
 * {@link #takeoverPending()}, {@link #lastTakeover()}, {@link #read} and {@link #dump} never
 * start a service, change a role or take over.</p>
 *
 * <p><b>Reads.</b> {@link #read} and {@link #dump} go over TCP as a cluster-level client
 * (sender {@value #CLIENT_ID}, the cluster's Lamport clock), so a crashed replica answers with a
 * real {@link java.net.ConnectException}, never a cached value.</p>
 *
 * <p>Covered by ReplicaSetTest.</p>
 */
public class ReplicaSet {

    /** Reads and dumps come from a cluster-level client. */
    public static final int CLIENT_ID = 0;

    private final Cluster cluster;
    private final ReplicationProperties properties;
    private final ClusterEventBus bus;
    private final ReplicationClient client;
    private final ReentrantLock roles = new ReentrantLock();

    private volatile Integer currentPrimaryId;          // written under roles; null = none made primary yet
    private volatile TakeoverReport lastTakeover;       // written under roles

    public ReplicaSet(Cluster cluster, ReplicationProperties properties, ClusterEventBus bus) {
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.client = new ReplicationClient(properties.timeoutMillis());
    }

    /**
     * The lowest-id live node.
     *
     * @throws IllegalStateException if every node is crashed
     */
    public int primaryId() {
        return selectedPrimaryId()
                .orElseThrow(() -> new IllegalStateException("Every node is crashed: there is no replication primary"));
    }

    /** Read-only: the node the selector would make primary now, or empty if every node is crashed. */
    public Optional<Integer> selectedPrimaryId() {
        List<Integer> live = cluster.nodes().stream().filter(ClusterNode::isUp).map(ClusterNode::id).toList();
        if (live.isEmpty()) {
            return Optional.empty();
        }
        // TODO(L1): replaced by the shared role provider in Phase 9A
        return Optional.of(ReplicationRoleSelector.selectPrimary(live));
    }

    /** Read-only: the node {@link #primary()} last made primary, or empty if none yet (or since a reset). */
    public Optional<Integer> currentPrimaryId() {
        return Optional.ofNullable(currentPrimaryId);
    }

    /**
     * Read-only: true if a primary was made before and the selector now picks a different node,
     * so the next {@link #primary()} will run a takeover (catch-up, then push).
     */
    public boolean takeoverPending() {
        Integer current = currentPrimaryId;
        Optional<Integer> selected = selectedPrimaryId();
        return current != null && selected.isPresent() && !selected.get().equals(current);
    }

    /** Read-only: the latest selection, or empty if none yet (or since a reset). */
    public Optional<TakeoverReport> lastTakeover() {
        return Optional.ofNullable(lastTakeover);
    }

    /** Every node except the current primary, crashed ones included, ascending. */
    public List<Integer> backupIds() {
        return backupsOf(primaryId());
    }

    /** The node's replication service, started on first use. @throws NodeDownException if it is crashed */
    public ReplicationNodeService service(int nodeId) {
        return ReplicationNodeService.on(cluster.node(nodeId), cluster, properties, bus);
    }

    /**
     * Brings the roles in line with the selector and returns the primary's service: starts the
     * service on every live node, steps down any other live node acting as primary, runs a
     * takeover if the selected node is not the one made primary last (see the class comment),
     * and makes the selected node act as primary at its current epoch.
     *
     * @throws IllegalStateException if every node is crashed
     * @throws NodeDownException     if the selected node crashed meanwhile
     */
    public ReplicationNodeService primary() {
        roles.lock();
        try {
            int selected = primaryId();
            List<Integer> livePeers = new ArrayList<>();
            for (ClusterNode node : cluster.nodes()) {
                if (node.id() == selected || !node.isUp()) {
                    continue;
                }
                try {
                    ReplicationNodeService peer = service(node.id());
                    if (peer.isPrimary()) {
                        peer.stepDown();
                    }
                    livePeers.add(node.id());
                } catch (NodeDownException e) {
                    // Crashed meanwhile: it stays a backup and diverges, as any crashed backup.
                }
            }
            ReplicationNodeService primary = service(selected);
            Integer previous = currentPrimaryId;
            if (previous != null && previous == selected) {
                if (!primary.isPrimary()) {
                    primary.becomePrimary(primary.epoch());
                }
                return primary;
            }
            List<CatchUpReport> catchUps = new ArrayList<>();
            List<AntiEntropyReport> pushes = new ArrayList<>();
            if (previous != null) {
                for (int peer : livePeers) {
                    catchUps.add(primary.catchUpFrom(peer));
                }
            }
            primary.becomePrimary(primary.epoch());
            if (previous != null) {
                primary.resetStats();   // the health table is measured by this primary, from now on
                for (int peer : livePeers) {
                    pushes.add(primary.antiEntropy(peer));
                }
            }
            long lamport = cluster.node(selected).clock().tick();
            TakeoverReport report = new TakeoverReport(Optional.ofNullable(previous), selected, catchUps, pushes,
                    lamport);
            publishSelected(report);
            currentPrimaryId = selected;
            lastTakeover = report;
            return primary;
        } finally {
            roles.unlock();
        }
    }

    /** Forgets the node made primary and the latest selection (a module reset). Changes no service. */
    public void reset() {
        roles.lock();
        try {
            currentPrimaryId = null;
            lastTakeover = null;
        } finally {
            roles.unlock();
        }
    }

    /** A client write through the current primary to every backup. See {@link ReplicationNodeService#write}. */
    public WriteResult write(String key, String value, ConsistencyModel model) {
        ReplicationNodeService primary = primary();
        return primary.write(key, value, model, backupsOf(primary.nodeId()));
    }

    /** Anti-entropy from the current primary to {@code targetId}. */
    public AntiEntropyReport antiEntropy(int targetId) {
        return primary().antiEntropy(targetId);
    }

    /**
     * Delivers to {@code backupId} a version of {@code key} strictly older than the primary's
     * ({@link OutOfOrderInjector}), to show the backup refusing it.
     *
     * @throws IllegalArgumentException if the primary does not hold {@code key}, or holds it at
     *                                  Lamport time 0
     */
    public PushOutcome injectStale(int backupId, String key, String staleValue) {
        ReplicationNodeService primary = primary();
        DataItem current = primary.get(key)
                .orElseThrow(() -> new IllegalArgumentException("the primary does not hold key '" + key + "'"));
        return primary.deliverOutOfOrder(backupId, OutOfOrderInjector.staleVersionOf(current, staleValue));
    }

    /** Reads {@code key} from one replica over TCP. Never starts a service. */
    public ReadReply read(int nodeId, String key) throws IOException {
        return client.read(cluster.node(nodeId).ports().replication(), CLIENT_ID, cluster.clusterClock(), key);
    }

    /** One replica's whole store over TCP, {@code batch-size} items per page. Never starts a service. */
    public ReplicaDump dump(int nodeId) throws IOException {
        return client.dump(cluster.node(nodeId).ports().replication(), CLIENT_ID, cluster.clusterClock(),
                properties.batchSize());
    }

    private List<Integer> backupsOf(int primaryId) {
        List<Integer> all = cluster.nodes().stream().map(ClusterNode::id).toList();
        return ReplicationRoleSelector.backupsOf(primaryId, all);
    }

    private void publishSelected(TakeoverReport report) {
        Map<String, Object> data = new LinkedHashMap<>();
        report.previousPrimaryId().ifPresent(previous -> data.put("previousPrimaryId", previous));
        data.put("reason", "lowest live node");
        data.put("catchUpSources", report.catchUps().stream().map(CatchUpReport::sourceNodeId).toList());
        data.put("appliedFromCatchUp", report.appliedFromCatchUp());
        data.put("pushedTo", report.pushes().stream().map(AntiEntropyReport::targetNodeId).toList());
        String message = report.previousPrimaryId()
                .map(previous -> "Node " + report.newPrimaryId() + " took over as replication primary from node "
                        + previous + " after catching up " + report.appliedFromCatchUp() + " items")
                .orElse("Node " + report.newPrimaryId() + " selected as replication primary (lowest live node)");
        bus.publish(EventDraft.of(ReplicationNodeService.MODULE, report.newPrimaryId(), "PRIMARY_SELECTED",
                        report.lamportTime())
                .withPeer(report.previousPrimaryId().orElse(null))
                .withMessage(message)
                .withData(data));
    }
}
