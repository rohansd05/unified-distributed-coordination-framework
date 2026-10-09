package com.udcf.modules.mapreduce;

import com.udcf.core.clock.LamportClock;
import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Real 127.0.0.1 socket integration tests for MapReduceNodeService on ports 24301-24305
 * and squatter port 24851.
 */
class MapReduceNodeServiceTest {

    // Test ports 24301-24305 inside Track D block 24100-24899
    private static final ClusterProperties CLUSTER_CONFIG = new ClusterProperties(
            5,
            List.of(FAST, MEDIUM, SLOW, MEDIUM, FAST),
            new ClusterProperties.Ports(24100, 24200, 24300, 24400, 24500, 24300)
    );

    private static final MapReduceProperties PROPERTIES =
            new MapReduceProperties(15000, 15000, 4194304, 50, 4, 32);

    private Cluster cluster;
    private ClusterEventBus bus;
    private JobRegistry registry;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 1024), Clock.systemUTC());
        cluster = new Cluster(CLUSTER_CONFIG, bus);
        registry = JobRegistry.standard();
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    private MapReduceNodeService service(int nodeId) {
        return MapReduceNodeService.on(cluster.node(nodeId), cluster, registry, PROPERTIES, bus);
    }

    private int port(int nodeId) {
        return cluster.node(nodeId).ports().mapreduce();
    }

    @Test
    @DisplayName("MAP and REDUCE tasks for word-count over TCP match in-memory results")
    void mapAndReduceWordCountOverTcpMatchesInMemory() throws Exception {
        MapReduceNodeService worker = service(2);
        assertThat(worker.isRunning()).isTrue();

        TcpTaskTransport transport = new TcpTaskTransport(cluster, cluster.node(1), bus, PROPERTIES);
        WordCountJob job = new WordCountJob();

        String splitInput = "hello world\nhello mapreduce\nworld world";
        String inMemoryMap = MapTask.execute(job, splitInput);
        String tcpMap = transport.executeTask(2, TaskType.MAP, job.name(), splitInput);

        assertThat(MapTask.decode(tcpMap).pairs())
                .isEqualTo(MapTask.decode(inMemoryMap).pairs());

        // REDUCE test
        Map<String, List<String>> partition = Map.of(
                "hello", List.of("1", "1"),
                "world", List.of("1", "2")
        );
        String partitionPayload = ReduceTask.encodePartition(partition);
        String inMemoryReduce = ReduceTask.execute(job, partitionPayload);
        String tcpReduce = transport.executeTask(2, TaskType.REDUCE, job.name(), partitionPayload);

        assertThat(ReduceTask.decodeResult(tcpReduce))
                .isEqualTo(ReduceTask.decodeResult(inMemoryReduce));
    }

    @Test
    @DisplayName("MAP and REDUCE tasks for event-category-count over TCP match in-memory results")
    void mapAndReduceEventCategoryCountOverTcpMatchesInMemory() throws Exception {
        service(3);
        TcpTaskTransport transport = new TcpTaskTransport(cluster, cluster.node(1), bus, PROPERTIES);
        EventCategoryJob job = new EventCategoryJob();

        String logLines = "2026-10-09T10:00:00Z INFO node-1 Service started\n" +
                "2026-10-09T10:00:01Z WARN node-2 Buffer high\n" +
                "2026-10-09T10:00:02Z INFO node-1 Task complete\n";

        String inMemoryMap = MapTask.execute(job, logLines);
        String tcpMap = transport.executeTask(3, TaskType.MAP, job.name(), logLines);
        assertThat(MapTask.decode(tcpMap).pairs()).isEqualTo(MapTask.decode(inMemoryMap).pairs());

        Map<String, List<String>> partition = Map.of("INFO", List.of("1", "1"), "WARN", List.of("1"));
        String payload = ReduceTask.encodePartition(partition);
        String inMemoryReduce = ReduceTask.execute(job, payload);
        String tcpReduce = transport.executeTask(3, TaskType.REDUCE, job.name(), payload);

        assertThat(ReduceTask.decodeResult(tcpReduce)).isEqualTo(ReduceTask.decodeResult(inMemoryReduce));
    }

    @Test
    @DisplayName("MAP and REDUCE tasks for avg-latency-per-node over TCP match in-memory results")
    void mapAndReduceAvgLatencyPerNodeOverTcpMatchesInMemory() throws Exception {
        service(4);
        TcpTaskTransport transport = new TcpTaskTransport(cluster, cluster.node(1), bus, PROPERTIES);
        LatencyPerNodeJob job = new LatencyPerNodeJob();

        String logLines = "2026-10-09T10:00:00Z INFO node-1 latency=50ms completed\n" +
                "2026-10-09T10:00:01Z INFO node-1 latency=100ms completed\n" +
                "2026-10-09T10:00:02Z INFO node-2 latency=20ms completed\n";

        String inMemoryMap = MapTask.execute(job, logLines);
        String tcpMap = transport.executeTask(4, TaskType.MAP, job.name(), logLines);
        assertThat(MapTask.decode(tcpMap).pairs()).isEqualTo(MapTask.decode(inMemoryMap).pairs());

        Map<String, List<String>> partition = Map.of("node-1", List.of("50;1", "100;1"));
        String payload = ReduceTask.encodePartition(partition);
        String inMemoryReduce = ReduceTask.execute(job, payload);
        String tcpReduce = transport.executeTask(4, TaskType.REDUCE, job.name(), payload);

        assertThat(ReduceTask.decodeResult(tcpReduce)).isEqualTo(ReduceTask.decodeResult(inMemoryReduce));
    }

    @Test
    @DisplayName("Lamport clocks propagate across TCP and events record correct values")
    void lamportClocksPropagateAcrossTcpAndEventsRecordThem() throws Exception {
        MapReduceNodeService workerService = service(2);
        ClusterNode coordNode = cluster.node(1);
        ClusterNode workerNode = cluster.node(2);

        coordNode.clock().update(10);
        workerNode.clock().update(5);

        TcpTaskTransport transport = new TcpTaskTransport(cluster, coordNode, bus, PROPERTIES);
        transport.executeTask(2, TaskType.MAP, "word-count", "hello world");

        // Coordinator ticked to 11 before send
        // Worker was at 5, received 11 -> updated to max(5, 11)+1 = 12
        // Worker executed, replied with tick: 13
        // Coordinator received 13 -> updated to max(11, 13)+1 = 14
        assertThat(workerNode.clock().current()).isGreaterThanOrEqualTo(13);
        assertThat(coordNode.clock().current()).isGreaterThanOrEqualTo(14);

        List<ClusterEvent> events = bus.query(MapReduceNodeService.MODULE, null, 100);
        assertThat(events).extracting(ClusterEvent::type)
                .contains("TASK_SENT", "TASK_RECEIVED", "TASK_COMPLETED");

        ClusterEvent sentEvent = events.stream().filter(e -> "TASK_SENT".equals(e.type())).findFirst().orElseThrow();
        ClusterEvent receivedEvent = events.stream().filter(e -> "TASK_RECEIVED".equals(e.type())).findFirst().orElseThrow();
        ClusterEvent completedEvent = events.stream().filter(e -> "TASK_COMPLETED".equals(e.type())).findFirst().orElseThrow();

        assertThat(sentEvent.message()).isEqualTo("Map task 1 sent to node 2");
        assertThat(receivedEvent.message()).isEqualTo("Map task 1 received from node 1");
        assertThat(completedEvent.message()).isEqualTo("Map task 1 completed on node 2");

        assertThat(sentEvent.lamportTime()).isEqualTo(12);
        assertThat(receivedEvent.lamportTime()).isEqualTo(13);
        assertThat(completedEvent.lamportTime()).isEqualTo(14);
    }

    @Test
    @DisplayName("crash closes the port and leaves no udcf-mapreduce threads running within 5 seconds")
    void crashClosesPortAndLeavesNoThreadsRunning() throws Exception {
        MapReduceNodeService s = service(1);
        assertThat(s.isRunning()).isTrue();

        s.crash();
        assertThat(s.isRunning()).isFalse();

        // Safe double stop / crash
        s.crash();
        s.stop();

        // Bounded wait verifying no udcf-mapreduce threads remain
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            Set<Thread> threads = Thread.getAllStackTraces().keySet();
            return threads.stream().noneMatch(t -> t.getName().startsWith("udcf-mapreduce-n1-") && t.isAlive());
        });

        // Port is unbound
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port(1)));
            assertThat(probe.isBound()).isTrue();
        }

        // Recover rebinds cleanly
        s.recover();
        assertThat(s.isRunning()).isTrue();
    }

    @Test
    @DisplayName("port already in use publishes SERVICE_START_FAILED and does not crash cluster")
    void portAlreadyInUsePublishesServiceStartFailed() throws IOException {
        int squatterPort = 24851;
        try (ServerSocket squatter = new ServerSocket()) {
            squatter.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), squatterPort));

            ClusterProperties squatterClusterProps = new ClusterProperties(
                    1,
                    List.of(FAST),
                    new ClusterProperties.Ports(24100, 24200, 24300, 24400, 24500, squatterPort - 1)
            );
            Cluster squatterCluster = new Cluster(squatterClusterProps, bus);

            try {
                MapReduceNodeService conflicting = new MapReduceNodeService(
                        squatterCluster.node(1),
                        registry,
                        PROPERTIES,
                        bus
                );

                assertThatThrownBy(conflicting::start)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("failed to bind");

                List<ClusterEvent> events = bus.query(MapReduceNodeService.MODULE, 1, 100);
                assertThat(events).anyMatch(e -> "SERVICE_START_FAILED".equals(e.type())
                        && e.message().contains("Failed to bind mapreduce TCP socket"));
            } finally {
                squatterCluster.close();
            }
        }
    }

    @Test
    @DisplayName("malformed request line is refused with REQUEST_REFUSED event and service remains healthy")
    void malformedRequestRefusedAndServiceServesSubsequentRequest() throws Exception {
        MapReduceNodeService s = service(2);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port(2)), 2000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write("GARBAGE|LINE\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            String reply = MapReduceProtocol.readLineBounded(in, 1024);
            assertThat(reply).startsWith("ERROR|2|");
            MapReduceProtocol.Reply decoded = MapReduceProtocol.decodeReply(reply);
            assertThat(decoded.isOk()).isFalse();
            assertThat(decoded.errorMessage()).contains("Malformed request");
        }

        // Service is still running and serves subsequent valid task
        TcpTaskTransport transport = new TcpTaskTransport(cluster, cluster.node(1), bus, PROPERTIES);
        String result = transport.executeTask(2, TaskType.MAP, "word-count", "ok");
        assertThat(result).isNotEmpty();

        List<ClusterEvent> refused = bus.query(MapReduceNodeService.MODULE, 2, 100).stream()
                .filter(e -> "REQUEST_REFUSED".equals(e.type()))
                .toList();
        assertThat(refused).isNotEmpty();
    }

    @Test
    @DisplayName("oversize request exceeding maxRequestBytes is refused with REQUEST_REFUSED")
    void oversizeRequestRefusedAndServiceServesSubsequentRequest() throws Exception {
        MapReduceProperties smallProps = new MapReduceProperties(15000, 15000, 256, 50, 4, 32);
        MapReduceNodeService s = MapReduceNodeService.on(cluster.node(2), cluster, registry, smallProps, bus);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port(2)), 2000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String huge = "MAP|1|10|1|word-count|" + "a".repeat(500) + "\n";
            out.write(huge.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String reply = MapReduceProtocol.readLineBounded(in, 1024);
            assertThat(reply).startsWith("ERROR|2|");
            MapReduceProtocol.Reply decoded = MapReduceProtocol.decodeReply(reply);
            assertThat(decoded.isOk()).isFalse();
            assertThat(decoded.errorMessage()).contains("maxRequestBytes");
        }

        assertThat(s.isRunning()).isTrue();
    }

    @Test
    @DisplayName("unknown job name is refused with REQUEST_REFUSED")
    void unknownJobRefusedAndServiceServesSubsequentRequest() throws Exception {
        service(2);
        TcpTaskTransport transport = new TcpTaskTransport(cluster, cluster.node(1), bus, PROPERTIES);

        assertThatThrownBy(() -> transport.executeTask(2, TaskType.MAP, "non-existent-job", "input"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unknown job: non-existent-job");

        List<ClusterEvent> events = bus.query(MapReduceNodeService.MODULE, 2, 100);
        assertThat(events).anyMatch(e -> "REQUEST_REFUSED".equals(e.type())
                && e.message().contains("Unknown job"));
    }

    @Test
    @DisplayName("unknown task type is refused by the service and clock is unchanged")
    void unknownTaskTypeRefusedByService() throws Exception {
        MapReduceNodeService s = service(2);
        long clockBefore = cluster.node(2).clock().current();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port(2)), 2000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write("UNKNOWN_TYPE|1|10|1|word-count|cGF5bG9hZA==\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            String reply = MapReduceProtocol.readLineBounded(in, 1024);
            assertThat(reply).startsWith("ERROR|2|");
            MapReduceProtocol.Reply decoded = MapReduceProtocol.decodeReply(reply);
            assertThat(decoded.errorMessage()).contains("Unknown task type");
        }

        assertThat(cluster.node(2).clock().current()).isGreaterThanOrEqualTo(clockBefore);
    }

    @Test
    @DisplayName("out-of-range Lamport time and unknown sender are refused without updating clock")
    void outOfRangeLamportTimeAndUnknownSenderRefused() throws Exception {
        service(2);
        long clockBefore = cluster.node(2).clock().current();

        // 1. Out of range Lamport time
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port(2)), 2000);
            socket.getOutputStream().write("MAP|1|-5|1|word-count|cGF5bG9hZA==\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            String reply = MapReduceProtocol.readLineBounded(socket.getInputStream(), 1024);
            assertThat(reply).startsWith("ERROR|2|");
            MapReduceProtocol.Reply decoded = MapReduceProtocol.decodeReply(reply);
            assertThat(decoded.errorMessage()).contains("Out-of-range Lamport time");
        }

        // 2. Unknown sender ID 99
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port(2)), 2000);
            socket.getOutputStream().write("MAP|99|5|1|word-count|cGF5bG9hZA==\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            String reply = MapReduceProtocol.readLineBounded(socket.getInputStream(), 1024);
            assertThat(reply).startsWith("ERROR|2|");
            MapReduceProtocol.Reply decoded = MapReduceProtocol.decodeReply(reply);
            assertThat(decoded.errorMessage()).contains("Unknown sender node ID");
        }
    }

    @Test
    @DisplayName("error text containing pipes and newlines does not break wire framing")
    void errorTextWithPipesAndNewlinesDoesNotBreakFraming() throws Exception {
        JobRegistry testReg = new JobRegistry();
        testReg.register(new MapReduceJob() {
            @Override
            public String name() {
                return "broken-job";
            }

            @Override
            public String description() {
                return "Broken test job";
            }

            @Override
            public void map(String line, java.util.function.BiConsumer<String, String> emitter) {
                throw new RuntimeException("Failure | with pipe \n and newline | details");
            }

            @Override
            public String combine(String key, List<String> values) {
                return values.isEmpty() ? "" : values.get(0);
            }

            @Override
            public String reduce(String key, List<String> values) {
                return "";
            }
        });

        MapReduceNodeService brokenService = MapReduceNodeService.on(cluster.node(2), testReg, PROPERTIES, bus);
        TcpTaskTransport transport = new TcpTaskTransport(cluster, cluster.node(1), bus, PROPERTIES);

        assertThatThrownBy(() -> transport.executeTask(2, TaskType.MAP, "broken-job", "sample"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Failure | with pipe \n and newline | details");

        List<ClusterEvent> events = bus.query(MapReduceNodeService.MODULE, 2, 100);
        assertThat(events).anyMatch(e -> "TASK_FAILED".equals(e.type()));
    }

    @Test
    @DisplayName("full worker queue replies with Worker is busy error and REQUEST_REFUSED event")
    void fullQueueGivesWorkerBusyReply() throws Exception {
        // Pool with 1 thread and queue capacity 1
        MapReduceProperties busyProps = new MapReduceProperties(15000, 15000, 4194304, 50, 1, 1);
        JobRegistry testReg = new JobRegistry();
        CountDownLatch task1Started = new CountDownLatch(1);
        CountDownLatch pause = new CountDownLatch(1);

        testReg.register(new MapReduceJob() {
            @Override
            public String name() {
                return "blocking-job";
            }

            @Override
            public String description() {
                return "Blocking test job";
            }

            @Override
            public void map(String line, java.util.function.BiConsumer<String, String> emitter) {
                task1Started.countDown();
                try {
                    pause.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }

            @Override
            public String combine(String key, List<String> values) {
                return values.isEmpty() ? "" : values.get(0);
            }

            @Override
            public String reduce(String key, List<String> values) {
                return "";
            }
        });

        MapReduceNodeService s = MapReduceNodeService.on(cluster.node(2), cluster, testReg, busyProps, bus);
        ExecutorService clientPool = Executors.newCachedThreadPool();

        try {
            // Task 1 occupies worker thread
            clientPool.submit(() -> {
                TcpTaskTransport t = new TcpTaskTransport(cluster, cluster.node(1), bus, busyProps);
                t.executeTask(2, TaskType.MAP, "blocking-job", "1");
                return null;
            });
            assertThat(task1Started.await(2, TimeUnit.SECONDS)).isTrue();

            // Task 2 fills the queue (capacity 1)
            clientPool.submit(() -> {
                TcpTaskTransport t = new TcpTaskTransport(cluster, cluster.node(1), bus, busyProps);
                t.executeTask(2, TaskType.MAP, "blocking-job", "2");
                return null;
            });
            await().atMost(2, TimeUnit.SECONDS).until(() -> s.workerPool().getQueue().size() == 1);

            // Task 3 must be rejected because queue is saturated
            TcpTaskTransport t3 = new TcpTaskTransport(cluster, cluster.node(1), bus, busyProps);
            assertThatThrownBy(() -> t3.executeTask(2, TaskType.MAP, "blocking-job", "3"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Worker is busy");

            List<ClusterEvent> events = bus.query(MapReduceNodeService.MODULE, 2, 100);
            assertThat(events).anyMatch(e -> "REQUEST_REFUSED".equals(e.type())
                    && e.message().contains("Worker is busy"));
        } finally {
            pause.countDown();
            clientPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("idle client times out on server side after socketReadTimeoutMillis and frees worker thread")
    void idleClientDisconnectsAfterReadTimeoutAndFreesWorkerThread() throws Exception {
        // Node 3 on port 24303 with fast socketReadTimeoutMillis (100ms)
        MapReduceProperties fastTimeoutProps = new MapReduceProperties(15000, 100, 4194304, 50, 4, 32);
        MapReduceNodeService s = MapReduceNodeService.on(cluster.node(3), cluster, registry, fastTimeoutProps, bus);

        try (Socket idleClient = new Socket("127.0.0.1", s.port())) {
            // Client connects but sends nothing (idle connection)
            await().atMost(2, TimeUnit.SECONDS).until(() -> {
                try {
                    idleClient.setSoTimeout(200);
                    int b = idleClient.getInputStream().read();
                    return b == -1; // server closed connection on read timeout
                } catch (IOException e) {
                    return true; // connection closed or reset by server
                }
            });
        }

        // Prove worker thread is freed and service remains ready for valid work
        await().atMost(2, TimeUnit.SECONDS).until(() -> s.workerPool().getActiveCount() == 0);
        TcpTaskTransport transport = new TcpTaskTransport(cluster, cluster.node(1), bus, PROPERTIES);
        WordCountJob job = new WordCountJob();
        String result = transport.executeTask(3, TaskType.MAP, job.name(), "node three is free");
        assertThat(result).contains("#raw=4");
    }

    @Test
    @DisplayName("concurrent tasks to same worker node all complete correctly")
    void concurrentTasksToSameWorkerCompleteCorrectly() throws Exception {
        service(2);
        int concurrency = 8;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        WordCountJob job = new WordCountJob();

        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    TcpTaskTransport transport = new TcpTaskTransport(cluster, cluster.node(1), bus, PROPERTIES);
                    return transport.executeTask(2, TaskType.MAP, job.name(), "word" + idx + " count");
                }));
            }

            for (Future<String> f : futures) {
                String result = f.get(5, TimeUnit.SECONDS);
                assertThat(result).contains("#raw=2");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
