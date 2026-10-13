package com.udcf.modules.matrix;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.modules.mpi.CollectiveEndpointExport;
import com.udcf.modules.mpi.CollectiveEndpointLogic;
import com.udcf.modules.mpi.MpiProperties;
import com.udcf.modules.mpi.RmiCollectiveTransport;
import com.udcf.modules.multithreading.MultithreadingProperties;
import com.udcf.modules.multithreading.WorkloadType;
import com.udcf.modules.rmi.RemoteEndpointLookup;
import com.udcf.modules.rmi.RmiExtraExport;
import com.udcf.modules.rmi.RmiNodeService;
import com.udcf.modules.rmi.RmiTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.awaitility.Awaitility.await;

/**
 * A real three-node cluster for the E10b socket tests: each node's RMI service exports NodeEndpoint,
 * CollectiveEndpoint and MatrixEndpoint on 127.0.0.1, exactly as production does, and the root reaches them
 * through {@link RmiCollectiveTransport} and {@link RmiMatrixTransport}. The nodes compute either on a
 * scripted executor per node (default: at once on the RMI call's thread) or on their real Experiment 2
 * executors (which bind the requests ports).
 *
 * <p>Test ports (Track C, E10b): the RMI base and, for real executors, the requests base are the caller's
 * (each class its own range in 28706-28749; nodes bind base + 1 to base + 3). The other bases are numbers
 * only and never bound: clock 28600, election 28610, replication 28620, mapreduce 28630, and requests 28640
 * when the executors are scripted.</p>
 */
final class MatrixRmiFixture implements AutoCloseable {

    /** Equal to application.yml (MpiPropertiesTest). */
    static final MpiProperties MPI = new MpiProperties(100_000, 10_000, 20, 8, 300);
    /** Equal to application.yml (MatrixRunPropertiesTest). */
    static final MatrixRunProperties RUN = new MatrixRunProperties(1, 2000, 4);
    /** Equal to application.yml. */
    static final MultithreadingProperties MULTITHREADING = new MultithreadingProperties(200, 60, "udcf-worker-", 30, 500, 5000,
            new MultithreadingProperties.Backpressure(50, WorkloadType.CPU_HASH, 200));
    /** The requests base when the executors are scripted: never bound. */
    static final int UNUSED_REQUESTS_BASE = 28640;

    final int rmiBase;
    final int requestsBase;
    final boolean experimentTwo;
    final ClusterEventBus bus;
    final MeterRegistry meters = new SimpleMeterRegistry();
    final Cluster cluster;
    final CollectiveEndpointExport collectiveExport;
    final MatrixEndpointExport matrixExport;
    final RemoteEndpointLookup lookup;
    final RmiCollectiveTransport collectives;
    final RmiMatrixTransport matrices;
    final Map<Integer, NodeExecutor> executors = new ConcurrentHashMap<>();

    /** Scripted executors, no requests port. */
    MatrixRmiFixture(int rmiBase, MatrixRunProperties run) {
        this(rmiBase, UNUSED_REQUESTS_BASE, run, false);
    }

    /** @param experimentTwo true: every node computes on its real Experiment 2 executor (binds requestsBase + k) */
    MatrixRmiFixture(int rmiBase, int requestsBase, MatrixRunProperties run, boolean experimentTwo) {
        this.rmiBase = rmiBase;
        this.requestsBase = requestsBase;
        this.experimentTwo = experimentTwo;
        this.bus = RmiTestSupport.bus();
        this.cluster = new Cluster(new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
                new ClusterProperties.Ports(rmiBase, 28600, 28610, 28620, requestsBase, 28630)), bus);
        Function<ClusterNode, NodeExecutor> perNode = experimentTwo
                ? node -> NodeExecutor.experimentTwo(node, MULTITHREADING, meters, bus)
                : node -> task -> executors.getOrDefault(node.id(), InMemoryMatrixNodes.DIRECT).submit(task);
        this.collectiveExport = new CollectiveEndpointExport(MPI, RmiTestSupport.TRANSPORT);
        this.matrixExport = new MatrixEndpointExport(run, RmiTestSupport.PROPERTIES, RmiTestSupport.TRANSPORT, perNode);
        this.lookup = RemoteEndpointLookup.of(cluster, RmiTestSupport.PROPERTIES, RmiTestSupport.TRANSPORT);
        this.collectives = new RmiCollectiveTransport(lookup);
        this.matrices = new RmiMatrixTransport(lookup);
    }

    /** Starts node {@code nodeId}'s RMI service with the collective and matrix exports, as RmiModule does. */
    RmiNodeService start(int nodeId) {
        return start(nodeId, collectiveExport, matrixExport);
    }

    RmiNodeService start(int nodeId, RmiExtraExport... extras) {
        return RmiNodeService.on(cluster.node(nodeId), RmiTestSupport.PROPERTIES, RmiTestSupport.TRANSPORT, List.of(extras));
    }

    void startAll() {
        for (int id = 1; id <= cluster.size(); id++) {
            start(id);
        }
    }

    MatrixEndpointLogic matrix(int nodeId) {
        return (MatrixEndpointLogic) RmiNodeService.find(cluster.node(nodeId)).orElseThrow().localExport(MatrixEndpoint.BOUND_NAME);
    }

    CollectiveEndpointLogic collective(int nodeId) {
        return CollectiveEndpointExport.current(cluster.node(nodeId)).orElseThrow();
    }

    @Override
    public void close() {
        cluster.close();
        bus.close();
        for (int id = 1; id <= cluster.size(); id++) {
            int rmiPort = rmiBase + id;
            int requestsPort = requestsBase + id;
            await().atMost(5, TimeUnit.SECONDS).until(() -> !RmiTestSupport.acceptThreadAlive(rmiPort) && RmiTestSupport.canBind(rmiPort)
                    && (!experimentTwo || RmiTestSupport.canBind(requestsPort)));
        }
    }
}
