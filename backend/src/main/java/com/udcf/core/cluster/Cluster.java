package com.udcf.core.cluster;

import com.udcf.core.clock.LamportClock;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one shared cluster every module runs on (R1, R10).
 *
 * <p>Builds nodes 1..size from {@link ClusterProperties}: capacities cycle through the
 * configured pattern and node k binds base + k on each port range. Cluster-level events
 * (node id 0) use a cluster-level Lamport clock, separate from every node's clock.</p>
 */
public class Cluster implements AutoCloseable {

    private final List<ClusterNode> nodes;
    private final ClusterEventBus bus;
    private final LamportClock clock = new LamportClock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object leaderLock = new Object();
    private Integer announcedLeader;   // guarded by leaderLock: the leader last announced in LEADER_CHANGED

    public Cluster(ClusterProperties properties, ClusterEventBus bus) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        List<ClusterNode> built = new ArrayList<>(properties.size());
        for (int k = 1; k <= properties.size(); k++) {
            built.add(new ClusterNode(k, properties.capacityOf(k), properties.portsFor(k), bus));
        }
        this.nodes = List.copyOf(built);
        List<String> capacities = nodes.stream().map(node -> node.capacity().name()).toList();
        bus.publish(EventDraft.of(ClusterNode.MODULE, 0, "CLUSTER_STARTED", clock.tick())
                .withMessage("Cluster started with " + nodes.size() + " nodes")
                .withData(Map.of("size", nodes.size(), "capacities", capacities)));
    }

    /** Every node, ordered by id. Unmodifiable. */
    public List<ClusterNode> nodes() {
        return nodes;
    }

    /** @throws UnknownNodeException if {@code id} is outside 1..size */
    public ClusterNode node(int id) {
        if (id < 1 || id > nodes.size()) {
            throw new UnknownNodeException(id);
        }
        return nodes.get(id - 1);
    }

    public int size() {
        return nodes.size();
    }

    public long upCount() {
        return nodes.stream().filter(ClusterNode::isUp).count();
    }

    /**
     * See {@link ClusterNode#crash()}. A crashed node holds no role, so if it was the
     * announced leader, {@code LEADER_CHANGED} (leader none) is published afterwards.
     *
     * <p>No cluster lock is held while the node crashes: its services join their threads,
     * and an election thread may be inside {@link #assignLeader} at that moment.</p>
     */
    public boolean crash(int id) {
        boolean crashed = node(id).crash();
        if (crashed) {
            synchronized (leaderLock) {
                if (Objects.equals(announcedLeader, id)) {
                    announceLeader(null);
                }
            }
        }
        return crashed;
    }

    /** The node holding {@link NodeRole#LEADER}, if any. */
    public Optional<Integer> leaderId() {
        return nodes.stream().filter(node -> node.hasRole(NodeRole.LEADER)).map(ClusterNode::id).findFirst();
    }

    /**
     * Makes {@code nodeId} the only {@link NodeRole#LEADER}, or removes the leader when
     * {@code nodeId} is null or the node is crashed. Publishes {@code LEADER_CHANGED} (module
     * {@code "cluster"}, node 0, cluster clock, data {@code {leaderId, previousLeaderId}})
     * when the announced leader changes. Only the election module calls this.
     *
     * <p>Locks: {@code leaderLock}, then each node's roles lock, then the bus's publish lock.
     * None of them is ever held while a thread is joined, so an election worker may call
     * this while {@link #crash(int)} is joining it.</p>
     *
     * @return the leader afterwards
     * @throws UnknownNodeException if {@code nodeId} is outside 1..size
     */
    public Optional<Integer> assignLeader(Integer nodeId) {
        ClusterNode target = nodeId == null ? null : node(nodeId);
        synchronized (leaderLock) {
            Integer leader = null;
            for (ClusterNode node : nodes) {
                if (node == target && node.grantRole(NodeRole.LEADER)) {
                    leader = node.id();
                } else {
                    node.revokeRole(NodeRole.LEADER);
                }
            }
            if (!Objects.equals(leader, announcedLeader)) {
                announceLeader(leader);
            }
            return Optional.ofNullable(leader);
        }
    }

    private void announceLeader(Integer leader) {   // caller holds leaderLock
        Integer previous = announcedLeader;
        announcedLeader = leader;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("leaderId", leader);
        data.put("previousLeaderId", previous);
        bus.publish(EventDraft.of(ClusterNode.MODULE, 0, "LEADER_CHANGED", clock.tick())
                .withPeer(leader)
                .withMessage(leader == null
                        ? "No leader (node " + previous + " is no longer leader)"
                        : "Node " + leader + " is the leader")
                .withData(data));
    }

    /** See {@link ClusterNode#recover()}. */
    public boolean recover(int id) {
        return node(id).recover();
    }

    /** The cluster-level Lamport clock, used for events with node id 0. */
    public LamportClock clusterClock() {
        return clock;
    }

    /**
     * Resets every node's clock and the cluster clock to zero. Only an explicit cluster
     * reset calls this; crash and recovery never do.
     */
    public void resetClocks() {
        nodes.forEach(node -> node.clock().reset());
        clock.reset();
    }

    /** Recovers every crashed node. */
    public void recoverAll() {
        nodes.forEach(ClusterNode::recover);
    }

    /** Stops every node's services. Idempotent; called by Spring on context shutdown. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            nodes.forEach(ClusterNode::stop);
        }
    }
}
