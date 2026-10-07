package com.udcf.modules.multithreading;

import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.cluster.NodeService;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.modules.multithreading.dto.RequestResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Experiment 2 transport: a node's "requests" service, a TCP listener on
 * 127.0.0.1:{@code ports().requests()} (720k) that runs work on that node's Exp 2 executor
 * (R2: TCP for Experiments 5 to 8; Exp 6 dispatches here, link L3).
 *
 * <p><b>Protocol.</b> One request line and one reply line per connection; see
 * {@link RequestsProtocol}. Each connection is served on its own virtual thread, so reading,
 * waiting and writing never occupy an Exp 2 worker thread: the pool only runs real work,
 * and its queue depth stays honest.</p>
 *
 * <p><b>Lamport time (link L4).</b> On receipt the node's clock takes
 * {@code update(senderTime)} (rule 3); the reply carries {@code tick()} (rule 2).</p>
 *
 * <p><b>Events.</b> One per TCP request, when its reply is sent: module
 * {@value #MODULE}, this node's id, peer = the sender, type {@code REQUEST_COMPLETED},
 * {@code REQUEST_FAILED} or {@code REQUEST_REJECTED}, at the reply's Lamport time, with data
 * {@code requestId, receiveLamport, workload, payloadSize, threadName} (absent when no worker
 * ran it) {@code , totalMillis}. A node that crashes mid-request sends no reply and so
 * publishes no event. In-process submissions through {@link #processing()} publish nothing.</p>
 *
 * <p><b>Lifecycle.</b> {@link #start()} and {@link #recover()} build a fresh executor with
 * {@link NodeExecutorFactory#forNode} (one cannot be restarted after shutdown), and with it a
 * fresh processing service, stats service and metrics; then bind the port. {@link #crash()}
 * closes the listener and every open connection, fails every pending {@link #execute}
 * future, and shuts the executor down: running work is interrupted and ends FAILED, queued
 * work ends FAILED "Node crashed". The {@link RequestRegistry} and {@link ThroughputTracker}
 * survive crash and recovery, so the history shows what happened to interrupted requests.</p>
 *
 * <p><b>Binding.</b> The listener sets {@code SO_REUSEADDR}. Checked on Windows 11 with
 * JDK 21: a second listener on a port in use is still refused, and an immediate rebind
 * after a crash succeeds. On Linux, {@code SO_REUSEADDR} never allows binding over a
 * listening socket, but does allow rebinding while the crashed connections sit in
 * TIME_WAIT. A bind failure on start or recover is reported, never retried.</p>
 *
 * <p><b>Metrics.</b> Request counters and the duration timer go to the given
 * {@link MeterRegistry}, tagged with this node's id (R5). Gauges are not bound here (see the
 * track file's known issues; they move to E2c).</p>
 */
public class RequestsNodeService implements NodeService {

    public static final String NAME = "requests";
    public static final String MODULE = "multithreading";

    private static final Logger log = LoggerFactory.getLogger(RequestsNodeService.class);

    /** Most input read and dropped after an over-long request line. */
    private static final long MAX_DISCARD_BYTES = 64 * 1024;

    private final ClusterNode node;
    private final MultithreadingProperties properties;
    private final MeterRegistry meterRegistry;
    private final ClusterEventBus bus;
    private final RequestRegistry registry;
    private final ThroughputTracker throughputTracker;
    private final RequestEventPublisher requestEvents = new LoggingRequestEventPublisher();
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();

    private volatile Engine engine;       // the current executor generation; null before the first start
    private volatile ServerSocket server;
    private volatile boolean running;

    /** One executor generation and everything that holds it. */
    private record Engine(ThreadPoolExecutor executor,
                          RequestProcessingService processing,
                          ThreadPoolStatsService stats,
                          Set<CompletableFuture<?>> tasks) {
    }

    public RequestsNodeService(ClusterNode node, MultithreadingProperties properties,
                               MeterRegistry meterRegistry, ClusterEventBus bus) {
        this.node = Objects.requireNonNull(node, "node must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.registry = new RequestRegistry(properties.requestHistorySize());
        this.throughputTracker = new ThroughputTracker(properties.metricsWindowSeconds());
    }

    /**
     * The node's requests service, created and started on first use through
     * {@link ClusterNode#ensureService} (so a crashed node throws {@link NodeDownException}).
     */
    public static RequestsNodeService on(ClusterNode node, MultithreadingProperties properties,
                                         MeterRegistry meterRegistry, ClusterEventBus bus) {
        return node.ensureService(NAME, n -> new RequestsNodeService(n, properties, meterRegistry, bus));
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
        close("Node crashed");
    }

    @Override
    public synchronized void recover() {
        open();
    }

    @Override
    public synchronized void stop() {
        close("Node stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public int port() {
        return node.ports().requests();
    }

    public int nodeId() {
        return node.id();
    }

    /** In-process submissions for this node (E2c's batches). @throws NodeDownException if not running */
    public RequestProcessingService processing() {
        return requireRunning().processing();
    }

    /** Live executor snapshot. @throws NodeDownException if not running */
    public ThreadPoolStatsService stats() {
        return requireRunning().stats();
    }

    /** This node's request history; kept across crash and recovery. */
    public RequestRegistry registry() {
        return registry;
    }

    /**
     * Runs {@code task} on this node's current Exp 2 executor, outside the request history.
     *
     * <p>The future completes with the task's result or exception; exceptionally with
     * {@link RejectedExecutionException} if the queue is full; and exceptionally with
     * {@link NodeDownException} if the node crashes before or during the task, so it never
     * hangs. A crash also interrupts the task; one that ignores interrupts runs on, but its
     * result is discarded.</p>
     *
     * @throws NodeDownException if the node is down when called
     */
    public <T> CompletableFuture<T> execute(Callable<T> task) {
        Objects.requireNonNull(task, "task must not be null");
        Engine current = requireRunning();
        CompletableFuture<T> future = new CompletableFuture<>();
        current.tasks().add(future);
        future.whenComplete((value, error) -> current.tasks().remove(future));
        try {
            current.executor().execute(() -> {
                if (future.isDone()) {
                    return;
                }
                try {
                    future.complete(task.call());
                } catch (Throwable t) {
                    if (t instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(current.executor().isShutdown() ? new NodeDownException(node.id()) : e);
        }
        // A crash between requireRunning() and add() would have missed this future.
        if (!running || current != engine) {
            future.completeExceptionally(new NodeDownException(node.id()));
        }
        return future;
    }

    private Engine requireRunning() {
        Engine current = engine;
        if (!running || current == null) {
            throw new NodeDownException(node.id());
        }
        return current;
    }

    private void open() {
        if (running) {
            return;
        }
        Engine fresh = newEngine();
        ServerSocket socket;
        try {
            socket = bind();
        } catch (IOException e) {
            fresh.processing().shutdownNow("Requests port could not be bound");
            throw new UncheckedIOException("Node " + node.id() + " cannot bind requests port " + port(), e);
        }
        engine = fresh;
        server = socket;
        running = true;
        Thread accept = new Thread(() -> acceptLoop(socket), "udcf-requests-n" + node.id() + "-accept");
        accept.setDaemon(true);
        accept.start();
    }

    private Engine newEngine() {
        int id = node.id();
        ThreadPoolExecutor executor = NodeExecutorFactory.forNode(id, node.capacity(), properties);
        // Gauges are deliberately not bound in E2b (track file, known issues).
        ThreadPoolMetrics metrics = new ThreadPoolMetrics(meterRegistry, executor, throughputTracker, id);
        RequestProcessingService processing = new RequestProcessingService(executor, new WorkloadExecutor(),
                registry, throughputTracker, metrics, requestEvents, id, node.capacity().workMultiplier());
        ThreadPoolStatsService stats = new ThreadPoolStatsService(executor, registry, throughputTracker,
                properties.queueCapacity(), id);
        return new Engine(executor, processing, stats, ConcurrentHashMap.newKeySet());
    }

    private ServerSocket bind() throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);   // see "Binding" in the class Javadoc
            socket.bind(new InetSocketAddress(RequestsProtocol.LOOPBACK, port()));
            return socket;
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    private void close(String reason) {
        running = false;
        closeQuietly(server);
        connections.forEach(RequestsNodeService::closeQuietly);
        Engine current = engine;
        if (current == null || current.executor().isShutdown()) {
            return;
        }
        // Fail the futures first, so an interrupted task cannot complete them differently.
        NodeDownException down = new NodeDownException(node.id());
        current.tasks().forEach(future -> future.completeExceptionally(down));
        current.processing().shutdownNow(reason);
    }

    private void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                return;   // closed by crash() or stop(): expected
            }
            connections.add(client);
            // A crash between accept() and add() would have missed this connection.
            if (!running || socket.isClosed()) {
                closeQuietly(client);
                connections.remove(client);
                return;
            }
            Thread.ofVirtual().name("udcf-requests-n" + node.id() + "-conn").start(() -> serve(client));
        }
    }

    private void serve(Socket client) {
        try (client) {
            client.setSoTimeout(properties.readTimeoutMillis());
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            String line;
            try {
                line = RequestsProtocol.readLine(in);
            } catch (ProtocolException tooLong) {
                refuse(out, tooLong.getMessage());
                discardRest(client, in);
                return;
            }
            if (line == null) {
                return;
            }
            WorkRequest request;
            try {
                request = RequestsProtocol.decodeRequest(line);
            } catch (ProtocolException e) {
                refuse(out, e.getMessage());
                return;
            }

            long receiveLamport = node.clock().update(request.lamportTime());   // Lamport rule 3
            Engine current = engine;
            if (!running || current == null) {
                return;
            }
            RequestResult result = current.processing().submitAsync(request.type(), request.payloadSize()).join();
            if (!running || current != engine) {
                return;   // crashed while working: a dead node sends nothing
            }
            long replyLamport = node.clock().tick();                             // Lamport rule 2
            RequestsProtocol.writeLine(out, RequestsProtocol.encode(toReply(result, replyLamport)));
            publish(request, result, receiveLamport, replyLamport);
        } catch (IOException e) {
            log.debug("Node {}: connection ended early: {}", node.id(), e.getMessage());
        } finally {
            connections.remove(client);
        }
    }

    private void refuse(OutputStream out, String reason) throws IOException {
        log.warn("Node {} refused a request on port {}: {}", node.id(), port(), reason);
        RequestsProtocol.writeLine(out, RequestsProtocol.error(node.id(), node.clock().tick(), reason));
    }

    /**
     * After refusing an over-long line: half-close, then read and drop what the client
     * still sends (bounded, and limited by the read timeout). Closing with unread input
     * would make TCP reset the connection, which can destroy the ERROR reply before the
     * client reads it.
     */
    private static void discardRest(Socket client, InputStream in) throws IOException {
        client.shutdownOutput();
        byte[] buffer = new byte[RequestsProtocol.MAX_LINE_LENGTH];
        long discarded = 0;
        int read;
        while (discarded < MAX_DISCARD_BYTES && (read = in.read(buffer)) != -1) {
            discarded += read;
        }
    }

    private WorkReply toReply(RequestResult result, long lamportTime) {
        String detail = result.resultSummary() != null ? result.resultSummary() : result.errorMessage();
        return new WorkReply(node.id(), lamportTime, result.id(), result.status(), result.threadName(),
                result.queueWaitMillis(), result.processingMillis(), result.totalMillis(), detail);
    }

    private void publish(WorkRequest request, RequestResult result, long receiveLamport, long replyLamport) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", result.id());
        data.put("receiveLamport", receiveLamport);
        data.put("workload", result.type().name());
        data.put("payloadSize", request.payloadSize());
        if (result.threadName() != null) {
            data.put("threadName", result.threadName());
        }
        data.put("totalMillis", result.totalMillis());
        String outcome = result.status().name().toLowerCase(Locale.ROOT);
        bus.publish(EventDraft.of(MODULE, node.id(), "REQUEST_" + result.status().name(), replyLamport)
                .withPeer(request.senderId())
                .withMessage("Request " + result.id() + " from node " + request.senderId() + " " + outcome)
                .withData(data));
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            // Closing an already-closed socket is not worth reporting.
        }
    }
}
