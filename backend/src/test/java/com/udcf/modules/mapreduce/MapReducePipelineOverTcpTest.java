package com.udcf.modules.mapreduce;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.core.events.ClusterEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Real 127.0.0.1 socket integration tests for MapReducePipeline over TcpTaskTransport
 * on ports 24351-24355.
 */
class MapReducePipelineOverTcpTest {

    // Test ports 24351-24355 inside Track D block 24100-24899
    private static final ClusterProperties CLUSTER_CONFIG = new ClusterProperties(
            5,
            List.of(FAST, MEDIUM, SLOW, MEDIUM, FAST),
            new ClusterProperties.Ports(24100, 24200, 24300, 24400, 24500, 24350)
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

    private void ensureAllServicesRunning() {
        for (int i = 1; i <= 5; i++) {
            MapReduceNodeService.on(cluster.node(i), cluster, registry, PROPERTIES, bus);
        }
    }

    private int port(int nodeId) {
        return cluster.node(nodeId).ports().mapreduce();
    }

    @Test
    @DisplayName("full pipeline for word-count over TCP matches in-memory result")
    void fullPipelineWordCountOverTcpMatchesInMemory() throws Exception {
        ensureAllServicesRunning();
        WordCountJob job = new WordCountJob();
        List<String> input = List.of(
                "hello world",
                "hello mapreduce",
                "testing distributed word count pipeline",
                "world of distributed systems",
                "hello pipeline"
        );

        JobReport tcpReport = new JobReport(job.name());
        List<Integer> workerIds = List.of(1, 2, 3, 4, 5);
        TcpTaskTransport transport = new TcpTaskTransport(cluster, cluster.node(1), bus, PROPERTIES);
        MapReducePipeline pipeline = new MapReducePipeline(workerIds, transport);

        Map<String, String> tcpResult;
        try {
            tcpResult = pipeline.run(job, input, tcpReport);
        } finally {
            pipeline.shutdown();
        }

        JobReport inMemReport = new JobReport(job.name());
        Map<String, String> inMemResult = MapReducePipeline.runLocal(job, input, 5, inMemReport);

        assertThat(tcpResult).isEqualTo(inMemResult);
        assertThat(tcpReport.inputLines()).isEqualTo(5);
        assertThat(tcpReport.splits()).isEqualTo(5);
        assertThat(tcpReport.failedTasksRetried()).isEqualTo(0);
    }

    @Test
    @DisplayName("full pipeline for event-category-count over TCP matches in-memory result")
    void fullPipelineEventCategoryCountOverTcpMatchesInMemory() throws Exception {
        ensureAllServicesRunning();
        EventCategoryJob job = new EventCategoryJob();
        List<String> input = List.of(
                "2026-10-09T10:00:00Z INFO node-1 Service started",
                "2026-10-09T10:00:01Z WARN node-2 Buffer high",
                "2026-10-09T10:00:02Z INFO node-1 Task complete",
                "2026-10-09T10:00:03Z ERROR node-3 Socket refused",
                "2026-10-09T10:00:04Z INFO node-2 Task complete"
        );

        JobReport tcpReport = new JobReport(job.name());
        List<Integer> workerIds = List.of(1, 2, 3, 4, 5);
        TcpTaskTransport transport = new TcpTaskTransport(cluster, cluster.node(1), bus, PROPERTIES);
        MapReducePipeline pipeline = new MapReducePipeline(workerIds, transport);

        Map<String, String> tcpResult;
        try {
            tcpResult = pipeline.run(job, input, tcpReport);
        } finally {
            pipeline.shutdown();
        }

        JobReport inMemReport = new JobReport(job.name());
        Map<String, String> inMemResult = MapReducePipeline.runLocal(job, input, 5, inMemReport);

        assertThat(tcpResult).isEqualTo(inMemResult);
        assertThat(tcpReport.splits()).isEqualTo(5);
    }

    @Test
    @DisplayName("full pipeline for avg-latency-per-node over TCP matches in-memory result")
    void fullPipelineLatencyPerNodeOverTcpMatchesInMemory() throws Exception {
        ensureAllServicesRunning();
        LatencyPerNodeJob job = new LatencyPerNodeJob();
        List<String> input = List.of(
                "2026-10-09T10:00:00Z INFO node-1 latency=50ms completed",
                "2026-10-09T10:00:01Z INFO node-2 latency=20ms completed",
                "2026-10-09T10:00:02Z INFO node-1 latency=150ms completed",
                "2026-10-09T10:00:03Z INFO node-3 latency=80ms completed"
        );

        JobReport tcpReport = new JobReport(job.name());
        List<Integer> workerIds = List.of(1, 2, 3, 4, 5);
        TcpTaskTransport transport = new TcpTaskTransport(cluster, cluster.node(1), bus, PROPERTIES);
        MapReducePipeline pipeline = new MapReducePipeline(workerIds, transport);

        Map<String, String> tcpResult;
        try {
            tcpResult = pipeline.run(job, input, tcpReport);
        } finally {
            pipeline.shutdown();
        }

        JobReport inMemReport = new JobReport(job.name());
        Map<String, String> inMemResult = MapReducePipeline.runLocal(job, input, 5, inMemReport);

        assertThat(tcpResult).isEqualTo(inMemResult);
    }

    @Test
    @DisplayName("pipeline retries when worker is crashed before run and produces identical result")
    void workerCrashedBeforeRunRetriedOnAnotherWorker() throws Exception {
        ensureAllServicesRunning();

        // Worker 2 is crashed before the run
        cluster.node(2).crash();

        WordCountJob job = new WordCountJob();
        List<String> input = List.of("apple orange", "banana apple", "orange pear");

        JobReport tcpReport = new JobReport(job.name());
        List<Integer> workerIds = List.of(1, 2, 3, 4, 5);
        TcpTaskTransport transport = new TcpTaskTransport(cluster, cluster.node(1), bus, PROPERTIES);
        MapReducePipeline pipeline = new MapReducePipeline(workerIds, transport);

        Map<String, String> tcpResult;
        try {
            tcpResult = pipeline.run(job, input, tcpReport);
        } finally {
            pipeline.shutdown();
        }

        JobReport inMemReport = new JobReport(job.name());
        Map<String, String> inMemResult = MapReducePipeline.runLocal(job, input, 5, inMemReport);

        assertThat(tcpResult).isEqualTo(inMemResult);
        assertThat(tcpReport.failedTasksRetried()).isGreaterThan(0);
    }

    @Test
    @DisplayName("pipeline retries when worker is crashed during run and finishes successfully")
    void workerCrashedDuringRunRetriedOnAnotherWorker() throws Exception {
        ensureAllServicesRunning();

        WordCountJob job = new WordCountJob();
        List<String> input = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            input.add("line " + i + " with repetitive tokens apple banana");
        }

        // Crash node 3 deterministically when coordinator sends a task to it
        bus.subscribe(event -> {
            if ("mapreduce".equals(event.module()) && "TASK_SENT".equals(event.type())) {
                Number target = (Number) event.data().get("workerId");
                if (target != null && target.intValue() == 3) {
                    cluster.node(3).crash();
                }
            }
        });

        JobReport tcpReport = new JobReport(job.name());
        List<Integer> workerIds = List.of(1, 2, 3, 4, 5);
        TcpTaskTransport transport = new TcpTaskTransport(cluster, cluster.node(1), bus, PROPERTIES);
        MapReducePipeline pipeline = new MapReducePipeline(workerIds, transport);

        Map<String, String> tcpResult;
        try {
            tcpResult = pipeline.run(job, input, tcpReport);
        } finally {
            pipeline.shutdown();
        }

        JobReport inMemReport = new JobReport(job.name());
        Map<String, String> inMemResult = MapReducePipeline.runLocal(job, input, 5, inMemReport);

        assertThat(tcpResult).isEqualTo(inMemResult);
    }

    @Test
    @DisplayName("slow worker exceeding task timeout is cancelled and retried on another worker without blocking threads")
    void workerSlowerThanTimeoutRetriedOnAnotherWorker() throws Exception {
        // Start services on nodes 1, 3, 4, 5.
        // Node 2 will host a plain test ServerSocket that accepts and never replies (Rule 10).
        MapReduceNodeService.on(cluster.node(1), cluster, registry, PROPERTIES, bus);
        MapReduceNodeService.on(cluster.node(3), cluster, registry, PROPERTIES, bus);
        MapReduceNodeService.on(cluster.node(4), cluster, registry, PROPERTIES, bus);
        MapReduceNodeService.on(cluster.node(5), cluster, registry, PROPERTIES, bus);

        CountDownLatch latch = new CountDownLatch(1);
        int slowPort = port(2);

        try (ServerSocket slowWorker = new ServerSocket()) {
            slowWorker.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), slowPort));

            Thread slowAcceptThread = new Thread(() -> {
                try {
                    Socket socket = slowWorker.accept();
                    latch.await(5, TimeUnit.SECONDS);
                    socket.close();
                } catch (Exception ignored) {
                }
            });
            slowAcceptThread.setDaemon(true);
            slowAcceptThread.start();

            // Pipeline configured with a small task timeout of 300 ms
            Duration smallTimeout = Duration.ofMillis(300);
            MapReduceProperties smallProps = new MapReduceProperties(300, 300, 4194304, 50, 4, 32);
            TcpTaskTransport transport = new TcpTaskTransport(cluster, cluster.node(1), bus, smallProps);
            List<Integer> workerIds = List.of(2, 3, 4, 5); // node 2 is preferred for split 0
            MapReducePipeline pipeline = new MapReducePipeline(workerIds, transport, smallTimeout);

            WordCountJob job = new WordCountJob();
            JobReport report = new JobReport(job.name());
            List<String> input = List.of("word count line");

            Map<String, String> result;
            try {
                result = pipeline.run(job, input, report);
            } finally {
                pipeline.shutdown();
                latch.countDown();
                slowAcceptThread.join(2000);
            }

            assertThat(result).containsEntry("word", "1").containsEntry("count", "1").containsEntry("line", "1");
            assertThat(report.failedTasksRetried()).isGreaterThanOrEqualTo(1);

            // Verify no transport threads remain blocked (CHANGE 1)
            await().atMost(Duration.ofSeconds(3)).until(() -> {
                Set<Thread> threads = Thread.getAllStackTraces().keySet();
                return threads.stream().noneMatch(t -> t.getName().matches("udcf-mapreduce-\\d+")
                        && t.isAlive());
            });
        }
    }

    @Test
    @DisplayName("all workers down throws IOException when retries are exhausted")
    void allWorkersDownThrowsIOException() {
        // Do not start any services; all ports refused
        List<Integer> workerIds = List.of(1, 2, 3);
        TcpTaskTransport transport = new TcpTaskTransport(cluster, cluster.node(1), bus, PROPERTIES);
        MapReducePipeline pipeline = new MapReducePipeline(workerIds, transport);

        WordCountJob job = new WordCountJob();
        JobReport report = new JobReport(job.name());
        List<String> input = List.of("hello world");

        try {
            assertThatThrownBy(() -> pipeline.run(job, input, report))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("All workers exhausted");
        } finally {
            pipeline.shutdown();
        }
    }

    @Test
    @DisplayName("coordinator refuses request exceeding maxRequestBytes without attempting connection")
    void oversizePayloadRefusedByCoordinator() {
        MapReduceProperties tinyProps = new MapReduceProperties(15000, 15000, 128, 50, 4, 32);
        TcpTaskTransport transport = new TcpTaskTransport(cluster, cluster.node(1), bus, tinyProps);

        assertThatThrownBy(() -> transport.executeTask(2, TaskType.MAP, "word-count", "a".repeat(500)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds maxRequestBytes limit");
    }

    @Test
    @DisplayName("TcpTaskTransport times out and throws IOException on socketReadTimeoutMillis when worker never replies")
    void tcpTaskTransportTimesOutOnSocketReadTimeout() throws Exception {
        // Fast socketReadTimeoutMillis (100ms) with large taskTimeoutMillis (10s)
        MapReduceProperties timeoutProps = new MapReduceProperties(10000, 100, 4194304, 50, 4, 32);
        CountDownLatch releaseServer = new CountDownLatch(1);

        int port = cluster.node(5).ports().mapreduce();
        try (ServerSocket ss = new ServerSocket()) {
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));

            Thread silentWorker = new Thread(() -> {
                try {
                    Socket s = ss.accept();
                    try {
                        releaseServer.await(5, TimeUnit.SECONDS);
                    } finally {
                        s.close();
                    }
                } catch (Exception ignored) {
                }
            });
            silentWorker.setDaemon(true);
            silentWorker.start();

            try {
                TcpTaskTransport transport = new TcpTaskTransport(cluster, cluster.node(1), bus, timeoutProps);
                assertThatThrownBy(() -> transport.executeTask(5, TaskType.MAP, "word-count", "hello world"))
                        .isInstanceOf(IOException.class)
                        .hasMessageMatching("(?i).*timed out.*");

                await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                    List<ClusterEvent> events = bus.query(MapReduceNodeService.MODULE, 1, 10);
                    assertThat(events).anyMatch(e -> "TASK_ATTEMPT_FAILED".equals(e.type())
                            && e.message().toLowerCase(Locale.ROOT).contains("timed out"));
                });
            } finally {
                releaseServer.countDown();
                silentWorker.join(2000);
            }
        }
    }
}
