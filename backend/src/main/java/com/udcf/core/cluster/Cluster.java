package com.udcf.core.cluster;

import com.udcf.core.clock.LamportClock;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final LamportClock clock = new LamportClock();
    private final AtomicBoolean closed = new AtomicBoolean();

    public Cluster(ClusterProperties properties, ClusterEventBus bus) {
        Objects.requireNonNull(properties, "properties must not be null");
        Objects.requireNonNull(bus, "bus must not be null");
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

    /** See {@link ClusterNode#crash()}. */
    public boolean crash(int id) {
        return node(id).crash();
    }

    /** See {@link ClusterNode#recover()}. */
    public boolean recover(int id) {
        return node(id).recover();
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
