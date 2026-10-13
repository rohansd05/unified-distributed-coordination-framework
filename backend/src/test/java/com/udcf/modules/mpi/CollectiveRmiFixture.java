package com.udcf.modules.mpi;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.modules.rmi.RemoteEndpointLookup;
import com.udcf.modules.rmi.RmiExtraExport;
import com.udcf.modules.rmi.RmiNodeService;
import com.udcf.modules.rmi.RmiProperties;
import com.udcf.modules.rmi.RmiTestSupport;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.awaitility.Awaitility.await;

/**
 * A real three-node cluster for the E9b tests: each node's RMI service exports its NodeEndpoint and
 * a CollectiveEndpoint (through {@link CollectiveEndpointExport}) on 127.0.0.1, exactly as
 * production does, and the root reaches them through {@link RmiCollectiveTransport}.
 *
 * <p>Test ports (Track C, E9b range 28500-28599): the RMI base is the caller's (28570, 28580 or
 * 28590; nodes bind base + 1 to base + 3); the other five bases 28505, 28515, 28525, 28535 and
 * 28545 are never bound, because only the rmi service starts.</p>
 */
final class CollectiveRmiFixture implements AutoCloseable {

    /** Equal to application.yml (MpiPropertiesTest). */
    static final MpiProperties LOCAL = new MpiProperties(100_000, 10_000, 20, 8, 300);
    /** The public profile's max-payload-elements, the rest as application.yml. */
    static final MpiProperties PUBLIC = new MpiProperties(10_000, 10_000, 20, 8, 300);

    final int rmiBase;
    final MpiProperties mpi;
    final RmiProperties rmi;
    final ClusterEventBus bus;
    final Cluster cluster;
    final CollectiveEndpointExport export;
    final RemoteEndpointLookup lookup;
    final RmiCollectiveTransport transport;

    CollectiveRmiFixture(int rmiBase, MpiProperties mpi) {
        this(rmiBase, mpi, RmiTestSupport.PROPERTIES);
    }

    /** @param rmi the RMI settings of every node's service (its call timeout is the stubs' read timeout) */
    CollectiveRmiFixture(int rmiBase, MpiProperties mpi, RmiProperties rmi) {
        this.rmiBase = rmiBase;
        this.mpi = mpi;
        this.rmi = rmi;
        this.bus = RmiTestSupport.bus();
        this.cluster = new Cluster(new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
                new ClusterProperties.Ports(rmiBase, 28505, 28515, 28525, 28535, 28545)), bus);
        this.export = new CollectiveEndpointExport(mpi, RmiTestSupport.TRANSPORT);
        this.lookup = RemoteEndpointLookup.of(cluster, rmi, RmiTestSupport.TRANSPORT);
        this.transport = new RmiCollectiveTransport(lookup);
    }

    /** Starts node {@code nodeId}'s RMI service with the collective export, as RmiModule does. */
    RmiNodeService start(int nodeId) {
        return start(nodeId, export);
    }

    /** Starts node {@code nodeId}'s RMI service with the given extra exports (none: Experiment 1 only). */
    RmiNodeService start(int nodeId, RmiExtraExport... extras) {
        return RmiNodeService.on(cluster.node(nodeId), rmi, RmiTestSupport.TRANSPORT, List.of(extras));
    }

    void startAll() {
        for (int id = 1; id <= cluster.size(); id++) {
            start(id);
        }
    }

    CollectiveEndpointLogic logic(int nodeId) {
        return CollectiveEndpointExport.current(cluster.node(nodeId)).orElseThrow();
    }

    int port(int nodeId) {
        return rmiBase + nodeId;
    }

    /**
     * The barrier after a refused call: one valid call to the same node (sender Lamport time 0, so
     * the node's clock moves by exactly 2: update to local + 1, then the reply's tick), waiting for
     * its reply. A refused call that had run after all, early or late, would have moved the clock by
     * at least 2 more and stored its own collective id.
     */
    ReceiveReceipt barrier(int nodeId, long collectiveId) throws TransferException {
        return transport.deliver(nodeId, new com.udcf.modules.rmi.CallContext(1, 0), collectiveId, 0, new double[] {1});
    }

    @Override
    public void close() {
        cluster.close();
        bus.close();
        for (int id = 1; id <= cluster.size(); id++) {
            int port = port(id);
            await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> !RmiTestSupport.acceptThreadAlive(port) && RmiTestSupport.canBind(port));
        }
    }
}
