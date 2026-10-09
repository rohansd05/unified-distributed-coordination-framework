package com.udcf.modules.mapreduce;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeService;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Experiment 7 transport: a node's "mapreduce" service, a TCP listener on
 * 127.0.0.1:{@code ports().mapreduce()} (730k) executing MAP and REDUCE tasks.
 *
 * <p>Uses the existing pure algorithms ({@link MapTask}, {@link ReduceTask}) and
 * {@link JobRegistry}. Tasks run on a bounded worker thread pool.</p>
 */
public class MapReduceNodeService implements NodeService {

    public static final String NAME = "mapreduce";
    public static final String MODULE = "mapreduce";

    private static final Logger log = LoggerFactory.getLogger(MapReduceNodeService.class);
    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(5);

    private final ClusterNode node;
    private final JobRegistry registry;
    private final MapReduceProperties properties;
    private final ClusterEventBus bus;
    private final int minNodeId;
    private final int maxNodeId;

    private final Set<Socket> inboundConnections = ConcurrentHashMap.newKeySet();
    private final AtomicInteger workerThreadSeq = new AtomicInteger(0);

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile ThreadPoolExecutor workerPool;
    private volatile boolean running;

    public MapReduceNodeService(ClusterNode node, JobRegistry registry,
                                MapReduceProperties properties, ClusterEventBus bus) {
        this(node, registry, properties, bus, 1, 100);
    }

    public MapReduceNodeService(ClusterNode node, JobRegistry registry,
                                MapReduceProperties properties, ClusterEventBus bus,
                                int minNodeId, int maxNodeId) {
        this.node = Objects.requireNonNull(node, "node must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.minNodeId = minNodeId;
        this.maxNodeId = maxNodeId;
    }

    /**
     * Obtains the MapReduceNodeService for {@code node}, starting it lazily via
     * {@link ClusterNode#ensureService}.
     */
    public static MapReduceNodeService on(ClusterNode node, JobRegistry registry,
                                          MapReduceProperties properties, ClusterEventBus bus) {
        return node.ensureService(NAME, n -> new MapReduceNodeService(n, registry, properties, bus));
    }

    /**
     * Convenience factory using a {@link Cluster} for node id bounds and registration.
     */
    public static MapReduceNodeService on(ClusterNode node, Cluster cluster, JobRegistry registry,
                                          MapReduceProperties properties, ClusterEventBus bus) {
        Objects.requireNonNull(cluster, "cluster must not be null");
        int minId = cluster.nodes().stream().mapToInt(ClusterNode::id).min().orElse(1);
        int maxId = cluster.nodes().stream().mapToInt(ClusterNode::id).max().orElse(100);
        return node.ensureService(NAME, n -> new MapReduceNodeService(n, registry, properties, bus, minId, maxId));
    }

    /**
     * Finds the service if registered without starting it.
     */
    public static Optional<MapReduceNodeService> find(ClusterNode node) {
        return node.service(NAME).map(MapReduceNodeService.class::cast);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public synchronized void start() {
        open();
    }

    @Override
    public synchronized void crash() {
        close(true);
    }

    @Override
    public synchronized void recover() {
        open();
    }

    @Override
    public synchronized void stop() {
        close(false);
    }

    @Override
    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed()
                && acceptThread != null && acceptThread.isAlive();
    }

    public int nodeId() {
        return node.id();
    }

    public int port() {
        return node.ports().mapreduce();
    }

    ThreadPoolExecutor workerPool() {
        return workerPool;
    }

    Set<Socket> inboundConnections() {
        return inboundConnections;
    }

    // ------------------------------------------------------------------ Server Lifecycle

    private void open() {
        if (running) {
            return;
        }

        ServerSocket socket;
        try {
            socket = bind();
        } catch (IOException e) {
            long stamped = node.clock().tick();
            bus.publish(EventDraft.of(MODULE, node.id(), "SERVICE_START_FAILED", stamped)
                    .withMessage("Failed to bind mapreduce TCP socket on port " + port())
                    .withData(Map.of("port", port(), "error", e.getMessage() != null ? e.getMessage() : "unknown")));
            throw new IllegalStateException("Node " + node.id() + " failed to bind mapreduce port " + port(), e);
        }

        serverSocket = socket;
        workerThreadSeq.set(0);

        ThreadFactory workerFactory = r -> {
            Thread t = new Thread(r, "udcf-mapreduce-n" + node.id() + "-worker-" + workerThreadSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };

        workerPool = new ThreadPoolExecutor(
                properties.workerThreads(),
                properties.workerThreads(),
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()),
                workerFactory,
                (runnable, executor) -> {
                    throw new RejectedExecutionException("Worker queue is full");
                }
        );

        running = true;

        Thread accept = new Thread(() -> acceptLoop(socket), "udcf-mapreduce-n" + node.id() + "-accept");
        accept.setDaemon(true);
        acceptThread = accept;
        accept.start();
    }

    private ServerSocket bind() throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            if (!isWindows()) {
                socket.setReuseAddress(true);
            }
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port()), properties.listenBacklog());
            return socket;
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private void close(boolean crashed) {
        running = false;
        closeQuietly(serverSocket);
        inboundConnections.forEach(MapReduceNodeService::closeQuietly);
        inboundConnections.clear();

        ThreadPoolExecutor pool = workerPool;
        if (pool != null) {
            pool.shutdownNow();
        }

        IllegalStateException failure = null;
        failure = collect(failure, this::awaitAcceptThreadExit);
        if (pool != null) {
            failure = collect(failure, () -> awaitTermination(pool, "worker thread pool"));
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void awaitAcceptThreadExit() {
        Thread accept = acceptThread;
        if (accept == null) {
            return;
        }
        try {
            if (!accept.join(EXIT_TIMEOUT)) {
                throw new IllegalStateException("Node " + node.id() + ": accept thread did not exit within "
                        + EXIT_TIMEOUT.toMillis() + " ms; port " + port() + " may still be listening");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Node " + node.id() + ": interrupted waiting for accept thread", e);
        }
        acceptThread = null;
    }

    private void awaitTermination(ThreadPoolExecutor pool, String what) {
        try {
            if (!pool.awaitTermination(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Node " + node.id() + ": " + what + " did not terminate within "
                        + EXIT_TIMEOUT.toMillis() + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Node " + node.id() + ": interrupted waiting for " + what, e);
        }
    }

    private static IllegalStateException collect(IllegalStateException first, Runnable r) {
        try {
            r.run();
            return first;
        } catch (IllegalStateException e) {
            if (first == null) {
                return e;
            }
            first.addSuppressed(e);
            return first;
        }
    }

    // ------------------------------------------------------------------ Accept & Serve

    private void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                return; // closed on stop() or crash()
            }

            inboundConnections.add(client);
            if (!running || socket.isClosed()) {
                closeQuietly(client);
                inboundConnections.remove(client);
                return;
            }

            ThreadPoolExecutor pool = workerPool;
            if (pool == null || pool.isShutdown()) {
                closeQuietly(client);
                inboundConnections.remove(client);
                return;
            }

            try {
                pool.execute(() -> {
                    try {
                        serve(client);
                    } finally {
                        inboundConnections.remove(client);
                    }
                });
            } catch (RejectedExecutionException e) {
                // Queue full: reply ERROR "Worker is busy" and close connection
                handleQueueFull(client);
                inboundConnections.remove(client);
            }
        }
    }

    private void handleQueueFull(Socket client) {
        try (client) {
            long stamped = node.clock().tick();
            String reply = MapReduceProtocol.encodeErrorReply(node.id(), stamped, null, "Worker is busy") + "\n";
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            out.write(reply.getBytes(StandardCharsets.UTF_8));
            out.flush();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reason", "Worker is busy");
            data.put("workerId", node.id());
            data.put("taskId", null);
            data.put("taskType", null);
            data.put("coordinatorId", null);
            data.put("lamportTime", stamped);

            bus.publish(EventDraft.of(MODULE, node.id(), "REQUEST_REFUSED", stamped)
                    .withMessage("Request refused by node " + node.id() + ": Worker is busy")
                    .withData(data));
        } catch (IOException ignored) {
        }
    }

    private void serve(Socket client) {
        try (client) {
            client.setSoTimeout((int) properties.socketReadTimeoutMillis());
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = new BufferedOutputStream(client.getOutputStream());

            String line;
            try {
                line = MapReduceProtocol.readLineBounded(in, properties.maxRequestBytes());
            } catch (ProtocolException e) {
                log.warn("Node {}: dropped oversize mapreduce request (exceeded {} bytes)", node.id(), properties.maxRequestBytes());
                refuseRequest(out, null, null, null, "Line exceeds maxRequestBytes limit", false);
                return;
            }

            if (line == null) {
                return; // clean EOF
            }

            MapReduceProtocol.Request request;
            try {
                request = MapReduceProtocol.decodeRequest(line, minNodeId, maxNodeId);
            } catch (ProtocolException e) {
                log.warn("Node {}: dropped malformed mapreduce request: {}", node.id(), e.getMessage());
                refuseRequest(out, null, null, null, e.getMessage(), false);
                return;
            }

            if (!registry.contains(request.jobName())) {
                log.warn("Node {}: unknown job: {}", node.id(), request.jobName());
                refuseRequest(out, request.taskId(), request.taskType(), request.senderId(),
                        "Unknown job: " + request.jobName(), false);
                return;
            }

            // Valid request: advance clock
            node.clock().update(request.lamportTime());

            String taskTypeName = request.taskType() == TaskType.MAP ? "Map" : "Reduce";
            Map<String, Object> receivedData = new LinkedHashMap<>();
            receivedData.put("taskId", request.taskId());
            receivedData.put("taskType", request.taskType().name());
            receivedData.put("jobName", request.jobName());
            receivedData.put("coordinatorId", request.senderId());
            receivedData.put("workerId", node.id());
            receivedData.put("lamportTime", node.clock().current());

            bus.publish(EventDraft.of(MODULE, node.id(), "TASK_RECEIVED", node.clock().current())
                    .withPeer(request.senderId())
                    .withMessage(taskTypeName + " task " + request.taskId() + " received from node " + request.senderId())
                    .withData(receivedData));

            MapReduceJob job = registry.get(request.jobName());
            String result;
            try {
                if (request.taskType() == TaskType.MAP) {
                    result = MapTask.execute(job, request.payload());
                } else {
                    result = ReduceTask.execute(job, request.payload());
                }
            } catch (Exception e) {
                log.warn("Node {}: execution failed for {} task {}: {}",
                        node.id(), request.taskType(), request.taskId(), e.getMessage());
                sendTaskFailed(out, request, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                return;
            }

            long replyLamport = node.clock().tick();
            String okReply = MapReduceProtocol.encodeOkReply(node.id(), replyLamport, request.taskId(), result) + "\n";
            out.write(okReply.getBytes(StandardCharsets.UTF_8));
            out.flush();

            Map<String, Object> completedData = new LinkedHashMap<>();
            completedData.put("taskId", request.taskId());
            completedData.put("taskType", request.taskType().name());
            completedData.put("jobName", request.jobName());
            completedData.put("coordinatorId", request.senderId());
            completedData.put("workerId", node.id());
            completedData.put("lamportTime", replyLamport);

            bus.publish(EventDraft.of(MODULE, node.id(), "TASK_COMPLETED", replyLamport)
                    .withPeer(request.senderId())
                    .withMessage(taskTypeName + " task " + request.taskId() + " completed on node " + node.id())
                    .withData(completedData));

        } catch (IOException e) {
            if (running) {
                log.debug("Node {}: socket error while serving connection: {}", node.id(), e.getMessage());
            }
        }
    }

    private void refuseRequest(OutputStream out, String taskId, TaskType taskType,
                               Integer coordinatorId, String reason, boolean clockUpdated) throws IOException {
        long stamped = node.clock().tick();
        String reply = MapReduceProtocol.encodeErrorReply(node.id(), stamped, taskId, reason) + "\n";
        out.write(reply.getBytes(StandardCharsets.UTF_8));
        out.flush();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason);
        data.put("workerId", node.id());
        data.put("taskId", taskId);
        data.put("taskType", taskType != null ? taskType.name() : null);
        data.put("coordinatorId", coordinatorId);
        data.put("lamportTime", stamped);

        bus.publish(EventDraft.of(MODULE, node.id(), "REQUEST_REFUSED", stamped)
                .withMessage("Request refused by node " + node.id() + ": " + reason)
                .withData(data));
    }

    private void sendTaskFailed(OutputStream out, MapReduceProtocol.Request request, String error) throws IOException {
        long replyLamport = node.clock().tick();
        String reply = MapReduceProtocol.encodeErrorReply(node.id(), replyLamport, request.taskId(), error) + "\n";
        out.write(reply.getBytes(StandardCharsets.UTF_8));
        out.flush();

        String taskTypeName = request.taskType() == TaskType.MAP ? "Map" : "Reduce";
        Map<String, Object> failedData = new LinkedHashMap<>();
        failedData.put("taskId", request.taskId());
        failedData.put("taskType", request.taskType().name());
        failedData.put("jobName", request.jobName());
        failedData.put("coordinatorId", request.senderId());
        failedData.put("workerId", node.id());
        failedData.put("lamportTime", replyLamport);
        failedData.put("error", error);

        bus.publish(EventDraft.of(MODULE, node.id(), "TASK_FAILED", replyLamport)
                .withPeer(request.senderId())
                .withMessage(taskTypeName + " task " + request.taskId() + " failed on node " + node.id())
                .withData(failedData));
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}
