package com.udcf.modules.replication;

import com.udcf.core.clock.LamportClock;
import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.cluster.UnknownNodeException;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The replication service on real 127.0.0.1 sockets, on a standalone three-node cluster. No
 * Spring context, so crashes here never disturb other tests. No sleeps: futures, latches and
 * bounded latch waits only.
 *
 * <p>Test-only port bases 28100 to 28600, so node k's replication port is 2840k: below Linux's
 * ephemeral range (32768 and up) and Windows' dynamic range (49152 and up), apart from every
 * other test class, and away from the production ports.</p>
 */
class ReplicationNodeServiceTest {

    private static final ClusterProperties CLUSTER = new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
            new ClusterProperties.Ports(28100, 28200, 28300, 28400, 28500, 28600));
    private static final ReplicationProperties PROPERTIES = new ReplicationProperties(50, 2000, 200);
    private static final List<Integer> BOTH = List.of(2, 3);
    private static final int RAW_SENDER = 9;

    private ClusterEventBus bus;
    private Cluster cluster;
    private ReplicationClient reader;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), Clock.systemUTC());
        cluster = new Cluster(CLUSTER, bus);
        reader = new ReplicationClient(2000);
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    private ReplicationNodeService service(int nodeId) {
        return service(nodeId, PROPERTIES, ReplicationNodeService.NO_PROBE);
    }

    private ReplicationNodeService service(int nodeId, ReplicationProperties properties) {
        return service(nodeId, properties, ReplicationNodeService.NO_PROBE);
    }

    private ReplicationNodeService service(int nodeId, ReplicationProperties properties, ReplicationNodeService.Probe probe) {
        return cluster.node(nodeId).ensureService(ReplicationNodeService.NAME, n -> new ReplicationNodeService(n,
                peer -> cluster.node(peer).ports().replication(), properties, bus, Clock.systemUTC(), probe));
    }

    /** Starts nodes 1 to 3 and makes node 1 primary at epoch 1. */
    private ReplicationNodeService primaryWithBackups(ReplicationProperties properties) {
        ReplicationNodeService primary = service(1, properties);
        service(2, properties);
        service(3, properties);
        primary.becomePrimary(1);
        return primary;
    }

    private int port(int nodeId) {
        return cluster.node(nodeId).ports().replication();
    }

    private Optional<DataItem> readOver(int nodeId, String key) throws IOException {
        return reader.read(port(nodeId), 0, new LamportClock(), key).item();
    }

    private List<ClusterEvent> events(int nodeId, String type) {
        return bus.query(ReplicationNodeService.MODULE, nodeId, 5000).stream()
                .filter(event -> event.type().equals(type))
                .toList();
    }

    private List<ClusterEvent> replicaEvents(int nodeId) {
        return bus.query(ReplicationNodeService.MODULE, nodeId, 5000).stream()
                .filter(event -> event.type().startsWith("REPLICA_") || event.type().equals("ANTI_ENTROPY_MERGED"))
                .toList();
    }

    /** Sends raw bytes and returns every line until the server closes the connection. */
    private List<String> exchangeRaw(int nodeId, String raw) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ReplicationProtocol.LOOPBACK, port(nodeId)), 2000);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(raw.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            socket.shutdownOutput();   // everything is sent, so the server's drain after an ERROR ends at once
            InputStream in = socket.getInputStream();
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = ReplicationProtocol.readLine(in)) != null) {
                lines.add(line);
            }
            return lines;
        }
    }

    private static String itemLine(String key, long epoch) {
        return ReplicationProtocol.encodeItem(new DataItem(key, "raw", 1, RAW_SENDER, epoch));
    }

    private static Set<Thread> liveReplicationThreads(int nodeId) {
        String prefix = "udcf-replication-n" + nodeId + "-";
        Set<Thread> threads = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith(prefix) && thread.isAlive()) {
                threads.add(thread);
            }
        }
        return threads;
    }

    private static Set<Thread> newThreads(int nodeId, Set<Thread> before) {
        Set<Thread> now = liveReplicationThreads(nodeId);
        now.removeAll(before);
        return now;
    }

    // ------------------------------------------------------------------ start, sync, async

    @Test
    @DisplayName("nothing listens until first use; then the service starts lazily on ports().replication()")
    void startsLazilyOnReplicationPort() throws IOException {
        assertThat(ReplicationNodeService.find(cluster.node(1))).isEmpty();
        assertThatThrownBy(() -> readOver(1, "k")).isInstanceOf(ConnectException.class);

        ReplicationNodeService service = ReplicationNodeService.on(cluster.node(1), cluster, PROPERTIES, bus);

        assertThat(service.port()).isEqualTo(28401);
        assertThat(service.nodeId()).isEqualTo(1);
        assertThat(readOver(1, "k")).isEmpty();
        assertThat(cluster.node(1).runningServices()).containsExactly(ReplicationNodeService.NAME);
        assertThat(ReplicationNodeService.on(cluster.node(1), cluster, PROPERTIES, bus)).isSameAs(service);
        assertThat(ReplicationNodeService.find(cluster.node(1))).containsSame(service);
        assertThat(service.epoch()).isEqualTo(DataStore.INITIAL_EPOCH);
        assertThat(service.isPrimary()).isFalse();
    }

    @Test
    @DisplayName("SYNCHRONOUS: every backup acknowledges before the write is confirmed, with measured latency and no simulated delay")
    void syncWriteAckedByEveryBackupBeforeConfirm() throws IOException {
        ReplicationNodeService primary = primaryWithBackups(PROPERTIES);

        WriteResult result = primary.write("k", "v", ConsistencyModel.SYNCHRONOUS, BOTH);

        assertThat(result.replication()).isDone();
        assertThat(result.replication().join()).extracting(PushOutcome::backupId, PushOutcome::status, PushOutcome::result)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2, PushStatus.ACKED, Optional.of(ApplyResult.APPLIED)),
                        org.assertj.core.groups.Tuple.tuple(3, PushStatus.ACKED, Optional.of(ApplyResult.APPLIED)));
        assertThat(result.localResult()).isEqualTo(ApplyResult.APPLIED);
        assertThat(result.simulatedDelayMillis()).isZero();
        assertThat(result.simulated()).isFalse();
        assertThat(readOver(2, "k")).contains(result.item());
        assertThat(readOver(3, "k")).contains(result.item());
        assertThat(primary.stats()).containsOnlyKeys(2, 3);
        assertThat(primary.stats().get(2).acks()).isEqualTo(1);
        assertThat(primary.stats().get(2).applied()).isEqualTo(1);
        assertThat(primary.stats().get(2).averageLatencyMillis()).isPresent();
        assertThat(primary.stats().get(2).lastSync()).isPresent();

        assertThat(events(1, "WRITE")).hasSize(1);
        assertThat(events(1, "ACK")).extracting(ClusterEvent::peerId).containsExactlyInAnyOrder(2, 3);
        assertThat(events(1, "WRITE_CONFIRMED")).singleElement()
                .satisfies(event -> assertThat(event.data()).containsEntry("acked", 2L).containsEntry("failed", 0L)
                        .doesNotContainKey("simulated"));
        assertThat(events(2, "REPLICA_APPLIED")).singleElement().satisfies(event -> assertThat(event.peerId()).isEqualTo(1));

        primary.resetStats();
        assertThat(primary.stats().get(2).acks()).isZero();
    }

    @Test
    @DisplayName("ASYNCHRONOUS (60 s simulated delay): confirmed before any backup has it, a backup read is stale, and a crash drops the pending pushes")
    void asyncConfirmsBeforeBackupsHaveIt() throws Exception {
        ReplicationNodeService primary = primaryWithBackups(new ReplicationProperties(60_000, 2000, 200));

        WriteResult result = primary.write("k", "v", ConsistencyModel.ASYNCHRONOUS, BOTH);

        assertThat(result.replication()).isNotDone();
        assertThat(result.simulatedDelayMillis()).isEqualTo(60_000);
        assertThat(result.simulated()).isTrue();
        assertThat(primary.get("k")).contains(result.item());
        assertThat(readOver(2, "k")).as("stale read from a backup").isEmpty();
        assertThat(events(1, "WRITE_CONFIRMED")).singleElement().satisfies(event -> assertThat(event.data())
                .containsEntry("simulated", true).containsEntry("simulatedDelayMillis", 60_000L));

        assertThat(cluster.crash(1)).isTrue();

        assertThat(result.replication().get(5, TimeUnit.SECONDS)).extracting(PushOutcome::status)
                .containsExactly(PushStatus.NOT_SENT, PushStatus.NOT_SENT);
        assertThat(events(1, "ASYNC_PUSHES_DROPPED")).singleElement().satisfies(event -> assertThat(event.data())
                .containsEntry("pushes", 2).containsEntry("keys", List.of("k")));
        assertThat(primary.stats().values()).allSatisfy(stats -> assertThat(stats.failures()).isZero());
        assertThat(readOver(2, "k")).as("never delivered").isEmpty();
    }

    @Test
    @DisplayName("ASYNCHRONOUS (50 ms simulated delay): the pushes follow and every backup converges")
    void asyncConverges() throws Exception {
        ReplicationNodeService primary = primaryWithBackups(PROPERTIES);

        WriteResult result = primary.write("k", "v", ConsistencyModel.ASYNCHRONOUS, BOTH);
        List<PushOutcome> outcomes = result.replication().get(10, TimeUnit.SECONDS);

        assertThat(outcomes).extracting(PushOutcome::status).containsExactly(PushStatus.ACKED, PushStatus.ACKED);
        assertThat(outcomes).extracting(PushOutcome::result)
                .containsOnly(Optional.of(ApplyResult.APPLIED));
        assertThat(readOver(2, "k")).contains(result.item());
        assertThat(readOver(3, "k")).contains(result.item());
        assertThat(events(1, "ACK")).allSatisfy(event -> assertThat(event.data())
                .containsEntry("simulated", true).containsEntry("simulatedDelayMillis", 50L));
    }

    @Test
    @DisplayName("keys and values with ';', '~' and '|' cross the socket exactly: read, dump and the backup's own store")
    void wireSafeEndToEnd() throws IOException {
        ReplicationNodeService primary = primaryWithBackups(PROPERTIES);
        String key = "a;b~c|d";
        String value = "x;y~z;~|é😀\uD800";

        WriteResult result = primary.write(key, value, ConsistencyModel.SYNCHRONOUS, BOTH);

        assertThat(readOver(2, key)).contains(result.item());
        assertThat(reader.dump(port(3), 0, new LamportClock(), 200).items()).containsEntry(key, result.item());
        assertThat(service(2).get(key)).contains(result.item());
        assertThat(service(3).snapshot()).containsEntry(key, result.item());
    }

    @Test
    @DisplayName("Lamport time crosses the socket both ways with exact values (L4)")
    void lamportCrossesTheSocket() {
        ReplicationNodeService primary = service(1);
        service(2);
        primary.becomePrimary(1);
        cluster.node(1).clock().update(99);                       // 100

        WriteResult result = primary.write("k", "v", ConsistencyModel.SYNCHRONOUS, List.of(2));

        // write tick 101, send tick 102; node 2 receives max(c, 102) + 1 = 103 and replies 104;
        // node 1 merges 105, ACK event 106, WRITE_CONFIRMED 107.
        assertThat(result.item().lamportTime()).isEqualTo(101);
        assertThat(events(2, "REPLICA_APPLIED")).singleElement().satisfies(event -> {
            assertThat(event.lamportTime()).isEqualTo(104);
            assertThat(event.data()).containsEntry("receiveLamport", 103L).containsEntry("itemLamport", 101L);
        });
        assertThat(events(1, "ACK")).singleElement().satisfies(event -> assertThat(event.lamportTime()).isEqualTo(106));
        assertThat(cluster.node(1).clock().current()).isEqualTo(107);
    }

    // ------------------------------------------------------------------ failures and crashed nodes

    @Test
    @DisplayName("a crashed backup is a FAILED push and a failure in the stats, never a stale result")
    void crashedBackupIsFailureNotStale() throws IOException {
        ReplicationNodeService primary = primaryWithBackups(PROPERTIES);
        cluster.crash(3);

        WriteResult result = primary.write("k", "v", ConsistencyModel.SYNCHRONOUS, BOTH);

        assertThat(result.replication().join()).satisfiesExactly(
                two -> assertThat(two.status()).isEqualTo(PushStatus.ACKED),
                three -> {
                    assertThat(three.status()).isEqualTo(PushStatus.FAILED);
                    assertThat(three.result()).isEmpty();
                    assertThat(three.latencyMillis()).isEmpty();
                    assertThat(three.detail()).hasValueSatisfying(detail -> assertThat(detail).contains("ConnectException"));
                });
        assertThat(primary.stats().get(3).failures()).isEqualTo(1);
        assertThat(primary.stats().get(3).staleRejections()).isZero();
        assertThat(primary.stats().get(3).acks()).isZero();
        assertThat(events(1, "REPLICATION_FAILED")).singleElement()
                .satisfies(event -> assertThat(event.peerId()).isEqualTo(3));
        assertThat(readOver(2, "k")).contains(result.item());
    }

    @Test
    @DisplayName("a crashed node refuses to serve: TCP reads are refused and get, snapshot and write throw NodeDownException")
    void crashedNodeRefusesToServe() {
        ReplicationNodeService primary = primaryWithBackups(PROPERTIES);
        ReplicationNodeService backup = service(2);
        cluster.crash(2);
        cluster.crash(1);

        assertThatThrownBy(() -> readOver(2, "k")).isInstanceOf(ConnectException.class);
        assertThatThrownBy(() -> backup.get("k")).isInstanceOf(NodeDownException.class);
        assertThatThrownBy(backup::snapshot).isInstanceOf(NodeDownException.class);
        assertThatThrownBy(() -> primary.write("k", "v", ConsistencyModel.SYNCHRONOUS, BOTH))
                .isInstanceOf(NodeDownException.class);
        assertThatThrownBy(() -> primary.antiEntropy(3)).isInstanceOf(NodeDownException.class);
        assertThatThrownBy(() -> service(2)).isInstanceOf(NodeDownException.class);
        assertThat(primary.isPrimary()).as("the role survives a crash").isTrue();
    }

    @Test
    @DisplayName("after crash() returns a connection is refused at once; recover() serves again with the store and role kept")
    void crashRefusesAtOnceThenRecoverServes() throws IOException {
        ReplicationNodeService primary = primaryWithBackups(PROPERTIES);
        WriteResult result = primary.write("k", "v", ConsistencyModel.SYNCHRONOUS, BOTH);

        assertThat(cluster.crash(1)).isTrue();
        assertThatThrownBy(() -> readOver(1, "k")).isInstanceOf(ConnectException.class);

        assertThat(cluster.recover(1)).isTrue();
        List<ClusterEvent> recoveries = bus.query("cluster", 1, 1000).stream()
                .filter(event -> event.type().equals("NODE_RECOVERED")).toList();
        assertThat(recoveries).last().satisfies(event -> assertThat((List<?>) event.data().get("failures")).isEmpty());
        assertThat(primary.isRunning()).isTrue();
        assertThat(readOver(1, "k")).contains(result.item());
        assertThat(primary.isPrimary()).isTrue();
        assertThat(primary.write("k2", "v2", ConsistencyModel.SYNCHRONOUS, BOTH).replication().join())
                .extracting(PushOutcome::status).containsOnly(PushStatus.ACKED);
    }

    @Test
    @DisplayName("a port already in use fails the start and is reported; once free, the service starts")
    void bindFailureReported() throws IOException {
        try (ServerSocket occupier = new ServerSocket()) {
            occupier.bind(new InetSocketAddress(ReplicationProtocol.LOOPBACK, port(1)));

            assertThatThrownBy(() -> service(1)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("failed to start");
            assertThat(bus.query("cluster", 1, 100)).extracting(ClusterEvent::type).contains("SERVICE_START_FAILED");
        }
        assertThat(service(1).isRunning()).isTrue();
    }

    // ------------------------------------------------------------------ anti-entropy and stale updates

    @Test
    @DisplayName("a crashed backup diverges, then anti-entropy brings it back to consistent; anti-entropy is not counted in the stats")
    void antiEntropyConvergesRecoveredBackup() throws IOException {
        ReplicationNodeService primary = primaryWithBackups(PROPERTIES);
        primary.write("k1", "a", ConsistencyModel.SYNCHRONOUS, BOTH);
        cluster.crash(3);
        primary.write("k2", "b", ConsistencyModel.SYNCHRONOUS, BOTH);
        primary.write("k3", "c", ConsistencyModel.SYNCHRONOUS, BOTH);
        cluster.recover(3);

        Map<Integer, Map<String, DataItem>> replicas = new HashMap<>();
        replicas.put(1, primary.snapshot());
        replicas.put(3, reader.dump(port(3), 0, new LamportClock(), 200).items());
        assertThat(ConsistencyCheck.compare(1, replicas).count(DivergenceKind.MISSING)).isEqualTo(2);
        ReplicationStatsSnapshot before = primary.stats().get(3);

        AntiEntropyReport report = primary.antiEntropy(3);

        assertThat(report.completed()).isTrue();
        assertThat(report.merged()).isEqualTo(new AntiEntropyResult(3, 2, 1, 0, 0));
        assertThat(report.chunksPlanned()).isEqualTo(1);
        replicas.put(3, reader.dump(port(3), 0, new LamportClock(), 200).items());
        assertThat(ConsistencyCheck.compare(1, replicas).consistent()).isTrue();
        assertThat(primary.stats().get(3)).isEqualTo(before);
        assertThat(events(1, "ANTI_ENTROPY")).singleElement().satisfies(event -> {
            assertThat(event.peerId()).isEqualTo(3);
            assertThat(event.data()).containsEntry("applied", 2).containsEntry("pushed", 3);
        });
        assertThat(events(3, "ANTI_ENTROPY_MERGED")).singleElement()
                .satisfies(event -> assertThat(event.data()).containsEntry("applied", 2));
    }

    @Test
    @DisplayName("anti-entropy pushes batch-size items per SYNC message (batch 2, 5 items: 3 chunks)")
    void antiEntropyChunksAtBatchSize() {
        ReplicationProperties batchOfTwo = new ReplicationProperties(50, 2000, 2);
        ReplicationNodeService primary = primaryWithBackups(batchOfTwo);
        for (int i = 0; i < 5; i++) {
            primary.write("k" + i, "v" + i, ConsistencyModel.SYNCHRONOUS, List.of(2));
        }

        AntiEntropyReport report = primary.antiEntropy(3);

        assertThat(report.chunksPlanned()).isEqualTo(3);
        assertThat(report.chunksAcknowledged()).isEqualTo(3);
        assertThat(report.merged()).isEqualTo(new AntiEntropyResult(5, 5, 0, 0, 0));
        assertThat(service(3).snapshot()).isEqualTo(primary.snapshot());
        assertThat(events(3, "ANTI_ENTROPY_MERGED")).hasSize(3);
    }

    @Test
    @DisplayName("anti-entropy to a crashed target fails with a report and an event, even from an empty store")
    void antiEntropyToCrashedTargetFails() {
        ReplicationNodeService primary = primaryWithBackups(PROPERTIES);
        cluster.crash(3);

        AntiEntropyReport report = primary.antiEntropy(3);

        assertThat(report.completed()).isFalse();
        assertThat(report.chunksPlanned()).isEqualTo(1);
        assertThat(report.chunksAcknowledged()).isZero();
        assertThat(report.failure()).hasValueSatisfying(reason -> assertThat(reason).contains("ConnectException"));
        assertThat(events(1, "ANTI_ENTROPY_FAILED")).hasSize(1);
    }

    @Test
    @DisplayName("an out-of-order stale update is rejected as STALE; the backup keeps the newer value")
    void staleOutOfOrderRejected() throws IOException {
        ReplicationNodeService primary = primaryWithBackups(PROPERTIES);
        DataItem current = primary.write("k", "v2", ConsistencyModel.SYNCHRONOUS, BOTH).item();

        PushOutcome outcome = primary.deliverOutOfOrder(2, OutOfOrderInjector.staleVersionOf(current, "old"));

        assertThat(outcome.status()).isEqualTo(PushStatus.ACKED);
        assertThat(outcome.result()).contains(ApplyResult.STALE);
        assertThat(readOver(2, "k")).contains(current);
        assertThat(primary.stats().get(2).staleRejections()).isEqualTo(1);
        assertThat(events(2, "REPLICA_STALE")).hasSize(1);
        assertThat(events(1, "ACK")).filteredOn(event -> event.data().containsKey("outOfOrder")).hasSize(1);
    }

    // ------------------------------------------------------------------ roles and epochs

    @Test
    @DisplayName("a recovered old primary learns the higher epoch from its first STALE_EPOCH reply, is not confirmed, and stops acting as primary")
    void recoveredOldPrimaryLearnsEpochFromStaleEpoch() throws IOException {
        ReplicationNodeService oldPrimary = primaryWithBackups(PROPERTIES);
        oldPrimary.write("k", "a", ConsistencyModel.SYNCHRONOUS, BOTH);
        cluster.crash(1);
        ReplicationNodeService newPrimary = service(2);
        newPrimary.becomePrimary(2);
        newPrimary.write("k", "b", ConsistencyModel.SYNCHRONOUS, List.of(1, 3));
        cluster.recover(1);
        assertThat(oldPrimary.primaryEpoch()).hasValue(1);
        int backupEventsBefore = replicaEvents(2).size() + replicaEvents(3).size();

        assertThatThrownBy(() -> oldPrimary.write("k", "c", ConsistencyModel.SYNCHRONOUS, BOTH))
                .isInstanceOf(NotPrimaryException.class)
                .hasMessageContaining("NOT confirmed")
                .hasMessageContaining("already applied to this node's own store");

        assertThat(oldPrimary.isPrimary()).isFalse();
        assertThat(oldPrimary.epoch()).isEqualTo(2);
        assertThat(oldPrimary.get("k")).hasValueSatisfying(item -> assertThat(item.value()).isEqualTo("c"));
        assertThat(readOver(2, "k")).hasValueSatisfying(item -> assertThat(item.value()).isEqualTo("b"));
        assertThat(readOver(3, "k")).hasValueSatisfying(item -> assertThat(item.value()).isEqualTo("b"));
        assertThat(events(2, "REPLICA_STALE_EPOCH")).hasSize(1);
        assertThat(events(1, "PRIMARY_SUPERSEDED")).singleElement()
                .satisfies(event -> assertThat(event.data()).containsEntry("previousEpoch", 1L).containsEntry("newEpoch", 2L));
        assertThat(events(1, "WRITE_NOT_CONFIRMED")).hasSize(1);
        int backupEventsAfter = replicaEvents(2).size() + replicaEvents(3).size();

        assertThatThrownBy(() -> oldPrimary.write("k", "d", ConsistencyModel.SYNCHRONOUS, BOTH))
                .isInstanceOf(NotPrimaryException.class).hasMessageContaining("not acting as primary");
        assertThat(replicaEvents(2).size() + replicaEvents(3).size()).as("nothing sent").isEqualTo(backupEventsAfter);
        assertThat(backupEventsAfter).isEqualTo(backupEventsBefore + 2);
        assertThat(events(1, "PRIMARY_SUPERSEDED")).hasSize(1);
    }

    @Test
    @DisplayName("a push from a newer primary raises the receiver's epoch and supersedes it if it was primary")
    void pushFromNewerEpochSupersedesReceiver() {
        ReplicationNodeService one = service(1);
        ReplicationNodeService two = service(2);
        one.becomePrimary(1);
        two.becomePrimary(2);

        two.write("k", "v", ConsistencyModel.SYNCHRONOUS, List.of(1));

        assertThat(one.isPrimary()).isFalse();
        assertThat(one.epoch()).isEqualTo(2);
        assertThat(events(1, "PRIMARY_SUPERSEDED")).singleElement().satisfies(event -> {
            assertThat(event.peerId()).isEqualTo(2);
            assertThat(event.data()).containsEntry("learnedFrom", "node 2");
        });
        assertThat(two.isPrimary()).isTrue();
    }

    @Test
    @DisplayName("ASYNCHRONOUS on a superseded primary: confirmed at once, then the STALE_EPOCH reply supersedes it")
    void asyncPathAlsoSupersedes() throws Exception {
        ReplicationNodeService primary = service(1);
        ReplicationNodeService backup = service(2);
        primary.becomePrimary(1);
        backup.observeEpoch(3);

        WriteResult result = primary.write("k", "v", ConsistencyModel.ASYNCHRONOUS, List.of(2));

        assertThat(result.replication().get(10, TimeUnit.SECONDS)).singleElement()
                .satisfies(outcome -> assertThat(outcome.result()).contains(ApplyResult.STALE_EPOCH));
        assertThat(primary.isPrimary()).isFalse();
        assertThat(primary.epoch()).isEqualTo(3);
        assertThatThrownBy(() -> primary.write("k", "w", ConsistencyModel.ASYNCHRONOUS, List.of(2)))
                .isInstanceOf(NotPrimaryException.class);
    }

    @Test
    @DisplayName("observeEpoch above the primary epoch supersedes locally; it never lowers the epoch")
    void observeEpochSupersedesLocally() {
        ReplicationNodeService primary = service(1);
        primary.becomePrimary(1);

        assertThat(primary.observeEpoch(5)).isEqualTo(5);
        assertThat(primary.observeEpoch(2)).isEqualTo(5);

        assertThat(primary.isPrimary()).isFalse();
        assertThat(events(1, "PRIMARY_SUPERSEDED")).singleElement()
                .satisfies(event -> assertThat(event.data()).containsEntry("learnedFrom", "local"));
    }

    @Test
    @DisplayName("writes need the primary role; stepDown removes it; becomePrimary below a known epoch is refused and is idempotent")
    void roleGuards() {
        ReplicationNodeService node = service(1);
        service(2);

        assertThatThrownBy(() -> node.write("k", "v", ConsistencyModel.SYNCHRONOUS, List.of(2)))
                .isInstanceOf(NotPrimaryException.class);
        node.becomePrimary(1);
        node.stepDown();
        node.stepDown();
        assertThat(events(1, "PRIMARY_STEPPED_DOWN")).hasSize(1);
        assertThatThrownBy(() -> node.write("k", "v", ConsistencyModel.SYNCHRONOUS, List.of(2)))
                .isInstanceOf(NotPrimaryException.class);

        node.observeEpoch(3);
        assertThatThrownBy(() -> node.becomePrimary(2)).isInstanceOf(NotPrimaryException.class)
                .hasMessageContaining("already knows epoch 3");
        node.becomePrimary(3);
        node.becomePrimary(3);
        assertThat(node.primaryEpoch()).hasValue(3);
        assertThat(events(1, "PRIMARY_ACTIVE")).hasSize(2);   // epoch 1, then epoch 3
    }

    @Test
    @DisplayName("invalid writes change nothing: bad key, a node replicating to itself, duplicate or unknown backups")
    void invalidWritesChangeNothing() {
        ReplicationNodeService primary = primaryWithBackups(PROPERTIES);
        long clockBefore = cluster.node(1).clock().current();

        assertThatThrownBy(() -> primary.write(" ", "v", ConsistencyModel.SYNCHRONOUS, BOTH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> primary.write("k", "v", ConsistencyModel.SYNCHRONOUS, List.of(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> primary.write("k", "v", ConsistencyModel.SYNCHRONOUS, List.of(2, 2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> primary.write("k", "v", ConsistencyModel.SYNCHRONOUS, List.of(9)))
                .isInstanceOf(UnknownNodeException.class);

        assertThat(cluster.node(1).clock().current()).isEqualTo(clockBefore);
        assertThat(primary.snapshot()).isEmpty();
        assertThat(events(1, "WRITE")).isEmpty();
    }

    // ------------------------------------------------------------------ inbound limits and malformed input

    @Test
    @DisplayName("an item epoch above the sender's epoch gets an ERROR reply and nothing is stored")
    void itemEpochAboveSenderEpochIsError() throws IOException {
        ReplicationNodeService backup = service(2);

        List<String> reply = exchangeRaw(2, "REPLICATE|" + RAW_SENDER + "|1|1\n" + itemLine("k", 2) + "\nEND\n");

        assertThat(reply).singleElement().satisfies(line -> assertThat(line).startsWith("ERROR|2|")
                .contains("above the sender's epoch 1"));
        assertThat(backup.get("k")).isEmpty();
        assertThat(backup.epoch()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown request and an over-long line (beyond 8192 characters) get an ERROR reply; the service keeps serving")
    void malformedAndOverLongLinesGetError() throws IOException {
        service(2);

        assertThat(exchangeRaw(2, "HELLO\n")).singleElement()
                .satisfies(line -> assertThat(line).startsWith("ERROR|2|").contains("Unknown request type"));
        assertThat(exchangeRaw(2, "x".repeat(9000) + "\n")).singleElement()
                .satisfies(line -> assertThat(line).startsWith("ERROR|2|").contains("longer than 8192"));
        assertThat(readOver(2, "k")).isEmpty();
    }

    @Test
    @DisplayName("a SYNC with more than 1000 items, or whose item lines do not match its count, is an ERROR and stores nothing")
    void syncOverLimitOrMismatchedIsError() throws IOException {
        ReplicationNodeService backup = service(2);
        String a = itemLine("a", 1);
        String b = itemLine("b", 1);

        assertThat(exchangeRaw(2, "SYNC|9|1|1|1001\n" + a + "\nEND\n")).singleElement()
                .satisfies(line -> assertThat(line).startsWith("ERROR|").contains("At most 1000"));
        assertThat(exchangeRaw(2, "SYNC|9|1|1|2\n" + a + "\nEND\n")).singleElement()
                .satisfies(line -> assertThat(line).startsWith("ERROR|").contains("Expected 2 item lines, got 1"));
        assertThat(exchangeRaw(2, "SYNC|9|1|1|1\n" + a + "\n" + b + "\nEND\n")).singleElement()
                .satisfies(line -> assertThat(line).startsWith("ERROR|").contains("More than the 1 item lines"));

        assertThat(backup.snapshot()).isEmpty();
        assertThat(events(2, "ANTI_ENTROPY_MERGED")).isEmpty();
    }

    @Test
    @DisplayName("a client that connects and sends nothing is dropped by the server within SO_TIMEOUT")
    void silentClientIsDropped() throws IOException {
        service(2, new ReplicationProperties(50, 300, 200));

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ReplicationProtocol.LOOPBACK, port(2)), 2000);
            socket.setSoTimeout(10_000);   // far longer than the server's 300 ms
            assertThat(socket.getInputStream().read()).as("the server closed the connection").isEqualTo(-1);
        }
    }

    // ------------------------------------------------------------------ closing

    @Test
    @DisplayName("crash() waits for an in-flight handler; the handler then skips, replies nothing, and the store is unchanged")
    void crashWaitsForInFlightHandlerAndStoreUnchanged() throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ReplicationNodeService.Probe probe = request -> {
            if (request.type() == ReplicationProtocol.RequestType.REPLICATE) {
                reached.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        ReplicationNodeService backup = service(2, PROPERTIES, probe);
        DataItem item = new DataItem("k", "v", 1, RAW_SENDER, 1);
        ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CompletableFuture<AckReply> push = CompletableFuture.supplyAsync(() -> {
                try {
                    return new ReplicationClient(10_000).replicate(port(2), RAW_SENDER, new LamportClock(), 1, item);
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            }, threads);
            assertThat(reached.await(5, TimeUnit.SECONDS)).as("handler paused after reading the request").isTrue();

            CountDownLatch crashReturned = new CountDownLatch(1);
            threads.execute(() -> {
                cluster.crash(2);
                crashReturned.countDown();
            });
            assertThat(crashReturned.await(300, TimeUnit.MILLISECONDS))
                    .as("crash() must not return while the handler is in flight").isFalse();

            release.countDown();
            assertThat(crashReturned.await(10, TimeUnit.SECONDS)).as("crash() returns once the handler is done").isTrue();
            assertThatThrownBy(() -> push.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IOException.class);
        } finally {
            threads.shutdownNow();
        }

        assertThat(cluster.recover(2)).isTrue();
        assertThat(backup.get("k")).as("the store is unchanged by the in-flight request").isEmpty();
        assertThat(replicaEvents(2)).isEmpty();
    }

    @Test
    @DisplayName("no NEW replication platform thread (accept or async scheduler) survives crash() or stop(); recover starts a fresh one")
    void crashAndStopLeaveNoNewThreadAlive() {
        // Cached Spring contexts from other test classes may run a node 1 with same-named threads,
        // so only thread objects that were not alive before count.
        Set<Thread> before = liveReplicationThreads(1);
        ReplicationNodeService primary = primaryWithBackups(new ReplicationProperties(60_000, 2000, 200));
        primary.write("k", "v", ConsistencyModel.ASYNCHRONOUS, BOTH);   // starts the scheduler thread
        Set<Thread> started = newThreads(1, before);
        assertThat(started).extracting(Thread::getName)
                .containsExactlyInAnyOrder("udcf-replication-n1-accept", "udcf-replication-n1-async");

        cluster.crash(1);
        assertThat(newThreads(1, before)).as("alive after crash() returned").isEmpty();
        assertThat(started).noneMatch(Thread::isAlive);

        assertThat(cluster.recover(1)).isTrue();
        Set<Thread> recovered = newThreads(1, before);
        assertThat(recovered).extracting(Thread::getName).containsExactly("udcf-replication-n1-accept");
        assertThat(recovered).doesNotContainAnyElementsOf(started);

        primary.stop();
        assertThat(newThreads(1, before)).as("alive after stop() returned").isEmpty();
        assertThat(recovered).noneMatch(Thread::isAlive);
    }

    @Test
    @DisplayName("40 concurrent synchronous writers, released together, leave all three replicas identical")
    void concurrentWritesConverge() throws Exception {
        ReplicationNodeService primary = primaryWithBackups(PROPERTIES);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();
        List<CompletableFuture<WriteResult>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 40; i++) {
                String key = i % 2 == 0 ? "shared" : "k" + i;
                String value = "v" + i;
                results.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        throw new CompletionException(e);
                    }
                    return primary.write(key, value, ConsistencyModel.SYNCHRONOUS, BOTH);
                }, writers));
            }
            go.countDown();
            CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        } finally {
            writers.shutdownNow();
        }

        Map<String, DataItem> one = primary.snapshot();
        assertThat(one).hasSize(21);
        assertThat(reader.dump(port(2), 0, new LamportClock(), 200).items()).isEqualTo(one);
        assertThat(reader.dump(port(3), 0, new LamportClock(), 200).items()).isEqualTo(one);
        assertThat(results).allSatisfy(result -> assertThat(result.join().replication().join())
                .extracting(PushOutcome::status).containsOnly(PushStatus.ACKED));
    }

    // ------------------------------------------------------------------ E5c: catch-up and reset

    @Test
    @DisplayName("catchUpFrom pulls the source's store over TCP and merges it with the source's epoch (raised here, events published)")
    void catchUpFromMergesWithSourceEpoch() {
        ReplicationNodeService one = service(1);
        ReplicationNodeService two = service(2);
        two.becomePrimary(2);
        two.write("a", "1", ConsistencyModel.SYNCHRONOUS, List.of());
        two.write("b;~", "2", ConsistencyModel.SYNCHRONOUS, List.of());

        CatchUpReport report = one.catchUpFrom(2);

        assertThat(report.completed()).isTrue();
        assertThat(report.merged()).contains(new AntiEntropyResult(2, 2, 0, 0, 0));
        assertThat(report.sourceEpoch()).hasValue(2);
        assertThat(one.snapshot()).isEqualTo(two.snapshot());
        assertThat(one.epoch()).isEqualTo(2);
        assertThat(events(1, "CATCH_UP")).singleElement().satisfies(event -> {
            assertThat(event.peerId()).isEqualTo(2);
            assertThat(event.data()).containsEntry("pulled", 2).containsEntry("applied", 2)
                    .containsEntry("sourceEpoch", 2L).containsEntry("storeEpoch", 2L);
        });
        assertThat(one.catchUpFrom(2).merged()).contains(new AntiEntropyResult(2, 0, 2, 0, 0));
    }

    @Test
    @DisplayName("after a catch-up the node's Lamport clock is above every item it pulled (the DUMP reply time is merged)")
    void catchUpAdvancesClockPastEveryPulledItem() {
        ReplicationNodeService one = service(1);
        ReplicationNodeService two = service(2);
        two.becomePrimary(1);
        cluster.node(2).clock().update(500);
        for (int i = 0; i < 5; i++) {
            two.write("k" + i, "v", ConsistencyModel.SYNCHRONOUS, List.of());
        }
        long highestItem = two.snapshot().values().stream().mapToLong(DataItem::lamportTime).max().orElseThrow();
        assertThat(cluster.node(1).clock().current()).isLessThan(highestItem);

        one.catchUpFrom(2);

        assertThat(cluster.node(1).clock().current()).isGreaterThan(highestItem);
    }

    @Test
    @DisplayName("a catch-up from a crashed peer is a failed report and a CATCH_UP_FAILED event; nothing is merged")
    void catchUpFromUnreachablePeerIsReported() {
        ReplicationNodeService one = service(1);
        service(2);
        cluster.crash(2);

        CatchUpReport report = one.catchUpFrom(2);

        assertThat(report.completed()).isFalse();
        assertThat(report.merged()).isEmpty();
        assertThat(report.sourceEpoch()).isEmpty();
        assertThat(report.failure()).hasValueSatisfying(reason -> assertThat(reason).contains("ConnectException"));
        assertThat(events(1, "CATCH_UP_FAILED")).singleElement()
                .satisfies(event -> assertThat(event.peerId()).isEqualTo(2));
        assertThat(one.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("resetState clears store, stats, role and epoch, drops pending async pushes, and serves again; no new accept thread survives")
    void resetStateClearsAndRestarts() throws Exception {
        Set<Thread> before = liveReplicationThreads(1);
        ReplicationNodeService primary = primaryWithBackups(new ReplicationProperties(60_000, 2000, 200));
        primary.observeEpoch(3);
        primary.becomePrimary(3);
        primary.write("k", "v", ConsistencyModel.SYNCHRONOUS, BOTH);
        WriteResult pending = primary.write("p", "v", ConsistencyModel.ASYNCHRONOUS, BOTH);
        Set<Thread> started = newThreads(1, before);

        primary.resetState();

        assertThat(pending.replication().get(5, TimeUnit.SECONDS)).extracting(PushOutcome::status)
                .containsOnly(PushStatus.NOT_SENT);
        assertThat(primary.isRunning()).isTrue();
        assertThat(primary.snapshot()).isEmpty();
        assertThat(primary.stats()).isEmpty();
        assertThat(primary.isPrimary()).isFalse();
        assertThat(primary.epoch()).isEqualTo(DataStore.INITIAL_EPOCH);
        assertThat(started).noneMatch(Thread::isAlive);
        assertThat(newThreads(1, before)).extracting(Thread::getName).containsExactly("udcf-replication-n1-accept");
        assertThat(readOver(1, "k")).isEmpty();
    }

    @Test
    @DisplayName("resetState on a crashed node's service only clears it: it stays closed and nothing listens")
    void resetStateOnCrashedServiceOnlyClears() throws IOException {
        ReplicationNodeService primary = primaryWithBackups(PROPERTIES);
        primary.write("k", "v", ConsistencyModel.SYNCHRONOUS, BOTH);
        cluster.crash(1);

        primary.resetState();

        assertThat(primary.isRunning()).isFalse();
        assertThatThrownBy(() -> readOver(1, "k")).isInstanceOf(ConnectException.class);
        assertThat(primary.isPrimary()).isFalse();
        assertThat(primary.stats()).isEmpty();
        assertThat(primary.epoch()).isEqualTo(DataStore.INITIAL_EPOCH);
        assertThat(cluster.recover(1)).isTrue();
        assertThat(primary.snapshot()).isEmpty();
    }
}
