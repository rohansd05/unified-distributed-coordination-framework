package com.udcf.modules.rmi;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.events.ClusterEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import static com.udcf.modules.rmi.RmiTestSupport.acceptThreadAlive;
import static com.udcf.modules.rmi.RmiTestSupport.canBind;
import static com.udcf.modules.rmi.RmiTestSupport.renewCleanThreads;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Twenty crash and recovery cycles on three nodes, with real calls: no port and no thread is
 * leaked, and a crashed node always fails visibly.
 *
 * <p>Leaks are asserted only on threads this service can be blamed for: RMI's listener
 * ("RMI TCP Accept-&lt;port&gt;") and its client-side DGC thread per endpoint
 * ("RMI RenewClean-[127.0.0.1:&lt;port&gt;,&lt;this service's factory&gt;]"). RMI's connection threads
 * ("RMI TCP Connection(n)-...") belong to a JVM-wide pool and idle for up to 60 s, so they are
 * covered only by the generous bound on the total thread count.</p>
 *
 * <p>Test-only ports 28811 to 28813 (RMI base 28810; see RmiTestSupport).</p>
 */
@Timeout(120)
class RmiCrashCycleTest {

    private static final Logger log = LoggerFactory.getLogger(RmiCrashCycleTest.class);
    private static final int BASE = 28810;
    private static final int NODES = 3;
    private static final int CYCLES = 20;

    /**
     * Allowed growth of the JVM's live thread count from the end of cycle 1 to the end of cycle 20,
     * after settling. Measured on Windows: 60 threads after cycle 1 and 60 after cycle 20 (growth
     * 0). A leak of one thread per node and cycle would add 57. The bound leaves room for RMI
     * connection and DGC threads, which idle for up to 60 s before they end.
     */
    private static final int THREAD_GROWTH_BOUND = 15;

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

    private static int port(int nodeId) {
        return BASE + nodeId;
    }

    private static int liveThreads() {
        return Thread.getAllStackTraces().size();
    }

    @Test
    @DisplayName("20 crash/recover cycles: every crash fails visibly, the port is free at once, and nothing leaks")
    void crashRecoverCycles() throws Exception {
        RmiNodeService[] services = new RmiNodeService[NODES + 1];
        for (int nodeId = 1; nodeId <= NODES; nodeId++) {
            services[nodeId] = RmiTestSupport.start(cluster, nodeId);
        }
        RemoteEndpointLookup remote = RmiTestSupport.remote(cluster);
        RmiInvoker invoker = RmiTestSupport.invoker(cluster, 1);
        Map<RemoteFailureKind, Integer> tally = new EnumMap<>(RemoteFailureKind.class);
        int threadsAfterFirstCycle = 0;

        for (int cycle = 1; cycle <= CYCLES; cycle++) {
            for (int nodeId = 1; nodeId <= NODES; nodeId++) {
                int target = nodeId;
                NodeEndpoint stub = remote.lookup(nodeId);
                RmiCallResult up = invoker.invoke(RmiTestSupport.fixed(stub), nodeId, RmiCall.ping());
                assertThat(up.outcome()).as("cycle %d, node %d up", cycle, nodeId).isEqualTo(CallOutcome.SUCCESS);

                cluster.crash(nodeId);
                assertThat(canBind(port(nodeId))).as("cycle %d: port %d free on return", cycle, port(nodeId)).isTrue();

                RmiCallResult down = invoker.invoke(remote, nodeId, RmiCall.ping());
                tally.merge(down.failureKind(), 1, Integer::sum);
                assertThat(down.failureKind()).as("cycle %d, node %d down: %s <- %s %s", cycle, nodeId,
                        down.failureClass(), down.failureCauseClass(), down.failureMessage())
                        .isIn(RmiTestSupport.NODE_GONE);

                // Empty both channels' pools, so the call after the recovery is deterministic.
                RmiTestSupport.drainUntilRefused(() -> remote.lookup(target));
                RmiTestSupport.drainUntilRefused(() -> stub.ping(new CallContext(1, 0)));
                cluster.recover(nodeId);
            }
            if (cycle == 1) {
                threadsAfterFirstCycle = liveThreads();
            }
        }

        log.info("E1b crash-cycle verdict tally ({} cycles x {} nodes): {}", CYCLES, NODES, tally);
        assertThat(tally.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(CYCLES * NODES);

        for (int nodeId = 1; nodeId <= NODES; nodeId++) {
            int port = port(nodeId);
            assertThat(acceptThreadAlive(port)).as("node %d listens", nodeId).isTrue();
            assertThat(renewCleanThreads(services[nodeId])).as("DGC threads for port %d", port).isLessThanOrEqualTo(1);
        }
        int baseline = threadsAfterFirstCycle;
        await().atMost(Duration.ofSeconds(30))
                .until(() -> liveThreads() <= baseline + THREAD_GROWTH_BOUND);
        log.info("E1b crash-cycle threads: after cycle 1 = {}, after cycle {} = {}", baseline, CYCLES, liveThreads());

        cluster.close();
        for (int nodeId = 1; nodeId <= NODES; nodeId++) {
            int port = port(nodeId);
            assertThat(canBind(port)).as("port %d free after stop", port).isTrue();
            await().atMost(Duration.ofSeconds(5)).until(() -> !acceptThreadAlive(port));
        }
    }

    @Test
    @DisplayName("20 stop/start cycles: the port is free on every return and the accept thread ends each time")
    void stopStartCycles() {
        RmiNodeService service = RmiTestSupport.start(cluster, 1);
        RemoteEndpointLookup remote = RmiTestSupport.remote(cluster);
        RmiInvoker invoker = RmiTestSupport.invoker(cluster, 2);

        for (int cycle = 1; cycle <= CYCLES; cycle++) {
            NodeEndpoint stub = lookup(remote);
            assertThat(invoker.invoke(RmiTestSupport.fixed(stub), 1, RmiCall.ping()).outcome())
                    .as("cycle %d", cycle).isEqualTo(CallOutcome.SUCCESS);

            service.stop();
            assertThat(canBind(port(1))).as("cycle %d: port free on return", cycle).isTrue();
            await().atMost(Duration.ofSeconds(5)).until(() -> !acceptThreadAlive(port(1)));
            RmiTestSupport.drainUntilRefused(() -> remote.lookup(1));
            RmiTestSupport.drainUntilRefused(() -> stub.ping(new CallContext(2, 0)));

            service.start();
        }
        assertThat(renewCleanThreads(service)).isLessThanOrEqualTo(1);
    }

    private static NodeEndpoint lookup(RemoteEndpointLookup remote) {
        try {
            return remote.lookup(1);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
