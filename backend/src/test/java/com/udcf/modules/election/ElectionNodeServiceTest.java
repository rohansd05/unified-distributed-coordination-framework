package com.udcf.modules.election;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.core.failure.FailureListener;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntUnaryOperator;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The election transport on real 127.0.0.1 UDP sockets: lifecycle (bind, crash and stop join
 * every thread and free the port, recover rebinds), listener robustness, the worker-only
 * threading rule (hard rule 7) and Lamport stamping (L4, heartbeats exempt). Peers are
 * either other services or a raw test socket speaking the wire format.
 *
 * <p>Test ports (Track B block, below 32768, outside the Linux and Windows ephemeral ranges):
 * cluster bases 26100 to 26150, so the election ports are 26121 to 26123; 26190 is bound by a
 * squatter (with an election base of 26189 in that one test); 26199 is never bound.</p>
 */
class ElectionNodeServiceTest {

    private static final ClusterProperties CLUSTER = new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
            new ClusterProperties.Ports(26100, 26110, 26120, 26130, 26140, 26150));
    private static final ElectionProperties PROPERTIES = new ElectionProperties(400, 1500, 150, 3000, 100, 1500);
    private static final IntUnaryOperator ELECTION_PORTS = id -> 26120 + id;
    private static final int SQUATTER = 26190;
    private static final int UNBOUND = 26199;
    private static final InetAddress LOOPBACK = loopback();

    private ClusterEventBus bus;
    private Cluster cluster;
    private final List<ElectionNodeService> services = new ArrayList<>();
    private final List<RawPeer> rawPeers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), Clock.systemUTC());
        cluster = new Cluster(CLUSTER, bus);
    }

    @AfterEach
    void tearDown() {
        services.forEach(ElectionNodeService::stop);
        rawPeers.forEach(RawPeer::close);
        cluster.close();
        bus.close();
    }

    private ElectionNodeService service(List<Integer> nodeIds, IntUnaryOperator ports) {
        ElectionNodeService service = new ElectionNodeService(cluster.node(1), nodeIds, ports, PROPERTIES, bus,
                Clock.systemUTC(), System::nanoTime);
        services.add(service);
        return service;
    }

    private ElectionNodeService service() {
        return service(List.of(1, 2, 3), ELECTION_PORTS);
    }

    private RawPeer rawPeer(int id) throws SocketException {
        RawPeer peer = new RawPeer(id, ELECTION_PORTS.applyAsInt(id));
        rawPeers.add(peer);
        return peer;
    }

    private static ConditionFactory within() {
        return await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50));
    }

    private static List<String> liveElectionThreads(int nodeId) {
        String prefix = "udcf-election-n" + nodeId + "-";
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith(prefix))
                .toList();
    }

    private List<ClusterEvent> events() {
        return bus.query(ElectionNodeService.MODULE, 1, 5000);
    }

    private static DatagramSocket bind(int port) throws SocketException {
        DatagramSocket socket = new DatagramSocket(null);
        try {
            socket.bind(new InetSocketAddress(LOOPBACK, port));
            return socket;
        } catch (SocketException e) {
            socket.close();
            throw e;
        }
    }

    @Test
    @DisplayName("start binds 127.0.0.1 on the node's election port, with one listener and one worker thread")
    void startBindsLoopbackElectionPort() {
        ElectionNodeService service = service();
        service.start();
        assertThat(service.isRunning()).isTrue();
        assertThat(service.isCrashed()).isFalse();
        assertThat(service.port()).isEqualTo(26121);
        assertThatThrownBy(() -> bind(26121).close()).isInstanceOf(BindException.class);
        assertThat(liveElectionThreads(1))
                .containsExactlyInAnyOrder("udcf-election-n1-listener", "udcf-election-n1-worker");
    }

    @Test
    @DisplayName("a port already taken fails start loudly and leaves no thread behind")
    void portCollisionFailsStartLoudly() throws SocketException {
        try (DatagramSocket squatter = bind(SQUATTER);
             Cluster other = new Cluster(new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
                     new ClusterProperties.Ports(26100, 26110, SQUATTER - 1, 26130, 26140, 26150)), bus)) {
            ElectionNodeService service = new ElectionNodeService(other.node(1), List.of(1, 2, 3),
                    id -> SQUATTER - 1 + id, PROPERTIES, bus, Clock.systemUTC(), System::nanoTime);
            services.add(service);
            assertThatThrownBy(service::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(String.valueOf(SQUATTER))
                    .hasCauseInstanceOf(BindException.class);
            assertThat(service.isRunning()).isFalse();
            assertThat(liveElectionThreads(1)).isEmpty();
            assertThat(squatter.isBound()).isTrue();
        }
    }

    @Test
    @DisplayName("crash closes the socket, joins every udcf-election-n1 thread, frees the port and crashes both algorithms")
    void crashClosesSocketJoinsListenerAndFreesPort() throws SocketException {
        ElectionNodeService service = service();
        service.start();
        service.crash();
        assertThat(service.isRunning()).isFalse();
        assertThat(service.isCrashed()).isTrue();
        assertThat(liveElectionThreads(1)).isEmpty();
        try (DatagramSocket rebound = bind(26121)) {
            assertThat(rebound.isBound()).isTrue();
        }
        assertThat(events()).filteredOn(e -> e.type().equals("CRASH"))
                .extracting(e -> e.data().get("algorithm")).containsExactlyInAnyOrder("BULLY", "RING");
    }

    @Test
    @DisplayName("stop joins every udcf-election-n1 thread and frees the port, without crash events")
    void stopJoinsThreadsAndReleasesPort() throws SocketException {
        ElectionNodeService service = service();
        service.start();
        service.stop();
        assertThat(service.isRunning()).isFalse();
        assertThat(liveElectionThreads(1)).isEmpty();
        try (DatagramSocket rebound = bind(26121)) {
            assertThat(rebound.isBound()).isTrue();
        }
        assertThat(events()).isEmpty();
    }

    @Test
    @DisplayName("recover rebinds the port and the node answers an ELECTION again")
    void recoverRebindsAndRunsAgain() throws SocketException {
        RawPeer peer2 = rawPeer(2);
        ElectionNodeService service = service();
        service.start();
        service.crash();
        service.recover();
        assertThat(service.isRunning()).isTrue();
        assertThat(liveElectionThreads(1))
                .containsExactlyInAnyOrder("udcf-election-n1-listener", "udcf-election-n1-worker");
        peer2.send(26121, "ELECTION|2|5|");
        within().until(() -> !peer2.received(ElectionMessageType.OK).isEmpty());
        assertThat(events()).extracting(ClusterEvent::type).contains("RECOVER");
    }

    @Test
    @DisplayName("repeated start, crash and recover are no-ops and never duplicate threads")
    void crashAndRecoverAreIdempotent() {
        ElectionNodeService service = service();
        service.start();
        service.start();
        service.crash();
        service.crash();
        assertThat(liveElectionThreads(1)).isEmpty();
        service.recover();
        service.recover();
        assertThat(service.isRunning()).isTrue();
        assertThat(liveElectionThreads(1))
                .containsExactlyInAnyOrder("udcf-election-n1-listener", "udcf-election-n1-worker");
    }

    @Test
    @DisplayName("registered through ensureService, it follows the node's crash and recovery")
    void lifecycleFollowsClusterNodeCrashAndRecover() {
        ElectionNodeService service = ElectionNodeService.on(cluster.node(1), cluster, PROPERTIES, bus);
        assertThat(ElectionNodeService.find(cluster.node(1))).containsSame(service);
        assertThat(ElectionNodeService.find(cluster.node(2))).isEmpty();
        assertThat(cluster.node(1).runningServices()).containsExactly("election");
        assertThat(service.nodeIds()).containsExactly(1, 2, 3);

        cluster.crash(1);
        assertThat(service.isRunning()).isFalse();
        assertThat(liveElectionThreads(1)).isEmpty();

        cluster.recover(1);
        assertThat(service.isRunning()).isTrue();
        assertThat(cluster.node(1).runningServices()).containsExactly("election");
    }

    @Test
    @DisplayName("starting an election on a node that is not running throws NodeDownException")
    void startElectionOnCrashedServiceThrowsNodeDown() {
        ElectionNodeService service = service();
        assertThatThrownBy(service::startBully).isInstanceOf(NodeDownException.class);
        service.start();
        service.crash();
        assertThatThrownBy(service::startBully).isInstanceOf(NodeDownException.class);
        assertThatThrownBy(service::startRing).isInstanceOf(NodeDownException.class);
    }

    @Test
    @DisplayName("garbage, oversize, unknown-sender and self-sent datagrams are dropped; the listener keeps going")
    void listenerSurvivesMalformedAndOversizeDatagrams() throws SocketException {
        RawPeer peer2 = rawPeer(2);
        ElectionNodeService service = service();
        service.start();
        peer2.send(26121, "garbage");
        peer2.send(26121, "ELECTION|x|y|");
        byte[] oversize = new byte[2000];
        Arrays.fill(oversize, (byte) 'A');
        peer2.sendBytes(26121, oversize);
        peer2.send(26121, "ELECTION|9|1|");
        peer2.send(26121, "ELECTION|1|1|");
        peer2.send(26121, "ELECTION|2|5|");
        within().until(() -> !peer2.received(ElectionMessageType.OK).isEmpty());
        assertThat(peer2.received(ElectionMessageType.OK)).hasSize(1);
        assertThat(service.isRunning()).isTrue();
    }

    @Test
    @DisplayName("heartbeats to a port nobody listens on (ICMP port unreachable) do not stop the listener")
    void listenerSurvivesPortUnreachable() throws SocketException {
        RawPeer peer3 = rawPeer(3);
        ElectionNodeService service = service(List.of(1, 2, 3), id -> id == 2 ? UNBOUND : ELECTION_PORTS.applyAsInt(id));
        service.start();
        within().until(() -> peer3.received(ElectionMessageType.HEARTBEAT).size() >= 5);
        peer3.send(26121, "ELECTION|3|5|");
        within().until(() -> !peer3.received(ElectionMessageType.OK).isEmpty());
        assertThat(service.isRunning()).isTrue();
        assertThat(liveElectionThreads(1)).contains("udcf-election-n1-listener");
    }

    @Test
    @DisplayName("L4: a received message moves the receiver's clock past the sender's stamp, and the reply carries it")
    void receivedMessageAdvancesReceiverClockPastSenderStamp() throws SocketException {
        RawPeer peer2 = rawPeer(2);
        ElectionNodeService service = service();
        service.start();
        peer2.send(26121, "ELECTION|2|500|");
        within().until(() -> !peer2.received(ElectionMessageType.OK).isEmpty());

        ClusterEvent received = events().stream()
                .filter(e -> e.type().equals(ElectionNodeService.MESSAGE_RECEIVED)).findFirst().orElseThrow();
        assertThat(received.lamportTime()).isEqualTo(501);
        assertThat(received.peerId()).isEqualTo(2);
        assertThat(received.data()).containsEntry("causedByTime", 500L).containsEntry("messageType", "ELECTION");

        ElectionMessage ok = peer2.received(ElectionMessageType.OK).get(0);
        assertThat(ok.senderId()).isEqualTo(1);
        assertThat(ok.lamportTime()).isGreaterThan(501);
        assertThat(cluster.node(1).clock().current()).isGreaterThanOrEqualTo(ok.lamportTime());
    }

    @Test
    @DisplayName("deviation from L4: sending and receiving heartbeats leaves the node clock unchanged")
    void heartbeatsDoNotTouchNodeClock() throws SocketException {
        RawPeer peer2 = rawPeer(2);
        ElectionNodeService service = service(List.of(1, 2), ELECTION_PORTS);
        service.start();
        long before = cluster.node(1).clock().current();
        within().until(() -> {
            peer2.send(26121, "HEARTBEAT|2|9999|");   // a stamp, if there were one, must never be applied
            return peer2.received(ElectionMessageType.HEARTBEAT).size() >= 5
                    && service.failureDetector().peers().get(0).millisSinceLastHeartbeat() != null;
        });
        assertThat(peer2.received(ElectionMessageType.HEARTBEAT))
                .allSatisfy(heartbeat -> assertThat(heartbeat.lamportTime()).isZero());
        assertThat(cluster.node(1).clock().current()).isEqualTo(before);
        assertThat(events()).isEmpty();
    }

    @Test
    @DisplayName("hard rule 7: Bully, Ring, timer and failure-detector callbacks run on the worker, never the listener")
    void algorithmWorkRunsOnWorkerNeverOnListenerThread() throws SocketException {
        RawPeer peer2 = rawPeer(2);
        peer2.ackProbes = true;
        ElectionNodeService service = service(List.of(1, 2, 3), id -> id == 3 ? UNBOUND : ELECTION_PORTS.applyAsInt(id));
        List<String> threads = new CopyOnWriteArrayList<>();
        List<String> seen = new CopyOnWriteArrayList<>();
        service.addElectionListener(event -> {
            threads.add(Thread.currentThread().getName());
            seen.add(event.type().name());
        });
        service.failureDetector().addListener(new FailureListener() {
            @Override
            public void onSuspected(int peerId, long silentMillis) {
                threads.add(Thread.currentThread().getName());
                seen.add("SUSPECTED " + peerId);
            }

            @Override
            public void onAlive(int peerId, long silentMillis) {
                threads.add(Thread.currentThread().getName());
            }
        });
        service.start();
        peer2.send(26121, "RING_ELECTION|2|7|2");   // node 1 appends itself, probes 2 (acked) and forwards
        peer2.send(26121, "ELECTION|2|8|");         // Bully: OK, own election, then the OK timeout elects node 1
        within().until(() -> seen.containsAll(List.of("TOKEN_FORWARDED", "ELECTION_RESTART", "ELECTED", "SUSPECTED 3")));
        assertThat(threads).isNotEmpty().containsOnly("udcf-election-n1-worker");
        assertThat(peer2.received(ElectionMessageType.RING_ELECTION)).extracting(ElectionMessage::payload).contains("2,1");
    }

    /** A bare UDP socket speaking the election wire format, standing in for a peer node. */
    static final class RawPeer implements AutoCloseable {

        private final int id;
        private final DatagramSocket socket;
        private final Thread reader;
        private final List<ElectionMessage> received = new CopyOnWriteArrayList<>();
        volatile boolean ackProbes;

        RawPeer(int id, int port) throws SocketException {
            this.id = id;
            this.socket = bind(port);
            this.reader = new Thread(this::read, "test-raw-peer-" + id);
            reader.setDaemon(true);
            reader.start();
        }

        List<ElectionMessage> received(ElectionMessageType type) {
            return received.stream().filter(m -> m.type() == type).toList();
        }

        void send(int port, String wire) {
            sendBytes(port, wire.getBytes(StandardCharsets.UTF_8));
        }

        void sendBytes(int port, byte[] data) {
            sendTo(new InetSocketAddress(LOOPBACK, port), data);
        }

        private void sendTo(SocketAddress target, byte[] data) {
            try {
                socket.send(new DatagramPacket(data, data.length, target));
            } catch (IOException e) {
                throw new IllegalStateException("raw peer " + id + " could not send", e);
            }
        }

        private void read() {
            byte[] buffer = new byte[2048];
            while (!socket.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (IOException e) {
                    continue;   // closed (loop ends) or an ICMP refusal reported on Windows
                }
                ElectionMessage message;
                try {
                    message = ElectionMessage.fromWire(
                            new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                received.add(message);
                if (ackProbes && message.type() == ElectionMessageType.PROBE) {
                    sendTo(packet.getSocketAddress(), ("PROBE_ACK|" + id + "|1|").getBytes(StandardCharsets.UTF_8));
                }
            }
        }

        @Override
        public void close() {
            socket.close();
            try {
                reader.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        } catch (UnknownHostException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
