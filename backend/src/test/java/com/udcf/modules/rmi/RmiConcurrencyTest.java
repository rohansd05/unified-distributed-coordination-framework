package com.udcf.modules.rmi;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.events.ClusterEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parallel remote calls from three calling nodes to three target nodes, on real 127.0.0.1
 * sockets: every call succeeds, and the Lamport time a node stamps on its replies is never
 * repeated (each reply is a send event of that node's single shared clock).
 *
 * <p>Test-only ports 28831 to 28833 (RMI base 28830; see RmiTestSupport).</p>
 */
@Timeout(60)
class RmiConcurrencyTest {

    private static final int BASE = 28830;
    private static final int NODES = 3;
    private static final int THREADS_PER_CALLER = 4;
    private static final int CALLS_PER_THREAD = 25;
    private static final List<RmiCall> CALLS = List.of(RmiCall.ping(), RmiCall.nodeInfo(), RmiCall.echo("x"),
            RmiCall.computeHash(100));

    private ClusterEventBus bus;
    private Cluster cluster;

    @BeforeEach
    void setUp() {
        bus = RmiTestSupport.bus();
        cluster = RmiTestSupport.cluster(BASE, NODES, bus);
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    /** One successful reply, and the caller that received it. */
    private record Received(int callerId, EndpointReply reply) {
    }

    @Test
    @DisplayName("parallel calls to several nodes all succeed, and every reply's Lamport time is unique per node")
    void parallelCalls() throws Exception {
        for (int nodeId = 1; nodeId <= NODES; nodeId++) {
            RmiTestSupport.start(cluster, nodeId);
        }
        RemoteEndpointLookup remote = RmiTestSupport.remote(cluster);
        ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(NODES * THREADS_PER_CALLER);
        List<Future<?>> done = new ArrayList<>();
        try {
            for (int caller = 1; caller <= NODES; caller++) {
                RmiInvoker invoker = RmiTestSupport.invoker(cluster, caller);
                int callerId = caller;
                for (int t = 0; t < THREADS_PER_CALLER; t++) {
                    int offset = t;
                    done.add(pool.submit(() -> {
                        gate.await();
                        for (int i = 0; i < CALLS_PER_THREAD; i++) {
                            int target = 1 + (i + offset) % NODES;
                            RmiCallResult result = invoker.invoke(remote, target, CALLS.get((i + offset) % CALLS.size()));
                            assertThat(result.outcome()).as("%s", result).isEqualTo(CallOutcome.SUCCESS);
                            received.add(new Received(callerId, result.reply()));
                        }
                        return null;
                    }));
                }
            }
            gate.countDown();
            for (Future<?> future : done) {
                future.get(50, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(received).hasSize(NODES * THREADS_PER_CALLER * CALLS_PER_THREAD);
        Map<Integer, List<Long>> timesByNode = new ConcurrentHashMap<>();
        for (Received r : received) {
            timesByNode.computeIfAbsent(r.reply().nodeId(), id -> new ArrayList<>()).add(r.reply().lamportTime());
        }
        for (Map.Entry<Integer, List<Long>> entry : timesByNode.entrySet()) {
            assertThat(entry.getValue()).as("reply times of node %d", entry.getKey()).doesNotHaveDuplicates();
            assertThat(cluster.node(entry.getKey()).clock().current())
                    .as("node %d's clock is past every reply it sent", entry.getKey())
                    .isGreaterThanOrEqualTo(entry.getValue().stream().mapToLong(Long::longValue).max().orElseThrow());
        }
        for (Received r : received) {
            assertThat(cluster.node(r.callerId()).clock().current())
                    .as("caller %d merged the reply time", r.callerId())
                    .isGreaterThan(r.reply().lamportTime());
        }
    }
}
