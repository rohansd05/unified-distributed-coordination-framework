package com.udcf.modules.replication;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.events.ClusterEventBus;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Experiment 5's replica set on the shared cluster: who is primary, who are the backups, and
 * the cluster-level operations on them. A thin facade over the nodes' replication services.
 *
 * <p><b>Roles.</b> The primary is the lowest-id live node and every other node, crashed ones
 * included, is a backup, both from the module-local {@link ReplicationRoleSelector}
 * (TODO(L1): replaced by the shared role provider in Phase 9A). {@link #primary()} starts the
 * replication service on every live node (backups must listen to receive pushes), makes the
 * selected node act as primary at its current epoch, and steps down any other live node still
 * acting as primary. In Experiment 5 the epoch therefore stays at 1; Experiment 8 promotes with
 * new epochs through {@link ReplicationNodeService#becomePrimary(long)} directly.</p>
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
        List<Integer> live = cluster.nodes().stream().filter(ClusterNode::isUp).map(ClusterNode::id).toList();
        if (live.isEmpty()) {
            throw new IllegalStateException("Every node is crashed: there is no replication primary");
        }
        // TODO(L1): replaced by the shared role provider in Phase 9A
        return ReplicationRoleSelector.selectPrimary(live);
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
     * service on every live node, steps down any other live node acting as primary, and makes
     * the selected node act as primary at its current epoch.
     */
    public ReplicationNodeService primary() {
        roles.lock();
        try {
            int primaryId = primaryId();
            for (ClusterNode node : cluster.nodes()) {
                if (node.id() == primaryId || !node.isUp()) {
                    continue;
                }
                try {
                    ReplicationNodeService backup = service(node.id());
                    if (backup.isPrimary()) {
                        backup.stepDown();
                    }
                } catch (NodeDownException e) {
                    // Crashed meanwhile: it stays a backup and diverges, as any crashed backup.
                }
            }
            ReplicationNodeService primary = service(primaryId);
            if (!primary.isPrimary()) {
                primary.becomePrimary(primary.epoch());
            }
            return primary;
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

    /** Reads {@code key} from one replica over TCP. */
    public ReadReply read(int nodeId, String key) throws IOException {
        return client.read(cluster.node(nodeId).ports().replication(), CLIENT_ID, cluster.clusterClock(), key);
    }

    /** One replica's whole store over TCP, {@code batch-size} items per page. */
    public ReplicaDump dump(int nodeId) throws IOException {
        return client.dump(cluster.node(nodeId).ports().replication(), CLIENT_ID, cluster.clusterClock(),
                properties.batchSize());
    }

    private List<Integer> backupsOf(int primaryId) {
        List<Integer> all = cluster.nodes().stream().map(ClusterNode::id).toList();
        return ReplicationRoleSelector.backupsOf(primaryId, all);
    }
}
