package com.udcf.modules.loadbalancing;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.modules.multithreading.MultithreadingProperties;
import com.udcf.modules.multithreading.RequestsClient;
import com.udcf.modules.multithreading.RequestsNodeService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Binds the Experiment 6 balancer to the shared cluster (R10): every cluster node is a
 * worker, reached over TCP on its Exp 2 requests port (link L3). Exp 6 adds no
 * {@code NodeService} of its own; it has no port range.
 *
 * <p><b>Each run</b> starts the requests service on every node that is up (lazily, through
 * {@link RequestsNodeService#on}), builds fresh workers and a fresh {@link TcpWorkerTransport}
 * (so counters and the event cap are per run), and runs the batch with {@link BatchRunner}.
 * A node that is down stays in the worker list: the balancer discovers it by a refused
 * connection, as in the legacy demo, and it shows 0 requests in the report.</p>
 *
 * <p><b>What "zero failed requests" means.</b> A crashed or unreachable worker turns into a
 * reroute, not a failure, only while some other worker is healthy and has queue room. If
 * every worker is down, every request fails ({@code succeeded = false}) promptly, with one
 * refused attempt per worker; nothing hangs.</p>
 *
 * <p><b>At least once.</b> A timeout trips the breaker even though the work may still finish
 * on that worker, so a request can run twice. Never claim exactly-once delivery.</p>
 *
 * <p><b>One run at a time.</b> Runs share the nodes' executors, so overlapping runs would
 * distort each other's measurements; {@link #run} takes a {@link ReentrantLock} (not a
 * monitor, so a caller on a virtual thread is not pinned while it waits for the run). E6c's
 * {@code ModuleActionGuard} answers 409 before a second run gets here.</p>
 *
 * <p>Plain class, wired by the module in E6c. Covered by LoadBalancingGatewayTest.</p>
 */
public class LoadBalancingGateway {

    private static final Logger log = LoggerFactory.getLogger(LoadBalancingGateway.class);

    private final Cluster cluster;
    private final MultithreadingProperties multithreadingProperties;
    private final MeterRegistry meterRegistry;
    private final ClusterEventBus bus;
    private final LoadBalancingProperties properties;
    private final BatchRunner runner;
    private final ReentrantLock runLock = new ReentrantLock();

    private volatile List<WorkerInfo> workers;

    public LoadBalancingGateway(Cluster cluster, MultithreadingProperties multithreadingProperties,
                                MeterRegistry meterRegistry, ClusterEventBus bus,
                                LoadBalancingProperties properties) {
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
        this.multithreadingProperties = Objects.requireNonNull(multithreadingProperties,
                "multithreadingProperties must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.runner = new BatchRunner();
        this.workers = newWorkers();
    }

    /**
     * Runs one batch with {@code strategy} and waits for it.
     *
     * @param requestCount at least 1
     * @param workUnits    the Exp 2 payload size, 1-5000 (see {@link TcpWorkerTransport})
     * @param concurrency  concurrent clients, at least 1
     */
    public PhaseReport run(Strategy strategy, int requestCount, int workUnits, int concurrency) {
        Objects.requireNonNull(strategy, "strategy must not be null");
        TcpWorkerTransport.requireWorkUnits(workUnits);
        if (requestCount < 1) {
            throw new IllegalArgumentException("requestCount must be >= 1, was " + requestCount);
        }
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be >= 1, was " + concurrency);
        }
        runLock.lock();
        try {
            startLiveServices();
            List<WorkerInfo> fresh = newWorkers();
            TcpWorkerTransport transport = new TcpWorkerTransport(
                    new RequestsClient(properties.requestTimeoutMillis()), cluster.clusterClock(), bus);
            workers = fresh;
            return runner.run(new LoadBalancer(fresh, transport), strategy, requestCount, workUnits, concurrency);
        } finally {
            runLock.unlock();
        }
    }

    /**
     * The workers of the current or last run (before the first run, fresh ones), in node-id
     * order, with the balancer's live counters.
     */
    public List<WorkerInfo> workers() {
        return workers;
    }

    private List<WorkerInfo> newWorkers() {
        return cluster.nodes().stream()
                .map(node -> WorkerInfo.of(node.id(), node.ports().requests(), node.capacity()))
                .toList();
    }

    private void startLiveServices() {
        for (ClusterNode node : cluster.nodes()) {
            if (!node.isUp()) {
                continue;
            }
            try {
                RequestsNodeService.on(node, multithreadingProperties, meterRegistry, bus);
            } catch (NodeDownException e) {
                // Crashed since the check: the balancer will find it refusing connections.
            } catch (IllegalStateException e) {
                // The port could not be bound (SERVICE_START_FAILED is already published);
                // the balancer will find it refusing connections too.
                log.warn("Node {}: requests service unavailable for load balancing: {}", node.id(), e.getMessage());
            }
        }
    }
}
