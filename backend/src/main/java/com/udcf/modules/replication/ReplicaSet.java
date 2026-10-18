package com.udcf.modules.replication;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.cluster.RoleProvider;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Experiment 5's replica set on the shared cluster: who is primary, who are the backups, and
 * the cluster-level operations on them. A thin facade over the nodes' replication services.
 *
 * <p><b>Roles (link L1).</b> The primary is the leader elected by Experiment 4, from the shared
 * {@link RoleProvider}; every other node, crashed ones included, is a backup. An action gets the
 * leader with {@link #leader()} on its request thread and passes it in; the read-only views use
 * {@link RoleProvider#leaderId()} and never start an election. Experiment 5 never moves the
 * primary away from the leader, and when the leader already acts as primary, at any epoch (for
 * example after an Experiment 8 promotion), {@link #primary(int)} changes nothing. Experiment 8
 * promotes with new epochs through {@link ReplicationNodeService#becomePrimary(long)} directly.</p>
 *
 * <p><b>Takeover.</b> Only {@link #primary(int)} (and the operations that call it) changes roles.
 * It remembers the node it last made primary; when the leader is another node (the primary
 * crashed and Experiment 4 elected a new leader, or the old leader came back and won again), that
 * node first <b>catches up</b>: it pulls the whole
 * store of every other live node over TCP and merges each with that node's store epoch
 * ({@link ReplicationNodeService#catchUpFrom}), so it holds every write it missed and its clock
 * is above every item it pulled. It then acts as primary at its (possibly raised) epoch, its
 * push statistics start afresh, and it <b>pushes</b> its whole store to every live backup
 * (anti-entropy), so every live replica holds the union. On the first selection, and after a
 * {@link #reset()}, no primary was made before and both steps are skipped. Epochs never go down:
 * the new primary acts at least at the epoch of every node it stepped down. Every selection
 * publishes {@code PRIMARY_SELECTED} on the new primary; every appointment then publishes the
 * PRIMARY and BACKUP roles ({@link RoleProvider#publishReplicationRoles}).</p>
 *
 * <p><b>Read-only views.</b> {@link #selectedPrimaryId()}, {@link #currentPrimaryId()},
 * {@link #takeoverPending()}, {@link #lastTakeover()}, {@link #read} and {@link #dump} never
 * start a service, change a role, take over or start an election.</p>
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
    private final RoleProvider leaders;
    private final ReplicationClient client;
    private final ReentrantLock roles = new ReentrantLock();

    private volatile Integer currentPrimaryId;          // written under roles; null = none made primary yet
    private volatile TakeoverReport lastTakeover;       // written under roles

    public ReplicaSet(Cluster cluster, ReplicationProperties properties, ClusterEventBus bus, RoleProvider leaders) {
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.leaders = Objects.requireNonNull(leaders, "leaders must not be null");
        this.client = new ReplicationClient(properties.timeoutMillis());
    }

    /**
     * The leader, electing one first if there is none ({@link RoleProvider#ensureLeader()}: may
     * block up to the election bound). Request thread only, holding no lock.
     *
     * @throws NoLeaderException if no leader could be elected (HTTP 409)
     */
    public int leader() {
        return leaders.ensureLeader().orElseThrow(NoLeaderException::new);
    }

    /** Read-only: the leader, the node the next action makes primary; empty if there is none. Never elects. */
    public Optional<Integer> selectedPrimaryId() {
        return leaders.leaderId();
    }

    /** Read-only: the node {@link #primary(int)} last made primary, or empty if none yet (or since a reset). */
    public Optional<Integer> currentPrimaryId() {
        return Optional.ofNullable(currentPrimaryId);
    }

    /**
     * Read-only: true if a primary was made before and the leader is now a different node, so the
     * next {@link #primary(int)} will run a takeover (catch-up, then push).
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

    /** The node's replication service, started on first use. @throws NodeDownException if it is crashed */
    public ReplicationNodeService service(int nodeId) {
        return ReplicationNodeService.on(cluster.node(nodeId), cluster, properties, bus);
    }

    /**
     * Makes the leader the primary and returns its service. Starts the service on every live node.
     * If the leader already acts as primary, at any epoch, and no live peer acts as primary at that
     * epoch or above (a recovered old primary still believes it is one), nothing else changes. Otherwise it
     * steps down any other live node acting as primary, runs a takeover if the leader is not the
     * node made primary last (see the class comment), makes the leader act as primary at the
     * highest of its own epoch and the epochs of the nodes it stepped down, and publishes the roles.
     *
     * @param leader the leader, from {@link #leader()}
     * @throws NodeDownException if the leader crashed meanwhile
     */
    public ReplicationNodeService primary(int leader) {
        roles.lock();
        try {
            ReplicationNodeService primary = service(leader);
            List<ReplicationNodeService> livePeers = new ArrayList<>();
            for (ClusterNode node : cluster.nodes()) {
                if (node.id() == leader || !node.isUp()) {
                    continue;
                }
                try {
                    livePeers.add(service(node.id()));
                } catch (NodeDownException e) {
                    // Crashed meanwhile: it stays a backup and diverges, as any crashed backup.
                }
            }
            Integer previous = currentPrimaryId;
            long leaderEpoch = primary.primaryEpoch().orElse(0);
            // A recovered old primary still believes it is primary (the role survives a crash): it only
            // stands if no live peer acts as primary at its epoch or above.
            if (leaderEpoch > 0 && livePeers.stream().allMatch(peer -> peer.primaryEpoch().orElse(0) < leaderEpoch)) {
                currentPrimaryId = leader;   // made primary before, here or by Experiment 8: nothing moves
                return primary;
            }
            long epoch = primary.epoch();
            for (ReplicationNodeService peer : livePeers) {
                if (peer.isPrimary()) {
                    epoch = Math.max(epoch, peer.epoch());   // epochs never go down
                    peer.stepDown();
                }
            }
            if (previous != null && previous == leader) {
                primary.becomePrimary(epoch);
                publishRoles(leader, livePeers);
                return primary;
            }
            List<CatchUpReport> catchUps = new ArrayList<>();
            List<AntiEntropyReport> pushes = new ArrayList<>();
            if (previous != null) {
                for (ReplicationNodeService peer : livePeers) {
                    catchUps.add(primary.catchUpFrom(peer.nodeId()));
                }
            }
            primary.becomePrimary(Math.max(epoch, primary.epoch()));   // a catch-up may have raised its epoch
            if (previous != null) {
                primary.resetStats();   // the health table is measured by this primary, from now on
                for (ReplicationNodeService peer : livePeers) {
                    pushes.add(primary.antiEntropy(peer.nodeId()));
                }
            }
            long lamport = cluster.node(leader).clock().tick();
            TakeoverReport report = new TakeoverReport(Optional.ofNullable(previous), leader, catchUps, pushes,
                    lamport);
            publishSelected(report);
            currentPrimaryId = leader;
            lastTakeover = report;
            publishRoles(leader, livePeers);
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

    /** A client write through the leader, as primary, to every backup. See {@link ReplicationNodeService#write}. */
    public WriteResult write(int leader, String key, String value, ConsistencyModel model) {
        ReplicationNodeService primary = primary(leader);
        return primary.write(key, value, model, backupsOf(primary.nodeId()));
    }

    /** Anti-entropy from the leader, as primary, to {@code targetId}. */
    public AntiEntropyReport antiEntropy(int leader, int targetId) {
        return primary(leader).antiEntropy(targetId);
    }

    /**
     * Delivers to {@code backupId} a version of {@code key} strictly older than the primary's
     * ({@link OutOfOrderInjector}), to show the backup refusing it.
     *
     * @throws IllegalArgumentException if the primary does not hold {@code key}, or holds it at
     *                                  Lamport time 0
     */
    public PushOutcome injectStale(int leader, int backupId, String key, String staleValue) {
        ReplicationNodeService primary = primary(leader);
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

    /** Every node except the primary, crashed ones included, ascending. */
    private List<Integer> backupsOf(int primaryId) {
        TreeSet<Integer> backups = new TreeSet<>(cluster.nodes().stream().map(ClusterNode::id).toList());
        backups.remove(primaryId);
        return List.copyOf(backups);
    }

    /** PRIMARY on the leader, BACKUP on its live peers (a crashed node holds no role). */
    private void publishRoles(int leader, List<ReplicationNodeService> livePeers) {
        leaders.publishReplicationRoles(leader, livePeers.stream().map(ReplicationNodeService::nodeId).toList());
    }

    private void publishSelected(TakeoverReport report) {
        Map<String, Object> data = new LinkedHashMap<>();
        report.previousPrimaryId().ifPresent(previous -> data.put("previousPrimaryId", previous));
        data.put("reason", "elected leader");
        data.put("catchUpSources", report.catchUps().stream().map(CatchUpReport::sourceNodeId).toList());
        data.put("appliedFromCatchUp", report.appliedFromCatchUp());
        data.put("pushedTo", report.pushes().stream().map(AntiEntropyReport::targetNodeId).toList());
        String message = report.previousPrimaryId()
                .map(previous -> "Node " + report.newPrimaryId() + " took over as replication primary from node "
                        + previous + " after catching up " + report.appliedFromCatchUp() + " items")
                .orElse("Node " + report.newPrimaryId() + " selected as replication primary (the leader elected "
                        + "by Experiment 4)");
        bus.publish(EventDraft.of(ReplicationNodeService.MODULE, report.newPrimaryId(), "PRIMARY_SELECTED",
                        report.lamportTime())
                .withPeer(report.previousPrimaryId().orElse(null))
                .withMessage(message)
                .withData(data));
    }
}
