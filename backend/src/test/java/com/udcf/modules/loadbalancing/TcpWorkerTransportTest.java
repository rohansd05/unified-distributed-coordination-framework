package com.udcf.modules.loadbalancing;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.NodeCapacity;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.modules.multithreading.MultithreadingProperties;
import com.udcf.modules.multithreading.RequestsClient;
import com.udcf.modules.multithreading.RequestsNodeService;
import com.udcf.modules.multithreading.RequestsProtocol;
import com.udcf.modules.multithreading.WorkloadType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The TCP transport on real 127.0.0.1 sockets: one real three-node cluster, no Spring
 * context, and scripted one-line servers for the replies a healthy node never sends on demand.
 *
 * <p>Test-only ports: cluster bases 47110 to 47610 (requests ports 47511 to 47513) and scripted
 * servers 47801 to 47806; 47809 is never bound (nothing listening). All inside the Exp 6 test
 * range 47100 to 47899, apart from every other test class.</p>
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class TcpWorkerTransportTest {

    private static final ClusterProperties CLUSTER = new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
            new ClusterProperties.Ports(47110, 47210, 47310, 47410, 47510, 47610));
    private static final MultithreadingProperties MULTITHREADING =
            new MultithreadingProperties(200, 60, "udcf-worker-", 30, 500, 2000,
                    new MultithreadingProperties.Backpressure(50, WorkloadType.CPU_HASH, 200));
    private static final int NOTHING_LISTENING = 47809;

    /** A one-line server: replies with a fixed line, closes without replying, or never replies. */
    private static final class ScriptedServer implements AutoCloseable {
        enum Mode { REPLY, CLOSE, HOLD }

        private final ServerSocket server = new ServerSocket();
        private final Set<Socket> held = ConcurrentHashMap.newKeySet();
        private final AtomicInteger accepted = new AtomicInteger();

        ScriptedServer(int port, Mode mode, String reply) throws IOException {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(RequestsProtocol.LOOPBACK, port));
            Thread.ofVirtual().name("scripted-" + port).start(() -> serve(mode, reply));
        }

        private void serve(Mode mode, String reply) {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    accepted.incrementAndGet();
                    if (mode == Mode.HOLD) {
                        held.add(socket);          // never answers; closed by close()
                        continue;
                    }
                    try (socket) {
                        RequestsProtocol.readLine(socket.getInputStream());
                        if (mode == Mode.REPLY) {
                            RequestsProtocol.writeLine(socket.getOutputStream(), reply);
                        }
                    }
                } catch (IOException e) {
                    return;                        // closed by close()
                }
            }
        }

        int accepted() {
            return accepted.get();
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket s : held) {
                s.close();
            }
        }
    }

    private ClusterEventBus bus;
    private Cluster cluster;
    private SimpleMeterRegistry meters;
    private TcpWorkerTransport transport;
    private final List<ScriptedServer> servers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), java.time.Clock.systemUTC());
        cluster = new Cluster(CLUSTER, bus);
        meters = new SimpleMeterRegistry();
        transport = new TcpWorkerTransport(new RequestsClient(5000), cluster.clusterClock(), bus);
    }

    @AfterEach
    void tearDown() throws IOException {
        for (ScriptedServer s : servers) {
            s.close();
        }
        cluster.close();
        bus.close();
    }

    private ScriptedServer server(int port, ScriptedServer.Mode mode, String reply) throws IOException {
        ScriptedServer s = new ScriptedServer(port, mode, reply);
        servers.add(s);
        return s;
    }

    private static WorkerInfo worker(int nodeId, int port) {
        return new WorkerInfo(nodeId, port, "W" + nodeId, 1);
    }

    private List<ClusterEvent> events(String type) {
        return bus.query(TcpWorkerTransport.MODULE, null, 1000).stream()
                .filter(e -> e.type().equals(type)).toList();
    }

    private static long number(ClusterEvent event, String key) {
        return ((Number) event.data().get(key)).longValue();
    }

    @Test
    @DisplayName("a real node serves the request as sender 0 with CPU_HASH work, and Lamport time moves both ways (L3, L4)")
    void servedByRealNode() throws Exception {
        RequestsNodeService.on(cluster.node(1), MULTITHREADING, meters, bus);
        WorkerInfo w = WorkerInfo.of(1, cluster.node(1).ports().requests(), NodeCapacity.FAST);
        long beforeSend = cluster.clusterClock().current();

        transport.send(w, 1, 5);

        // The node publishes REQUEST_COMPLETED just after writing its reply.
        await().atMost(Duration.ofSeconds(10)).until(() -> bus.query("multithreading", 1, 100).stream()
                .anyMatch(e -> e.type().equals("REQUEST_COMPLETED")));
        List<ClusterEvent> served = bus.query("multithreading", 1, 100).stream()
                .filter(e -> e.type().equals("REQUEST_COMPLETED")).toList();
        assertThat(served).hasSize(1);
        ClusterEvent event = served.get(0);
        assertThat(event.peerId()).isEqualTo(TcpWorkerTransport.SENDER_ID);
        assertThat(event.data()).containsEntry("workload", "CPU_HASH");
        assertThat(number(event, "payloadSize")).isEqualTo(5);
        assertThat(number(event, "receiveLamport")).isGreaterThan(beforeSend + 1);   // node merged the send time
        assertThat(cluster.clusterClock().current()).isGreaterThan(event.lamportTime()); // sender merged the reply
        assertThat(bus.query(TcpWorkerTransport.MODULE, null, 100)).isEmpty();          // nothing published when served
    }

    @Test
    @DisplayName("REJECTED and FAILED replies decline the request (node alive) and publish DISPATCH_DECLINED")
    void rejectedAndFailedDecline() throws Exception {
        server(47801, ScriptedServer.Mode.REPLY, "REJECTED|1|5|r-9;;0.0;0.0;0.0;Queue full");
        server(47802, ScriptedServer.Mode.REPLY, "FAILED|2|6|r-10;udcf-worker-n2-1;1.0;2.0;3.0;Work failed");

        assertThatThrownBy(() -> transport.send(worker(1, 47801), 7, 5))
                .isInstanceOf(WorkerDeclinedException.class)
                .hasMessageContaining("REJECTED").hasMessageContaining("Queue full");
        assertThatThrownBy(() -> transport.send(worker(2, 47802), 8, 5))
                .isInstanceOf(WorkerDeclinedException.class)
                .hasMessageContaining("FAILED").hasMessageContaining("Work failed");

        List<ClusterEvent> declined = events("DISPATCH_DECLINED");
        assertThat(declined).hasSize(2);
        ClusterEvent first = declined.stream().filter(e -> e.peerId() == 1).findFirst().orElseThrow();
        assertThat(first.module()).isEqualTo("loadbalancing");
        assertThat(first.nodeId()).isZero();
        assertThat(first.lamportTime()).isGreaterThan(5);   // after merging the reply's time 5
        assertThat(first.data()).containsEntry("requestId", 7).containsEntry("status", "REJECTED")
                .containsEntry("detail", "Queue full").containsEntry("workerRequestId", "r-9");
        assertThat(declined).anySatisfy(e -> assertThat(e.data()).containsEntry("status", "FAILED"));
        assertThat(events("DISPATCH_FAILED")).isEmpty();
    }

    @Test
    @DisplayName("an ERROR reply or a malformed reply (ProtocolException) declines the request: the node is alive")
    void protocolErrorsDecline() throws Exception {
        server(47803, ScriptedServer.Mode.REPLY, "ERROR|1|5|Invalid request: bad");
        server(47804, ScriptedServer.Mode.REPLY, "COMPLETED|1|5|garbage");

        assertThatThrownBy(() -> transport.send(worker(1, 47803), 1, 5))
                .isInstanceOf(WorkerDeclinedException.class).hasMessageContaining("Invalid request: bad");
        assertThatThrownBy(() -> transport.send(worker(1, 47804), 2, 5))
                .isInstanceOf(WorkerDeclinedException.class);

        assertThat(events("DISPATCH_DECLINED")).hasSize(2)
                .allSatisfy(e -> assertThat(e.data()).containsEntry("status", TcpWorkerTransport.PROTOCOL_ERROR)
                        .doesNotContainKey("workerRequestId"));
        assertThat(events("DISPATCH_FAILED")).isEmpty();
    }

    @Test
    @DisplayName("nothing listening (a crashed node): ConnectException, published as DISPATCH_FAILED")
    void connectionRefused() {
        assertThatThrownBy(() -> transport.send(worker(3, NOTHING_LISTENING), 4, 5))
                .isInstanceOf(ConnectException.class);

        List<ClusterEvent> failed = events("DISPATCH_FAILED");
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).nodeId()).isZero();
        assertThat(failed.get(0).peerId()).isEqualTo(3);
        assertThat(failed.get(0).data()).containsEntry("requestId", 4).containsEntry("reason", "ConnectException");
    }

    @Test
    @DisplayName("a connection closed without a reply (a crash mid-request): a plain IOException")
    void closedWithoutReply() throws Exception {
        server(47805, ScriptedServer.Mode.CLOSE, null);

        assertThatThrownBy(() -> transport.send(worker(1, 47805), 1, 5))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(SocketTimeoutException.class).isNotInstanceOf(ProtocolException.class);
        assertThat(events("DISPATCH_FAILED")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("reason", "IOException"));
    }

    @Test
    @DisplayName("a node that never answers: SocketTimeoutException after the configured timeout")
    void silentNode() throws Exception {
        server(47806, ScriptedServer.Mode.HOLD, null);
        TcpWorkerTransport shortTimeout =
                new TcpWorkerTransport(new RequestsClient(200), cluster.clusterClock(), bus);

        assertThatThrownBy(() -> shortTimeout.send(worker(1, 47806), 1, 5))
                .isInstanceOf(SocketTimeoutException.class);
        assertThat(events("DISPATCH_FAILED")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("reason", "SocketTimeoutException"));
    }

    @Test
    @DisplayName("work units outside the Exp 2 payload range 1-5000 are refused before any connection")
    void workUnitsRange() throws Exception {
        ScriptedServer s = server(47801, ScriptedServer.Mode.REPLY, "COMPLETED|1|5|r-1;t;0.0;0.0;0.0;ok");
        long clockBefore = cluster.clusterClock().current();

        assertThatThrownBy(() -> transport.send(worker(1, 47801), 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transport.send(worker(1, 47801), 1, 5001)).isInstanceOf(IllegalArgumentException.class);

        assertThat(s.accepted()).isZero();
        assertThat(cluster.clusterClock().current()).isEqualTo(clockBefore);
        transport.send(worker(1, 47801), 1, 5000);   // the upper bound itself is fine
        assertThat(s.accepted()).isEqualTo(1);
    }

    @Test
    @DisplayName("events are capped at 20 per worker per transport; the balancer's counters keep the true totals")
    void eventCap() throws Exception {
        server(47801, ScriptedServer.Mode.REPLY, "REJECTED|1|5|r-1;;0.0;0.0;0.0;Queue full");
        WorkerInfo w1 = worker(1, 47801);
        LoadBalancer lb = new LoadBalancer(List.of(w1), transport);

        for (int id = 1; id <= 100; id++) {
            DispatchResult r = lb.dispatch(id, 5, Strategy.ROUND_ROBIN);
            assertThat(r.succeeded()).isFalse();
        }

        assertThat(w1.declined()).isEqualTo(100);
        assertThat(events("DISPATCH_DECLINED")).hasSize(TcpWorkerTransport.MAX_EVENTS_PER_WORKER);

        // The cap is per worker: another worker's failures get their own 20.
        for (int id = 1; id <= 25; id++) {
            assertThatThrownBy(sendTo(transport, worker(2, NOTHING_LISTENING), id)).isInstanceOf(ConnectException.class);
        }
        assertThat(events("DISPATCH_FAILED")).hasSize(20).allMatch(e -> e.peerId() == 2);

        // A fresh transport (one per run) starts a fresh cap.
        TcpWorkerTransport nextRun = new TcpWorkerTransport(new RequestsClient(5000), cluster.clusterClock(), bus);
        LoadBalancer next = new LoadBalancer(List.of(w1), nextRun);
        for (int id = 1; id <= 30; id++) {
            next.dispatch(id, 5, Strategy.ROUND_ROBIN);
        }
        assertThat(events("DISPATCH_DECLINED")).hasSize(40);
    }

    private static org.assertj.core.api.ThrowableAssert.ThrowingCallable sendTo(
            TcpWorkerTransport t, WorkerInfo w, int id) {
        return () -> t.send(w, id, 5);
    }

    @Test
    @DisplayName("rejects null arguments with NullPointerException")
    void validation() {
        RequestsClient client = new RequestsClient(1000);
        assertThatThrownBy(() -> new TcpWorkerTransport(null, cluster.clusterClock(), bus))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TcpWorkerTransport(client, null, bus)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TcpWorkerTransport(client, cluster.clusterClock(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> transport.send(null, 1, 5)).isInstanceOf(NullPointerException.class);
    }
}
