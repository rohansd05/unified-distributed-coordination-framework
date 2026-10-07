package com.udcf.modules.multithreading;

import com.udcf.core.clock.LamportClock;
import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.NodeCapacity;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.core.metrics.MetricNames;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The requests service on real 127.0.0.1 sockets, on a standalone three-node cluster
 * (FAST, MEDIUM, SLOW). No Spring context, so crashes here never disturb other tests.
 *
 * <p>Test-only port bases 41100 to 41600, so node k's requests port is 4150k: below the
 * Windows dynamic range (49152 and up), apart from every other test class, and away from
 * the production ports, so the suite runs while a local backend or legacy demo is up.</p>
 */
class RequestsNodeServiceTest {

    private static final ClusterProperties CLUSTER = new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
            new ClusterProperties.Ports(41100, 41200, 41300, 41400, 41500, 41600));
    private static final MultithreadingProperties PROPERTIES =
            new MultithreadingProperties(200, 60, "udcf-worker-", 30, 500, 2000,
                    new MultithreadingProperties.Backpressure(50, WorkloadType.CPU_HASH, 200));
    private static final int SENDER = 7;

    private ClusterEventBus bus;
    private Cluster cluster;
    private SimpleMeterRegistry meters;
    private RequestsClient client;
    private ExecutorService clients;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), java.time.Clock.systemUTC());
        cluster = new Cluster(CLUSTER, bus);
        meters = new SimpleMeterRegistry();
        client = new RequestsClient(5000);
        clients = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        clients.shutdownNow();
        cluster.close();
        bus.close();
    }

    private RequestsNodeService service(int nodeId) {
        return RequestsNodeService.on(cluster.node(nodeId), PROPERTIES, meters, bus);
    }

    private WorkReply send(int nodeId, WorkloadType type, int payloadSize) throws IOException {
        return client.send(cluster.node(nodeId).ports().requests(), SENDER, new LamportClock(), type, payloadSize);
    }

    private CompletableFuture<WorkReply> sendAsync(int nodeId, WorkloadType type, int payloadSize) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return send(nodeId, type, payloadSize);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, clients);
    }

    private List<ClusterEvent> requestEvents(int nodeId) {
        return bus.query(RequestsNodeService.MODULE, nodeId, 5000).stream()
                .filter(event -> event.type().startsWith("REQUEST_"))
                .toList();
    }

    /** Sends raw bytes and returns the reply line, then whether the server closed the connection. */
    private List<String> exchangeRaw(int port, String raw) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(RequestsProtocol.LOOPBACK, port), 2000);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(raw.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String reply = RequestsProtocol.readLine(socket.getInputStream());
            String after = RequestsProtocol.readLine(socket.getInputStream());
            return java.util.Arrays.asList(reply, after == null ? "closed" : "open: " + after);
        }
    }

    @Test
    @DisplayName("nothing listens until first use; then the service starts lazily and serves work")
    void startsLazilyAndServes() throws IOException {
        assertThat(cluster.node(1).service(RequestsNodeService.NAME)).isEmpty();
        assertThatThrownBy(() -> send(1, WorkloadType.CPU_HASH, 5)).isInstanceOf(ConnectException.class);

        RequestsNodeService service = service(1);
        WorkReply reply = send(1, WorkloadType.CPU_HASH, 5);

        assertThat(service.port()).isEqualTo(41501);
        assertThat(reply.status()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(reply.nodeId()).isEqualTo(1);
        assertThat(reply.detail()).startsWith("hash=");
        assertThat(cluster.node(1).runningServices()).containsExactly(RequestsNodeService.NAME);
        assertThat(service(1)).isSameAs(service);
        assertThat(service.registry().find(reply.requestId())).isPresent();
    }

    @Test
    @DisplayName("work runs on that node's own executor, sized by its capacity")
    void runsOnTheNodesExecutor() throws Exception {
        service(1);
        RequestsNodeService slow = service(3);

        assertThat(send(1, WorkloadType.CPU_HASH, 5).threadName()).startsWith("udcf-worker-n1-");
        List<CompletableFuture<WorkReply>> onSlow = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            onSlow.add(sendAsync(3, WorkloadType.CPU_HASH, 20));
        }
        for (CompletableFuture<WorkReply> reply : onSlow) {
            assertThat(reply.get(10, TimeUnit.SECONDS).threadName()).isEqualTo("udcf-worker-n3-1");
        }
        assertThat(service(1).stats().snapshot().maxPoolSize()).isEqualTo(NodeCapacity.FAST.threads());
        assertThat(slow.stats().snapshot().maxPoolSize()).isEqualTo(NodeCapacity.SLOW.threads());
    }

    @Test
    @DisplayName("Lamport time crosses the socket both ways, and the reply is published as one event")
    void carriesLamportTimeAndPublishesOneEvent() throws IOException {
        service(1);
        ClusterNode node = cluster.node(1);
        node.clock().update(100);                       // receiver at 101
        LamportClock sender = new LamportClock();

        WorkReply reply = client.send(node.ports().requests(), SENDER, sender, WorkloadType.CPU_HASH, 5);

        assertThat(reply.lamportTime()).isEqualTo(103); // receive max(101, 1) + 1 = 102, reply tick 103
        assertThat(node.clock().current()).isEqualTo(103);
        assertThat(sender.current()).isEqualTo(104);    // max(1, 103) + 1
        await().atMost(5, TimeUnit.SECONDS).until(() -> requestEvents(1).size() == 1);
        ClusterEvent event = requestEvents(1).get(0);
        assertThat(event.type()).isEqualTo("REQUEST_COMPLETED");
        assertThat(event.module()).isEqualTo("multithreading");
        assertThat(event.nodeId()).isEqualTo(1);
        assertThat(event.peerId()).isEqualTo(SENDER);
        assertThat(event.lamportTime()).isEqualTo(103);
        assertThat(event.data())
                .containsEntry("requestId", reply.requestId())
                .containsEntry("receiveLamport", 102L)
                .containsEntry("workload", "CPU_HASH")
                .containsEntry("payloadSize", 5)
                .containsEntry("threadName", reply.threadName())
                .containsKey("totalMillis");
    }

    @Test
    @DisplayName("concurrent clients on the FAST node are served by several worker threads")
    void concurrentClientsUseSeveralThreads() throws Exception {
        service(1);
        List<CompletableFuture<WorkReply>> replies = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            replies.add(sendAsync(1, WorkloadType.MIXED, 200));
        }

        Set<String> threads = new HashSet<>();
        for (CompletableFuture<WorkReply> reply : replies) {
            WorkReply done = reply.get(20, TimeUnit.SECONDS);
            assertThat(done.status()).isEqualTo(RequestStatus.COMPLETED);
            threads.add(done.threadName());
        }
        assertThat(threads).hasSizeGreaterThan(1).allMatch(name -> name.startsWith("udcf-worker-n1-"));
    }

    @Test
    @DisplayName("a full queue rejects work with a REJECTED reply and a REQUEST_REJECTED event")
    void fullQueueRejects() throws Exception {
        MultithreadingProperties tinyQueue = new MultithreadingProperties(1, 60, "udcf-worker-", 30, 500, 2000,
                new MultithreadingProperties.Backpressure(50, WorkloadType.CPU_HASH, 200));
        RequestsNodeService.on(cluster.node(3), tinyQueue, meters, bus);   // SLOW: 1 thread, queue 1
        List<CompletableFuture<WorkReply>> replies = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            replies.add(sendAsync(3, WorkloadType.IO_SIMULATED, 500));   // x4 on SLOW: 800 ms each
        }

        List<RequestStatus> statuses = new ArrayList<>();
        for (CompletableFuture<WorkReply> reply : replies) {
            statuses.add(reply.get(20, TimeUnit.SECONDS).status());
        }

        assertThat(statuses).contains(RequestStatus.REJECTED, RequestStatus.COMPLETED);
        await().atMost(5, TimeUnit.SECONDS).until(() -> requestEvents(3).size() == 6);
        assertThat(requestEvents(3)).filteredOn(event -> event.type().equals("REQUEST_REJECTED")).isNotEmpty()
                .allSatisfy(event -> assertThat(event.data()).doesNotContainKey("threadName"));
    }

    @Test
    @DisplayName("a crash refuses new connections, cuts in-flight ones, and ends running and queued work FAILED")
    void crashTakesTheServiceDown() throws Exception {
        RequestsNodeService service = service(1);
        List<CompletableFuture<WorkReply>> inFlight = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            inFlight.add(sendAsync(1, WorkloadType.IO_SIMULATED, 5000));   // 2 s each; FAST has 4 threads
        }
        await().atMost(5, TimeUnit.SECONDS).until(() ->
                service.registry().countByStatus().get(RequestStatus.PROCESSING) == 4L
                        && service.registry().countByStatus().get(RequestStatus.QUEUED) == 2L);

        assertThat(cluster.crash(1)).isTrue();

        for (CompletableFuture<WorkReply> reply : inFlight) {
            assertThatThrownBy(() -> reply.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(IOException.class).isNotInstanceOf(SocketTimeoutException.class);
        }
        assertThatThrownBy(() -> send(1, WorkloadType.CPU_HASH, 5)).isInstanceOf(ConnectException.class);
        assertThat(service.isRunning()).isFalse();
        assertThatThrownBy(service::processing).isInstanceOf(NodeDownException.class);
        assertThatThrownBy(() -> service(1)).isInstanceOf(NodeDownException.class);
        assertThat(service.registry().recent(0))
                .allMatch(request -> request.getStatus() == RequestStatus.FAILED)
                .extracting(DistributedRequest::getErrorMessage)
                .containsExactlyInAnyOrder("Interrupted during processing", "Interrupted during processing",
                        "Interrupted during processing", "Interrupted during processing",
                        "Node crashed", "Node crashed");
        assertThat(requestEvents(1)).as("a crashed node sends no replies, so publishes no events").isEmpty();
    }

    @Test
    @DisplayName("recover rebinds the port on a fresh executor and keeps the request history")
    void recoverServesAgain() throws IOException {
        RequestsNodeService service = service(1);
        WorkReply before = send(1, WorkloadType.CPU_HASH, 5);
        RequestProcessingService firstEngine = service.processing();

        cluster.crash(1);
        assertThat(cluster.recover(1)).isTrue();
        WorkReply after = send(1, WorkloadType.CPU_HASH, 5);

        assertThat(service.isRunning()).isTrue();
        assertThat(after.status()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(service.processing()).isNotSameAs(firstEngine);
        assertThat(service.registry().find(before.requestId())).isPresent();
        assertThat(service.registry().find(after.requestId())).isPresent();
    }

    @Test
    @DisplayName("a crash while many requests complete ends each one once, with one event at most and no hang")
    void crashWhileManyComplete() throws Exception {
        RequestsNodeService service = service(1);
        List<CompletableFuture<WorkReply>> replies = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            replies.add(sendAsync(1, WorkloadType.CPU_HASH, 50));
        }
        await().atMost(10, TimeUnit.SECONDS)
                .until(() -> service.registry().countByStatus().get(RequestStatus.COMPLETED) >= 5);

        cluster.crash(1);

        int answered = 0;
        for (CompletableFuture<WorkReply> reply : replies) {
            try {
                assertThat(reply.get(10, TimeUnit.SECONDS).status()).isEqualTo(RequestStatus.COMPLETED);
                answered++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(IOException.class);
            }
        }
        assertThat(answered).isPositive();
        assertThat(service.registry().recent(0)).hasSize(40).allMatch(DistributedRequest::isEnded);
        List<Object> ids = requestEvents(1).stream().map(event -> event.data().get("requestId")).toList();
        assertThat(ids).doesNotHaveDuplicates().hasSizeLessThanOrEqualTo(40);
        double accepted = count("accepted");
        assertThat(accepted).isEqualTo(40.0);
        assertThat(count("completed") + count("failed")).isEqualTo(accepted);
    }

    @Test
    @DisplayName("a malformed line gets an ERROR reply and the service keeps serving")
    void malformedLineIsRefused() throws IOException {
        service(1);

        List<String> exchange = exchangeRaw(41501, "HELLO\n");

        assertThat(exchange.get(0)).startsWith("ERROR|1|");
        assertThat(send(1, WorkloadType.CPU_HASH, 5).status()).isEqualTo(RequestStatus.COMPLETED);
    }

    @Test
    @DisplayName("a line over 1024 characters gets an ERROR reply and the connection is closed")
    void overLongLineIsRefusedAndClosed() throws IOException {
        service(1);
        String tooLong = "WORK|0|1|CPU_HASH;5" + "x".repeat(RequestsProtocol.MAX_LINE_LENGTH);

        List<String> exchange = exchangeRaw(41501, tooLong + "\n");

        assertThat(exchange.get(0)).startsWith("ERROR|1|").contains("1024");
        assertThat(exchange.get(1)).isEqualTo("closed");
        assertThat(send(1, WorkloadType.CPU_HASH, 5).status()).isEqualTo(RequestStatus.COMPLETED);
    }

    @Test
    @DisplayName("a port already in use fails the start cleanly; once it is free, the next use starts")
    void portInUseFailsCleanly() throws IOException {
        try (ServerSocket squatter = new ServerSocket()) {
            squatter.bind(new InetSocketAddress(RequestsProtocol.LOOPBACK, 41502));

            assertThatThrownBy(() -> service(2)).isInstanceOf(IllegalStateException.class);
            assertThat(cluster.node(2).runningServices()).isEmpty();
            assertThat(bus.query("cluster", 2, 100)).extracting(ClusterEvent::type).contains("SERVICE_START_FAILED");
        }

        RequestsNodeService service = service(2);
        assertThat(service.isRunning()).isTrue();
        assertThat(send(2, WorkloadType.CPU_HASH, 5).threadName()).startsWith("udcf-worker-n2-");
    }

    @Test
    @DisplayName("stop closes the port and is safe to call twice")
    void stopClosesThePort() throws IOException {
        RequestsNodeService service = service(1);
        send(1, WorkloadType.CPU_HASH, 5);

        service.stop();
        service.stop();

        assertThat(service.isRunning()).isFalse();
        assertThatThrownBy(() -> send(1, WorkloadType.CPU_HASH, 5)).isInstanceOf(ConnectException.class);
    }

    @Test
    @DisplayName("request counters carry each node's own node_id, and no gauge is bound yet")
    void countersPerNodeAndNoGauges() throws IOException {
        service(1);
        service(3);
        send(1, WorkloadType.CPU_HASH, 5);
        send(3, WorkloadType.CPU_HASH, 5);

        assertThat(meters.get(MetricNames.REQUESTS_TOTAL).tag(MetricNames.NODE_ID, "1").tag("outcome", "completed")
                .counter().count()).isEqualTo(1.0);
        assertThat(meters.get(MetricNames.REQUESTS_TOTAL).tag(MetricNames.NODE_ID, "3").tag("outcome", "completed")
                .counter().count()).isEqualTo(1.0);
        assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTag(MetricNames.NODE_ID))
                .isIn("1", "3"));
        assertThat(meters.getMeters()).extracting(meter -> meter.getId().getType())
                .doesNotContain(Meter.Type.GAUGE);
    }

    @Test
    @DisplayName("execute runs a task on the node's executor, outside the request history")
    void executeRunsOnTheNodesExecutor() throws Exception {
        RequestsNodeService service = service(1);

        String thread = service.execute(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS);

        assertThat(thread).startsWith("udcf-worker-n1-");
        assertThat(service.registry().size()).isZero();
        assertThatThrownBy(() -> service.execute(() -> {
            throw new IllegalStateException("boom");
        }).get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(IllegalStateException.class).hasMessage("boom");
    }

    @Test
    @DisplayName("execute on a crashed node throws NodeDownException at once")
    void executeOnCrashedNodeThrows() {
        RequestsNodeService service = service(1);
        cluster.crash(1);

        assertThatThrownBy(() -> service.execute(() -> 42)).isInstanceOf(NodeDownException.class);
    }

    @Test
    @DisplayName("a crash mid-task fails running and queued tasks with NodeDownException, even one that ignores interrupts")
    void crashMidTaskFailsTheFuture() throws Exception {
        RequestsNodeService slow = service(3);                    // one worker thread
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean release = new AtomicBoolean();
        CompletableFuture<String> running = slow.execute(() -> {
            started.countDown();
            while (!release.get()) {
                Thread.onSpinWait();                               // deliberately ignores interrupts
            }
            return "finished";
        });
        CompletableFuture<String> queued = slow.execute(() -> "never runs");
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            cluster.crash(3);

            for (CompletableFuture<String> future : List.of(running, queued)) {
                assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .cause().isInstanceOf(NodeDownException.class);
            }
        } finally {
            release.set(true);
        }
    }

    @Test
    @DisplayName("find never starts a service; snapshot is empty unless running; clearHistory keeps the service up")
    void findSnapshotAndClearHistory() throws IOException {
        assertThat(RequestsNodeService.find(cluster.node(1))).isEmpty();
        assertThat(cluster.node(1).runningServices()).isEmpty();

        RequestsNodeService service = service(1);
        send(1, WorkloadType.CPU_HASH, 5);

        assertThat(RequestsNodeService.find(cluster.node(1))).containsSame(service);
        assertThat(service.snapshot()).get().extracting(stats -> stats.maxPoolSize()).isEqualTo(4);
        service.clearHistory();
        assertThat(service.registry().size()).isZero();
        assertThat(service.snapshot()).get().extracting(stats -> stats.sampleCount()).isEqualTo(0);
        assertThat(service.isRunning()).isTrue();

        cluster.crash(1);
        assertThat(service.snapshot()).isEmpty();
        service.clearHistory();   // safe while crashed
    }

    private double count(String outcome) {
        var counter = meters.find(MetricNames.REQUESTS_TOTAL).tag(MetricNames.NODE_ID, "1").tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
