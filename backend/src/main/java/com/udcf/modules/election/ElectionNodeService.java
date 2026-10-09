package com.udcf.modules.election;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.cluster.NodeService;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.core.failure.FailureDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;

/**
 * Experiment 4 transport: a node's "election" service, one UDP socket on
 * 127.0.0.1:{@code ports().election()} (700k) carrying Bully, Ring and the shared failure
 * detector's heartbeats (R2, link L2).
 *
 * <p>The algorithms are the pure E4a classes, used unchanged: this class is their
 * {@link ElectionMessenger} and {@link ElectionTimer}, and routes each datagram by type
 * ({@code ELECTION}, {@code OK}, {@code COORDINATOR} to Bully; {@code RING_*}, {@code PROBE},
 * {@code PROBE_ACK} to Ring; {@code HEARTBEAT} to the {@link FailureDetector}).</p>
 *
 * <p><b>Threads, and why ring messages never run on the listener (hard rule 7).</b> The legacy
 * {@code ElectionNode} handled a ring token by probing the successor and then <i>waiting</i>
 * for the PROBE_ACK. Only the listener thread can deliver that PROBE_ACK, so handling a ring
 * message on the listener deadlocked the node. Here the listener thread only reads a datagram,
 * decodes it and hands it to the node's single worker thread
 * ({@code udcf-election-n<k>-worker}); every algorithm step, every timer (OK, coordinator,
 * probe and ring-completion timeouts) and the heartbeat tick run on that worker. Together
 * with the E4a callback prober, nothing ever waits for a reply, and the listener stays free
 * to deliver one.</p>
 *
 * <p><b>Lamport (link L4).</b> The five election message types and PROBE/PROBE_ACK are
 * stamped on send with {@code node.clock().tick()} (replacing the algorithm's private
 * counter, so the wire carries the node's shared clock) and the receiver calls
 * {@code node.clock().update(t)}. Algorithm events, {@code MESSAGE_SENT} and
 * {@code MESSAGE_RECEIVED} are published with node-clock values. <b>Deviation:</b> heartbeats
 * carry no Lamport stamp and never touch the clock (track B, Deviations).</p>
 *
 * <p><b>Lifecycle.</b> Start and recover bind first, then set {@code running}, then start the
 * listener. Crash and stop close the socket, join the listener and the worker (5 s each,
 * {@link IllegalStateException} if one is still alive), and only then crash the algorithms,
 * so on return no {@code udcf-election-n<k>-*} thread is alive and the port is free.
 * Recover calls the algorithms' {@code recover()}; Bully's starts an election (E4a), which is
 * how a recovered highest node reclaims leadership.</p>
 *
 * <p>Listeners added here and to {@link #failureDetector()} run on the worker thread; they
 * must not block and must not crash or recover this node.</p>
 */
public class ElectionNodeService implements NodeService, ElectionParticipant {

    public static final String NAME = "election";
    public static final String MODULE = "election";
    public static final String MESSAGE_SENT = "MESSAGE_SENT";
    public static final String MESSAGE_RECEIVED = "MESSAGE_RECEIVED";
    public static final String BULLY = "BULLY";
    public static final String RING = "RING";

    /** Message types published as MESSAGE_SENT / MESSAGE_RECEIVED; probes and heartbeats would flood the log. */
    static final Set<ElectionMessageType> PUBLISHED_TYPES = EnumSet.of(
            ElectionMessageType.ELECTION, ElectionMessageType.OK, ElectionMessageType.COORDINATOR,
            ElectionMessageType.RING_ELECTION, ElectionMessageType.RING_COORDINATOR);

    static final Duration EXIT_TIMEOUT = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(ElectionNodeService.class);
    private static final InetAddress LOOPBACK = loopback();
    private static final ElectionTimer.Cancellable NOT_SCHEDULED = () -> { };

    /** Closing it removes the listener; closing twice does nothing. */
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private final ClusterNode node;
    private final List<Integer> nodeIds;
    private final IntUnaryOperator peerPort;
    private final ClusterEventBus bus;
    private final long heartbeatIntervalMillis;
    private final String threadPrefix;
    private final ElectionConfig config;
    private final Clock wallClock;
    private final LongSupplier nanoClock;
    private final FailureDetector detector;
    private volatile BullyAlgorithm bully;   // replaced only by resetState(), while no election thread runs
    private volatile RingAlgorithm ring;
    private final List<ElectionEventListener> electionListeners = new CopyOnWriteArrayList<>();

    private volatile DatagramSocket socket;
    private volatile Thread listenerThread;
    private volatile ScheduledThreadPoolExecutor worker;
    private volatile List<Thread> workerThreads = List.of();
    private volatile boolean running;
    private volatile Integer coordinatorId;

    /**
     * @param nodeIds  every node of the cluster, including this one; Bully and Ring use them in
     *                 ascending order
     * @param peerPort the election port of a node id
     */
    public ElectionNodeService(ClusterNode node, List<Integer> nodeIds, IntUnaryOperator peerPort,
                               ElectionProperties properties, ClusterEventBus bus, Clock wallClock,
                               LongSupplier nanoClock) {
        this.node = Objects.requireNonNull(node, "node must not be null");
        Objects.requireNonNull(nodeIds, "nodeIds must not be null");
        this.peerPort = Objects.requireNonNull(peerPort, "peerPort must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock must not be null");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        if (!nodeIds.contains(node.id())) {
            throw new IllegalArgumentException("nodeIds " + nodeIds + " must contain node " + node.id());
        }
        this.nodeIds = List.copyOf(new TreeSet<>(nodeIds));
        this.heartbeatIntervalMillis = properties.heartbeatIntervalMillis();
        this.threadPrefix = "udcf-election-n" + node.id() + "-";
        this.config = properties.toElectionConfig();
        this.detector = new FailureDetector(node, this.nodeIds, properties.toFailureDetectorConfig(),
                MODULE, this::sendHeartbeat, bus, nanoClock);
        buildAlgorithms();
    }

    /** Fresh E4a algorithms (no coordinator, no election in progress) and a fresh prober. */
    private void buildAlgorithms() {
        ElectionMessenger messenger = this::sendElectionMessage;
        ElectionTimer timer = this::schedule;
        LivenessProber prober = new DefaultLivenessProber(node.id(), messenger, timer, config.probeTimeoutMs());
        this.bully = new BullyAlgorithm(node.id(), this.nodeIds, config, messenger,
                event -> onAlgorithmEvent(BULLY, event), timer, nanoClock, wallClock);
        this.ring = new RingAlgorithm(node.id(), this.nodeIds, config, messenger,
                event -> onAlgorithmEvent(RING, event), timer, wallClock, prober);
    }

    /** Returns this node's election service, creating and starting it on first use. */
    public static ElectionNodeService on(ClusterNode node, Cluster cluster, ElectionProperties properties,
                                         ClusterEventBus bus) {
        List<Integer> ids = cluster.nodes().stream().map(ClusterNode::id).toList();
        return node.ensureService(NAME, n -> new ElectionNodeService(n, ids,
                peerId -> cluster.node(peerId).ports().election(), properties, bus,
                Clock.systemUTC(), System::nanoTime));
    }

    /** Returns the node's election service if it is registered, without starting it. */
    public static Optional<ElectionNodeService> find(ClusterNode node) {
        return node.service(NAME).map(ElectionNodeService.class::cast);
    }

    // ------------------------------------------------------------------ NodeService

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        open();
    }

    @Override
    public synchronized void crash() {
        if (!running) {
            return;
        }
        IllegalStateException failure = close();
        coordinatorId = null;
        // Every election thread has stopped, so nothing races these; they publish CRASH events.
        bully.crash();
        ring.crash();
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public synchronized void recover() {
        if (running) {
            return;
        }
        open();
        // Bully's recover() starts an election (E4a): a recovered highest node reclaims leadership.
        handOff("recover", () -> {
            ring.recover();
            bully.recover();
        });
    }

    @Override
    public synchronized void stop() {
        IllegalStateException failure = close();
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public boolean isRunning() {
        Thread listener = listenerThread;
        return running && listener != null && listener.isAlive();
    }

    /**
     * First half of a clean-slate reset: closes the socket and joins every
     * {@code udcf-election-n<k>-*} thread, which also drops every pending timer. No events,
     * and the algorithms are left as they are. Halt every node's service before calling
     * {@link #resetState} on any of them, so no message from an old election reaches a fresh one.
     *
     * @return whether the service was running
     * @throws IllegalStateException if a thread did not exit within 5 s
     */
    public synchronized boolean halt() {
        boolean wasRunning = running;
        IllegalStateException failure = close();
        if (failure != null) {
            throw failure;
        }
        return wasRunning;
    }

    /**
     * Second half of a clean-slate reset: fresh Bully and Ring algorithms and prober (no
     * coordinator, no election in progress), no learned coordinator, and a fresh failure
     * detector view (no suspicions, every peer's silence counted from now). Reopens the socket
     * if {@code reopen} and the node is up. Publishes nothing; starts no election. Halts first
     * if still running.
     */
    public synchronized void resetState(boolean reopen) {
        if (running || listenerThread != null || worker != null) {
            halt();
        }
        buildAlgorithms();
        coordinatorId = null;
        detector.reset();
        if (reopen && node.isUp()) {
            open();
        }
    }

    // ------------------------------------------------------------------ election API

    /** Starts a Bully election from this node, on the worker thread. */
    public void startBully() {
        submit("start Bully", bully::startElection);
    }

    /** Starts a Ring election from this node, on the worker thread. */
    public void startRing() {
        submit("start Ring", ring::startElection);
    }

    /**
     * The coordinator this node most recently learned from either algorithm (ELECTED or
     * COORDINATOR_ACCEPTED); {@code null} if none yet or since the last crash.
     */
    public Integer coordinatorId() {
        return coordinatorId;
    }

    /** This node's shared failure detector (link L2); its heartbeats use this socket. */
    public FailureDetector failureDetector() {
        return detector;
    }

    /** Receives every Bully and Ring event of this node, on the worker thread. */
    public Registration addElectionListener(ElectionEventListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        electionListeners.add(listener);
        AtomicBoolean registered = new AtomicBoolean(true);
        return () -> {
            if (registered.compareAndSet(true, false)) {
                electionListeners.remove(listener);
            }
        };
    }

    public ClusterNode node() {
        return node;
    }

    public int port() {
        return node.ports().election();
    }

    /** Every node id of the cluster, ascending (the Bully order and the ring order). */
    public List<Integer> nodeIds() {
        return nodeIds;
    }

    @Override
    public int getNodeId() {
        return node.id();
    }

    @Override
    public boolean isCrashed() {
        return !isRunning();
    }

    @Override
    public Integer getCoordinatorId() {
        return coordinatorId;
    }

    // ------------------------------------------------------------------ algorithm callbacks

    private void onAlgorithmEvent(String algorithm, ElectionEvent event) {
        Integer learned = (BULLY.equals(algorithm) ? bully : ring).getCoordinatorId();
        if ((event.type() == ElectionEventType.ELECTED || event.type() == ElectionEventType.COORDINATOR_ACCEPTED)
                && learned != null) {
            coordinatorId = learned;
        }
        bus.publish(EventDraft.of(MODULE, node.id(), event.type().name(), node.clock().tick())
                .withPeer(event.peerId() > 0 ? event.peerId() : null)
                .withMessage(eventText(algorithm, event.type(), event.peerId(), learned))
                .withData(Map.of("algorithm", algorithm)));
        for (ElectionEventListener listener : electionListeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException e) {
                log.warn("Node {}: election listener threw on {}; continuing", node.id(), event.type(), e);
            }
        }
    }

    /**
     * Sentence-case name of a wire message type for event messages: the page shows them as
     * written, and protocol names in capitals would read as shouting. Types and data keep the
     * protocol names.
     */
    static String messageName(ElectionMessageType type) {
        return switch (type) {
            case ELECTION -> "Election message";
            case OK -> "Answer";
            case COORDINATOR -> "Coordinator announcement";
            case RING_ELECTION -> "Ring token";
            case RING_COORDINATOR -> "Ring result";
            case PROBE -> "Liveness probe";
            case PROBE_ACK -> "Probe answer";
            case HEARTBEAT -> "Heartbeat";
        };
    }

    /**
     * Sentence-case text for an algorithm event, written here from its type, peer and the
     * coordinator the algorithm now knows, instead of the E4a description (which names
     * protocol messages in capitals). E4a is unchanged.
     *
     * @param peerId      the event's peer, or -1 for none
     * @param coordinator the algorithm's coordinator after the event, or null
     */
    static String eventText(String algorithm, ElectionEventType type, int peerId, Integer coordinator) {
        String name = BULLY.equals(algorithm) ? "Bully" : "Ring";
        return switch (type) {
            case ELECTION_START -> "Started a " + name + " election";
            case ELECTION_RESTART -> "Started its own Bully election after an election message from node " + peerId;
            case OK_RECEIVED -> "Node " + peerId + " answered, so this node waits for the coordinator announcement";
            case WAITING_FOR_COORDINATOR -> "Waiting for the coordinator announcement";
            case ELECTED -> BULLY.equals(algorithm)
                    ? "Elected itself coordinator: no higher node answered"
                    : "The ring token came back: node " + coordinator + " has the highest id and wins";
            case COORDINATOR_ACCEPTED -> "Accepted node " + (coordinator != null ? coordinator : peerId) + " as coordinator";
            case TOKEN_FORWARDED -> "Forwarded the ring token to node " + peerId;
            case DEAD_NODE_SKIPPED -> "Skipped dead node " + peerId;
            case ELECTION_TIMEOUT -> BULLY.equals(algorithm)
                    ? "No coordinator announcement arrived in time; starting again"
                    : "The ring token did not come back in time";
            case CRASH -> "Node crashed";
            case RECOVER -> "Node recovered";
            case UNKNOWN_SENDER -> "Ignored a message of an unknown type from node " + peerId;
            case LATE_MESSAGE -> "Ignored a late answer from node " + peerId;
        };
    }

    /** {@link ElectionMessenger}: stamps with the node clock (L4 Rule 2) and sends. */
    private void sendElectionMessage(int targetNodeId, ElectionMessage message) {
        if (!running) {
            return;
        }
        long stamped = node.clock().tick();
        ElectionMessage out = new ElectionMessage(message.type(), node.id(), stamped, message.payload());
        if (transmit(targetNodeId, out) && PUBLISHED_TYPES.contains(out.type())) {
            bus.publish(EventDraft.of(MODULE, node.id(), MESSAGE_SENT, stamped)
                    .withPeer(targetNodeId)
                    .withMessage(messageName(out.type()) + " to node " + targetNodeId)
                    .withData(Map.of("messageType", out.type().name())));
        }
    }

    /** {@link com.udcf.core.failure.HeartbeatSender}: no Lamport stamp, the clock is not touched. */
    private void sendHeartbeat(int peerId) {
        transmit(peerId, new ElectionMessage(ElectionMessageType.HEARTBEAT, node.id(), 0, ""));
    }

    /** {@link ElectionTimer}: runs on the worker; dropped once the node has crashed. */
    private ElectionTimer.Cancellable schedule(Runnable action, long delayMillis) {
        ScheduledThreadPoolExecutor current = worker;
        if (current == null) {
            return NOT_SCHEDULED;
        }
        try {
            ScheduledFuture<?> future = current.schedule(guarded("timer", action), delayMillis, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        } catch (RejectedExecutionException e) {
            return NOT_SCHEDULED;
        }
    }

    // ------------------------------------------------------------------ receive side

    private void listen(DatagramSocket bound) {
        byte[] buffer = new byte[ElectionMessage.MAX_DATAGRAM_SIZE + 1];
        while (running && !bound.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                bound.receive(packet);
            } catch (IOException e) {
                // Covers PortUnreachableException (Windows reports an ICMP refusal from an
                // earlier send here) and any SocketException while the socket is still open.
                if (!running || bound.isClosed()) {
                    return;
                }
                log.debug("Node {}: election receive failed, listener continues: {}", node.id(), e.toString());
                continue;
            }
            try {
                decodeAndHandOff(packet);
            } catch (RuntimeException e) {
                log.warn("Node {}: dropped election datagram: {}", node.id(), e.toString());
            }
        }
    }

    /** Listener thread: decode and hand off only; no algorithm work and no clock update here. */
    private void decodeAndHandOff(DatagramPacket packet) {
        if (packet.getLength() > ElectionMessage.MAX_DATAGRAM_SIZE) {
            log.warn("Node {}: dropped oversize election datagram (more than {} bytes)",
                    node.id(), ElectionMessage.MAX_DATAGRAM_SIZE);
            return;
        }
        String raw = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
        ElectionMessage message;
        try {
            message = ElectionMessage.fromWire(raw);
        } catch (IllegalArgumentException e) {
            log.warn("Node {}: dropped malformed election datagram: {}", node.id(), e.getMessage());
            return;
        }
        if (message.senderId() == node.id() || !nodeIds.contains(message.senderId())) {
            log.warn("Node {}: dropped election datagram from unknown sender {}", node.id(), message.senderId());
            return;
        }
        handOff("deliver " + message.type(), () -> deliver(message));
    }

    /** Worker thread. */
    private void deliver(ElectionMessage message) {
        if (message.type() == ElectionMessageType.HEARTBEAT) {
            detector.onHeartbeat(message.senderId());   // exempt from Lamport: no clock update
            return;
        }
        long received = node.clock().update(message.lamportTime());   // L4 Rule 3
        if (PUBLISHED_TYPES.contains(message.type())) {
            bus.publish(EventDraft.of(MODULE, node.id(), MESSAGE_RECEIVED, received)
                    .withPeer(message.senderId())
                    .withMessage(messageName(message.type()) + " from node " + message.senderId())
                    .withData(Map.of("messageType", message.type().name(), "causedByTime", message.lamportTime())));
        }
        switch (message.type()) {
            case ELECTION, OK, COORDINATOR -> bully.onReceive(message);
            case RING_ELECTION, RING_COORDINATOR, PROBE, PROBE_ACK -> ring.onReceive(message);
            case HEARTBEAT -> { }   // handled above
        }
    }

    // ------------------------------------------------------------------ socket and threads

    private boolean transmit(int targetNodeId, ElectionMessage message) {
        DatagramSocket current = socket;
        if (!running || current == null) {
            return false;
        }
        byte[] data = message.toWire().getBytes(StandardCharsets.UTF_8);
        if (data.length > ElectionMessage.MAX_DATAGRAM_SIZE) {
            log.warn("Node {}: not sending {} to node {}: {} bytes exceeds {}", node.id(), message.type(),
                    targetNodeId, data.length, ElectionMessage.MAX_DATAGRAM_SIZE);
            return false;
        }
        try {
            current.send(new DatagramPacket(data, data.length, LOOPBACK, peerPort.applyAsInt(targetNodeId)));
            return true;
        } catch (IOException e) {
            if (running) {
                log.debug("Node {}: sending {} to node {} failed: {}", node.id(), message.type(), targetNodeId,
                        e.toString());
            }
            return false;
        }
    }

    private void open() {
        int port = port();
        DatagramSocket bound = null;
        try {
            // No SO_REUSEADDR: on Linux it would let a second UDP socket bind this port and hide a collision.
            bound = new DatagramSocket(null);
            bound.bind(new InetSocketAddress(LOOPBACK, port));
        } catch (SocketException e) {
            if (bound != null) {
                bound.close();
            }
            throw new IllegalStateException("Node " + node.id() + " cannot bind election port " + port, e);
        }
        List<Thread> threads = new CopyOnWriteArrayList<>();
        ScheduledThreadPoolExecutor fresh = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, threadPrefix + "worker");
            thread.setDaemon(true);
            threads.add(thread);
            return thread;
        });
        fresh.setRemoveOnCancelPolicy(true);
        socket = bound;
        worker = fresh;
        workerThreads = threads;
        running = true;
        DatagramSocket listening = bound;
        Thread listener = new Thread(() -> listen(listening), threadPrefix + "listener");
        listener.setDaemon(true);
        listenerThread = listener;
        listener.start();
        detector.start();
        fresh.scheduleAtFixedRate(guarded("heartbeat tick", detector::tick), 0, heartbeatIntervalMillis,
                TimeUnit.MILLISECONDS);
    }

    /** Closes the socket, then joins the listener and the worker. Returns the first failure, if any. */
    private IllegalStateException close() {
        running = false;
        detector.stop();
        DatagramSocket current = socket;
        if (current != null) {
            current.close();
        }
        socket = null;
        IllegalStateException failure = null;
        Thread listener = listenerThread;
        if (listener != null) {
            failure = collect(failure, () -> join(listener));
            listenerThread = null;
        }
        ScheduledThreadPoolExecutor executor = worker;
        if (executor != null) {
            executor.shutdownNow();
            failure = collect(failure, () -> awaitTermination(executor));
            for (Thread thread : workerThreads) {
                failure = collect(failure, () -> join(thread));
            }
            worker = null;
            workerThreads = List.of();
        }
        return failure;
    }

    private void submit(String what, Runnable task) {
        ScheduledThreadPoolExecutor current = worker;
        if (!running || current == null) {
            throw new NodeDownException(node.id());
        }
        try {
            current.execute(guarded(what, task));
        } catch (RejectedExecutionException e) {
            throw new NodeDownException(node.id());
        }
    }

    /** Like {@link #submit} but silent: a message arriving while the node goes down is lost, as on a dead node. */
    private void handOff(String what, Runnable task) {
        ScheduledThreadPoolExecutor current = worker;
        if (current == null) {
            return;
        }
        try {
            current.execute(guarded(what, task));
        } catch (RejectedExecutionException e) {
            log.debug("Node {}: election worker stopped; dropped {}", node.id(), what);
        }
    }

    private Runnable guarded(String what, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("Node {}: election {} failed", node.id(), what, e);
            }
        };
    }

    private interface Wait {
        void run();
    }

    private static IllegalStateException collect(IllegalStateException first, Wait wait) {
        try {
            wait.run();
            return first;
        } catch (IllegalStateException e) {
            if (first == null) {
                return e;
            }
            first.addSuppressed(e);
            return first;
        }
    }

    private void join(Thread thread) {
        try {
            if (!thread.join(EXIT_TIMEOUT)) {
                throw new IllegalStateException("Node " + node.id() + ": thread " + thread.getName()
                        + " did not exit within " + EXIT_TIMEOUT.toMillis() + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Node " + node.id() + ": interrupted waiting for " + thread.getName(), e);
        }
    }

    private void awaitTermination(ScheduledThreadPoolExecutor executor) {
        try {
            if (!executor.awaitTermination(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Node " + node.id() + ": election worker did not finish within "
                        + EXIT_TIMEOUT.toMillis() + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Node " + node.id() + ": interrupted waiting for the election worker", e);
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
