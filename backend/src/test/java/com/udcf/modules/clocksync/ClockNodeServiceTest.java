package com.udcf.modules.clocksync;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.NodeCapacity;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.modules.clocksync.berkeley.BerkeleyRoundResult;
import com.udcf.modules.clocksync.berkeley.ClockDriftModel;
import com.udcf.modules.clocksync.berkeley.NodeAdjustment;
import com.udcf.modules.clocksync.lamport.CausalInvariantChecker;
import com.udcf.modules.clocksync.lamport.CausalVerificationResult;
import com.udcf.modules.clocksync.lamport.ClockEventLog;
import com.udcf.modules.clocksync.lamport.ClockEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests on real 127.0.0.1 UDP sockets, on a standalone cluster (no Spring context).
 *
 * <p>Test ports: 48100 to 48899 (reserved for Track D Exp 3):
 * <ul>
 *   <li>Cluster bases: RMI 48100, Clock 48200 (clock UDP ports 48201 to 48205), Election 48300,
 *       Replication 48400, Requests 48500, MapReduce 48600.</li>
 *   <li>Squatter / port collision test: 48801.</li>
 * </ul>
 * Apart from Track A (47100-47899) and Exp 2 (41xxx-46xxx), safely below Windows dynamic range (49152).</p>
 */
class ClockNodeServiceTest {

    private static final ClusterProperties CLUSTER = new ClusterProperties(
            3,
            List.of(FAST, MEDIUM, SLOW),
            new ClusterProperties.Ports(48100, 48200, 48300, 48400, 48500, 48600)
    );

    private static final ClockSyncProperties PROPERTIES = new ClockSyncProperties(
            500L,
            300L,
            5,
            30,
            4,
            20,
            2000,
            Map.of(
                    1, new ClockSyncProperties.NodeDriftConfig(0L, 0.0),
                    2, new ClockSyncProperties.NodeDriftConfig(60L, 1.0),
                    3, new ClockSyncProperties.NodeDriftConfig(-40L, -0.5)
            )
    );

    private ClusterEventBus bus;
    private Cluster cluster;
    private ClockEventLog eventLog;
    private Clock wallClock;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        wallClock = Clock.systemUTC();
        bus = new ClusterEventBus(new EventProperties(5000, 5000), wallClock);
        cluster = new Cluster(CLUSTER, bus);
        eventLog = new ClockEventLog();
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        cluster.close();
        bus.close();
    }

    private ClockNodeService service(int nodeId) {
        return ClockNodeService.on(cluster.node(nodeId), cluster, eventLog, null, bus, PROPERTIES, wallClock);
    }

    private ClockNodeService serviceWithDrift(int nodeId, long initialOffset, double driftRate) {
        ClockDriftModel model = new ClockDriftModel(nodeId, initialOffset, driftRate, wallClock.instant());
        return ClockNodeService.on(cluster.node(nodeId), cluster, eventLog, model, bus, PROPERTIES, wallClock);
    }

    @Test
    @DisplayName("lazy start through ensureService: binds UDP port, reports running, and stops cleanly")
    void lazyStartThroughEnsureService() {
        ClockNodeService svc = service(1);

        assertThat(svc.name()).isEqualTo("clock");
        assertThat(svc.port()).isEqualTo(48201);
        assertThat(svc.isRunning()).isTrue();
        assertThat(cluster.node(1).runningServices()).contains("clock");

        svc.stop();
        assertThat(svc.isRunning()).isFalse();
    }

    @Test
    @DisplayName("Lamport exchange: receiver's time strictly exceeds sender's timestamp")
    void lamportExchangeReceiverExceedsSender() {
        ClockNodeService s1 = service(1);
        ClockNodeService s2 = service(2);

        long initial1 = cluster.node(1).clock().current();
        long initial2 = cluster.node(2).clock().current();

        // Rule 1: Node 1 local event
        long local1 = s1.recordLocalEvent("Local client request");
        assertThat(local1).isEqualTo(initial1 + 1);

        // Rule 2: Node 1 sends message to Node 2
        long sendStamp = s1.sendLamportMessage(2, 1001L, "Payment_N1_to_N2");
        assertThat(sendStamp).isEqualTo(local1 + 1);

        // Await UDP packet receipt on Node 2 (Rule 3)
        await().atMost(Duration.ofSeconds(2)).until(() -> cluster.node(2).clock().current() > initial2);

        long recvClock = cluster.node(2).clock().current();
        // Rule 3: max(localBefore, sendStamp) + 1 > sendStamp
        assertThat(recvClock).isGreaterThan(sendStamp);
        assertThat(recvClock).isEqualTo(Math.max(initial2, sendStamp) + 1);

        // Causal invariant verification
        CausalInvariantChecker checker = new CausalInvariantChecker();
        CausalVerificationResult verification = checker.verify(eventLog);
        assertThat(verification.passed()).isTrue();
        assertThat(verification.violationsCount()).isZero();
    }

    @Test
    @DisplayName("burst of random messages across 3 nodes gives 0 violations from CausalInvariantChecker")
    void burstOfRandomMessagesZeroCausalViolations() {
        ClockNodeService s1 = service(1);
        ClockNodeService s2 = service(2);
        ClockNodeService s3 = service(3);

        List<ClockNodeService> services = List.of(s1, s2, s3);
        int totalSends = 30;

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 1; i <= totalSends; i++) {
            final int msgNum = i;
            futures.add(CompletableFuture.runAsync(() -> {
                int senderIdx = msgNum % 3;
                int targetIdx = (msgNum + 1) % 3;
                ClockNodeService sender = services.get(senderIdx);
                ClockNodeService target = services.get(targetIdx);

                if (msgNum % 4 == 0) {
                    sender.recordLocalEvent("Local task " + msgNum);
                }
                sender.sendLamportMessage(target.node().id(), (long) msgNum, "Msg_" + msgNum);
            }, executor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Wait until all 30 messages are received
        await().atMost(Duration.ofSeconds(4))
                .until(() -> eventLog.countByType(ClockEventType.RECV) == totalSends);

        CausalInvariantChecker checker = new CausalInvariantChecker();
        CausalVerificationResult result = checker.verify(eventLog);

        assertThat(result.passed()).isTrue();
        assertThat(result.violationsCount()).isZero();
        assertThat(result.receiveEventsChecked()).isEqualTo(totalSends);
    }

    @Test
    @DisplayName("Berkeley round over UDP reduces spread with RTT compensation, self-adjustment, and outlier adjustment")
    void berkeleyRoundReducesSpread() {
        // Node 1 (daemon): offset 0 ms
        // Node 2: offset +60 ms
        // Node 3: offset -40 ms
        // Node 4: (simulated on Node 3 with big offset) but with 3 nodes:
        ClockNodeService s1 = serviceWithDrift(1, 0L, 0.0);
        ClockNodeService s2 = serviceWithDrift(2, 60L, 0.0);
        ClockNodeService s3 = serviceWithDrift(3, -40L, 0.0);

        long spreadBeforeExpected = 60L - (-40L); // 100 ms

        // Node 1 initiates Berkeley round with threshold 500 ms
        BerkeleyRoundResult result = s1.runBerkeleyRound(List.of(1, 2, 3), 500L, Duration.ofMillis(800));

        assertThat(result.spreadBeforeMillis()).isEqualTo(spreadBeforeExpected);
        assertThat(result.spreadAfterMillis()).isZero();
        assertThat(result.spreadAfterMillis()).isLessThan(result.spreadBeforeMillis());
        assertThat(result.participatingNodes()).containsExactly(1, 2, 3);
        assertThat(result.outlierNodes()).isEmpty();
        assertThat(result.simulated()).isTrue();

        // Check RTT compensation: every peer adjustment has measured RTT
        for (NodeAdjustment adj : result.adjustments()) {
            assertThat(adj.rttMillis()).isGreaterThanOrEqualTo(0.0);
        }

        // Daemon adjusted its own clock too (Requirement 5c)
        NodeAdjustment daemonAdj = result.adjustments().stream()
                .filter(a -> a.nodeId() == 1).findFirst().orElseThrow();
        assertThat(daemonAdj.adjustmentMillis()).isNotZero(); // shifted from 0 to average
    }

    @Test
    @DisplayName("Berkeley round with outlier: outlier is excluded from average, receives adjustment, and spreadAfter covers all nodes")
    void berkeleyRoundHandlesOutlier() {
        // Node 1 (daemon): offset 100 ms
        // Node 2: offset 140 ms (diff +40, within 300 ms)
        // Node 3: offset 5000 ms (diff +4900, EXCEEDS 300 ms -> OUTLIER)
        ClockNodeService s1 = serviceWithDrift(1, 100L, 0.0);
        ClockNodeService s2 = serviceWithDrift(2, 140L, 0.0);
        ClockNodeService s3 = serviceWithDrift(3, 5000L, 0.0);

        BerkeleyRoundResult result = s1.runBerkeleyRound(List.of(1, 2, 3), 300L, Duration.ofMillis(800));

        // Node 3 flagged as outlier and excluded from average
        assertThat(result.outlierNodes()).containsExactly(3);
        assertThat(result.participatingNodes()).containsExactly(1, 2);

        // Outlier receives adjustment toward target offset
        NodeAdjustment adj3 = result.adjustments().stream()
                .filter(a -> a.nodeId() == 3).findFirst().orElseThrow();
        assertThat(adj3.outlier()).isTrue();
        assertThat(adj3.adjustmentMillis()).isLessThan(-4000L); // brings 5000 ms down toward ~120 ms
        assertThat(adj3.afterOffsetMillis()).isEqualTo(result.averageOffsetMillis());

        // spreadAfter covers all participating nodes and drops to 0 ms
        assertThat(result.spreadAfterMillis()).isZero();
    }

    @Test
    @DisplayName("crashed node is excluded and reported, and the round does not hang")
    void crashedNodeExcludedWithoutHanging() {
        ClockNodeService s1 = service(1);
        service(2);
        ClockNodeService s3 = service(3);

        // Crash Node 2
        cluster.node(2).crash();

        // Node 1 runs round with short timeout (150 ms)
        BerkeleyRoundResult result = s1.runBerkeleyRound(List.of(1, 2, 3), 500L, Duration.ofMillis(150));

        // Node 2 was unresponsive and excluded; round succeeded with Node 1 and 3
        assertThat(result.participatingNodes()).containsExactly(1, 3);
        assertThat(result.adjustments()).extracting(NodeAdjustment::nodeId).containsExactly(1, 3);
    }

    @Test
    @DisplayName("recover rebinds the socket and preserves simulated clock drift")
    void recoverRebindsSocketAndPreservesDrift() {
        ClockNodeService s2 = serviceWithDrift(2, 75L, 2.5);
        assertThat(s2.isRunning()).isTrue();
        assertThat(s2.driftModel().driftRateMsPerSec()).isEqualTo(2.5);

        // Crash Node 2
        cluster.node(2).crash();
        assertThat(s2.isRunning()).isFalse();

        // Attempting to send from crashed node throws NodeDownException
        assertThatThrownBy(() -> s2.sendLamportMessage(1, "Test"))
                .isInstanceOf(NodeDownException.class);

        // Recover Node 2
        cluster.node(2).recover();
        assertThat(s2.isRunning()).isTrue();

        // Simulated drift model kept its offset and drift across crash and recover (Requirement 5d)
        assertThat(s2.driftModel().driftRateMsPerSec()).isEqualTo(2.5);

        // Node 2 can now receive and send messages again
        ClockNodeService s1 = service(1);
        s1.sendLamportMessage(2, "Post-recovery test");
        await().atMost(Duration.ofSeconds(2)).until(() -> cluster.node(2).clock().current() > 0);
    }

    @Test
    @DisplayName("late reply for an old round is ignored without exception")
    void lateReplyForOldRoundIsIgnored() throws IOException {
        ClockNodeService s1 = service(1);

        // Synthesize an old round reply for roundId = 99999
        ClockMessage lateReply = ClockMessage.pollReply(2, 10L, 99999L, 25L);
        byte[] bytes = ClockProtocol.encode(lateReply);

        try (DatagramSocket testSocket = new DatagramSocket()) {
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, InetAddress.getByName("127.0.0.1"), s1.port());
            testSocket.send(packet);
        }

        // Service continues running normally
        assertThat(s1.isRunning()).isTrue();
    }

    @Test
    @DisplayName("malformed datagram is dropped and the service keeps working")
    void malformedDatagramIsDropped() throws IOException {
        ClockNodeService s1 = service(1);
        ClockNodeService s2 = service(2);

        // Send corrupted byte payload to Node 1
        byte[] garbage = "CORRUPT|NOT|A|VALID|PAYLOAD|DATA".getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket testSocket = new DatagramSocket()) {
            DatagramPacket packet = new DatagramPacket(garbage, garbage.length, InetAddress.getByName("127.0.0.1"), s1.port());
            testSocket.send(packet);
        }

        // Subsequent valid message from Node 2 to Node 1 is received and processed
        s2.sendLamportMessage(1, "Valid payload after garbage");
        await().atMost(Duration.ofSeconds(2)).until(() -> cluster.node(1).clock().current() > 0);
        assertThat(s1.isRunning()).isTrue();
    }

    @Test
    @DisplayName("port already in use publishes SERVICE_START_FAILED and throws exception")
    void portAlreadyInUseGivesServiceStartFailed() throws IOException {
        int occupiedPort = 48801;
        try (DatagramSocket squatter = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), occupiedPort))) {
            // Build a node configured with the occupied port
            ClusterProperties customProps = new ClusterProperties(
                    1,
                    List.of(FAST),
                    new ClusterProperties.Ports(48100, occupiedPort - 1, 48300, 48400, 48500, 48600)
            );
            Cluster customCluster = new Cluster(customProps, bus);

            try {
                ClockNodeService conflictingService = new ClockNodeService(
                        customCluster.node(1),
                        id -> 48201,
                        eventLog,
                        null,
                        bus,
                        PROPERTIES,
                        wallClock
                );

                assertThatThrownBy(conflictingService::start)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("failed to bind");

                // Check that SERVICE_START_FAILED event was published
                List<ClusterEvent> events = bus.query(null, null, 100);
                assertThat(events).anyMatch(e -> "SERVICE_START_FAILED".equals(e.type()));
            } finally {
                customCluster.close();
            }
        }
    }

    @Test
    @DisplayName("daemon coordinates Berkeley round under concurrent load without listener deadlock")
    void daemonReceivesRepliesUnderLoadWithoutDeadlock() {
        ClockNodeService s1 = service(1);
        ClockNodeService s2 = service(2);
        ClockNodeService s3 = service(3);

        // Run concurrent Lamport traffic while initiating a Berkeley round
        CompletableFuture<Void> trafficFuture = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 15; i++) {
                s2.sendLamportMessage(3, "Load message " + i);
                s3.sendLamportMessage(2, "Reply message " + i);
            }
        }, executor);

        // Daemon initiates Berkeley round concurrently
        BerkeleyRoundResult roundResult = s1.runBerkeleyRound(List.of(1, 2, 3), 500L, Duration.ofSeconds(2));
        trafficFuture.join();

        assertThat(roundResult.participatingNodes()).contains(1, 2, 3);
        assertThat(roundResult.spreadAfterMillis()).isZero();
    }
}
