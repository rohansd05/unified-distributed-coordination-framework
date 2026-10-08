package com.udcf.modules.clocksync;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.cluster.NodeService;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.modules.clocksync.berkeley.BerkeleyAveragingCoordinator;
import com.udcf.modules.clocksync.berkeley.BerkeleyRoundResult;
import com.udcf.modules.clocksync.berkeley.ClockDriftModel;
import com.udcf.modules.clocksync.berkeley.ClockSyncRoleSelector;
import com.udcf.modules.clocksync.berkeley.NodeAdjustment;
import com.udcf.modules.clocksync.berkeley.NodeClockReading;
import com.udcf.modules.clocksync.lamport.ClockEvent;
import com.udcf.modules.clocksync.lamport.ClockEventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.SocketException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntUnaryOperator;

/**
 * Experiment 3 transport: a node's "clock" service, a UDP socket on
 * 127.0.0.1:{@code ports().clock()} (600k) exchanging Lamport messages and Berkeley
 * time-daemon poll/adjust rounds (R2).
 *
 * <p><b>Lamport Logical Clock (link L4):</b> Uses each node's existing
 * {@link ClusterNode#clock()}; never creates another {@code LamportClock}. The sender ticks
 * before sending and carries the value with the message; the receiver calls
 * {@code clock.update(receivedTime)} (Rule 3).</p>
 *
 * <p><b>Berkeley Physical Clock (R7, R11):</b> Each node maintains simulated drift via
 * {@link ClockDriftModel}. Across crash and recovery, the simulated clock keeps its offset
 * and drift (modelling hardware), while sockets and threads are rebuilt.</p>
 *
 * <p><b>Anti-deadlock Listener Rule:</b> The UDP listener loop never blocks waiting for
 * replies that only it can deliver. Rounds execute on calling/orchestrating threads, and
 * the listener merely hands replies over via {@link CompletableFuture}s keyed by round id.</p>
 */
public class ClockNodeService implements NodeService {

    public static final String NAME = "clock";
    public static final String MODULE = "clocksync";

    private static final Logger log = LoggerFactory.getLogger(ClockNodeService.class);
    private static final InetAddress LOOPBACK = loopback();

    private final ClusterNode node;
    private final IntUnaryOperator peerPortResolver;
    private final ClockEventLog eventLog;
    private final ClockDriftModel driftModel;
    private final ClusterEventBus bus;
    private final ClockSyncProperties properties;
    private final Clock wallClock;

    private final AtomicLong nextMessageId = new AtomicLong(0);
    private final AtomicLong nextRoundId = new AtomicLong(0);
    private final Map<Long, ActiveBerkeleyRound> activeRounds = new ConcurrentHashMap<>();

    private volatile DatagramSocket socket;
    private volatile Thread listenerThread;
    private volatile boolean running;

    /** In-flight state of a Berkeley round being coordinated by this node. */
    private static class ActiveBerkeleyRound {
        final long roundId;
        final int daemonNodeId;
        final Map<Integer, Long> sendTimesNanos = new ConcurrentHashMap<>();
        final Map<Integer, CompletableFuture<NodeClockReading>> pollFutures = new ConcurrentHashMap<>();
        final Map<Integer, CompletableFuture<Long>> adjustFutures = new ConcurrentHashMap<>();
        final List<NodeClockReading> readings = new ArrayList<>();

        ActiveBerkeleyRound(long roundId, int daemonNodeId) {
            this.roundId = roundId;
            this.daemonNodeId = daemonNodeId;
        }
    }

    public ClockNodeService(
            ClusterNode node,
            IntUnaryOperator peerPortResolver,
            ClockEventLog eventLog,
            ClockDriftModel driftModel,
            ClusterEventBus bus,
            ClockSyncProperties properties,
            Clock wallClock
    ) {
        this.node = Objects.requireNonNull(node, "node must not be null");
        this.peerPortResolver = Objects.requireNonNull(peerPortResolver, "peerPortResolver must not be null");
        this.eventLog = Objects.requireNonNull(eventLog, "eventLog must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.properties = properties != null ? properties : new ClockSyncProperties(1000L, 500L, Map.of());
        this.wallClock = wallClock != null ? wallClock : Clock.systemUTC();

        if (driftModel != null) {
            this.driftModel = driftModel;
        } else {
            ClockSyncProperties.NodeDriftConfig cfg = this.properties.configFor(node.id());
            this.driftModel = new ClockDriftModel(node.id(), cfg.initialOffsetMillis(), cfg.driftRateMsPerSec(), this.wallClock.instant());
        }
    }

    public static ClockNodeService on(
            ClusterNode node,
            IntUnaryOperator peerPortResolver,
            ClockEventLog eventLog,
            ClockDriftModel driftModel,
            ClusterEventBus bus,
            ClockSyncProperties properties,
            Clock wallClock
    ) {
        return node.ensureService(NAME, n -> new ClockNodeService(
                n, peerPortResolver, eventLog, driftModel, bus, properties, wallClock));
    }

    public static ClockNodeService on(
            ClusterNode node,
            Cluster cluster,
            ClockEventLog eventLog,
            ClockDriftModel driftModel,
            ClusterEventBus bus,
            ClockSyncProperties properties,
            Clock wallClock
    ) {
        return on(node, peerId -> cluster.node(peerId).ports().clock(),
                eventLog, driftModel, bus, properties, wallClock);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        bindAndListen();
        running = true;
    }

    @Override
    public synchronized void crash() {
        if (!running) {
            return;
        }
        running = false;
        closeSocket();
        // Fail any pending rounds
        for (ActiveBerkeleyRound round : activeRounds.values()) {
            for (CompletableFuture<NodeClockReading> f : round.pollFutures.values()) {
                f.completeExceptionally(new NodeDownException(node.id()));
            }
            for (CompletableFuture<Long> f : round.adjustFutures.values()) {
                f.completeExceptionally(new NodeDownException(node.id()));
            }
        }
        activeRounds.clear();
        // Note: driftModel is preserved across crash and recover (models hardware clock)
    }

    @Override
    public synchronized void recover() {
        if (running) {
            return;
        }
        bindAndListen();
        running = true;
    }

    @Override
    public synchronized void stop() {
        running = false;
        closeSocket();
        activeRounds.clear();
    }

    @Override
    public boolean isRunning() {
        return running && socket != null && !socket.isClosed();
    }

    public ClusterNode node() {
        return node;
    }

    public int port() {
        return node.ports().clock();
    }

    public ClockDriftModel driftModel() {
        return driftModel;
    }

    public ClockEventLog eventLog() {
        return eventLog;
    }

    public Clock wallClock() {
        return wallClock;
    }

    // -------------------------------------------------------------------------
    // Lamport Operations
    // -------------------------------------------------------------------------

    /**
     * Executes Lamport Rule 1: records an internal local event, advances the clock by 1,
     * logs to {@link ClockEventLog}, and publishes a cluster event.
     */
    public long recordLocalEvent(String description) {
        checkRunning();
        long stamped;
        synchronized (node.clock()) {
            stamped = node.clock().tick();
            eventLog.record(ClockEvent.local(node.id(), stamped, description, wallClock.instant()));
            bus.publish(EventDraft.of(MODULE, node.id(), "CLOCK_LOCAL_EVENT", stamped)
                    .withMessage(description)
                    .withData(Map.of("description", description)));
        }
        return stamped;
    }

    /**
     * Executes Lamport Rule 2: advances the clock by 1, sends a message over UDP to the
     * target node, logs to {@link ClockEventLog}, and publishes a cluster event.
     */
    public long sendLamportMessage(int targetNodeId, String payload) {
        long messageId = nextMessageId.incrementAndGet();
        return sendLamportMessage(targetNodeId, messageId, payload);
    }

    public long sendLamportMessage(int targetNodeId, long messageId, String payload) {
        checkRunning();
        long stamped;
        synchronized (node.clock()) {
            stamped = node.clock().tick();
            eventLog.record(ClockEvent.send(node.id(), stamped, targetNodeId, payload, wallClock.instant()));
            bus.publish(EventDraft.of(MODULE, node.id(), "CLOCK_MESSAGE_SENT", stamped)
                    .withPeer(targetNodeId)
                    .withMessage(payload)
                    .withData(Map.of("messageId", messageId, "payload", payload)));
        }

        int targetPort = peerPortResolver.applyAsInt(targetNodeId);
        ClockMessage msg = ClockMessage.lamport(node.id(), stamped, messageId, payload);
        try {
            sendUdp(targetPort, msg);
        } catch (IOException e) {
            log.warn("Node {}: failed to send UDP Lamport message to Node {} on port {}: {}",
                    node.id(), targetNodeId, targetPort, e.getMessage());
        }
        return stamped;
    }

    // -------------------------------------------------------------------------
    // Berkeley Operations
    // -------------------------------------------------------------------------

    public BerkeleyRoundResult runBerkeleyRound() {
        return runBerkeleyRound(properties.outlierThresholdMillis());
    }

    public BerkeleyRoundResult runBerkeleyRound(long outlierThresholdMillis) {
        return runBerkeleyRound(List.of(node.id()), outlierThresholdMillis, Duration.ofMillis(properties.pollTimeoutMillis()));
    }

    public BerkeleyRoundResult runBerkeleyRound(
            Collection<Integer> targetNodeIds,
            long outlierThresholdMillis
    ) {
        return runBerkeleyRound(targetNodeIds, outlierThresholdMillis, Duration.ofMillis(properties.pollTimeoutMillis()));
    }

    /**
     * Executes a Berkeley clock synchronization round coordinated by this node.
     *
     * <ol>
     *   <li>Polls all target nodes over UDP on their clock ports.</li>
     *   <li>Collects offsets, estimating each with Cristian's RTT compensation.</li>
     *   <li>Excludes outliers from average computation, but sends adjustments to all nodes.</li>
     *   <li>Sends adjustments over UDP and applies adjustment to local clock.</li>
     * </ol>
     */
    public BerkeleyRoundResult runBerkeleyRound(
            Collection<Integer> targetNodeIds,
            long outlierThresholdMillis,
            Duration timeout
    ) {
        checkRunning();
        Objects.requireNonNull(targetNodeIds, "targetNodeIds must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");

        long roundId = nextRoundId.incrementAndGet();
        List<Integer> targets = new ArrayList<>(targetNodeIds);
        if (!targets.contains(node.id())) {
            targets.add(node.id());
        }

        bus.publish(EventDraft.of(MODULE, node.id(), "BERKELEY_ROUND_STARTED", node.clock().current())
                .withMessage("Berkeley synchronization round " + roundId + " started by daemon Node " + node.id())
                .withData(Map.of(
                        "roundId", roundId,
                        "daemonId", node.id(),
                        "thresholdMillis", outlierThresholdMillis,
                        "targetNodes", List.copyOf(targets)
                )));

        ActiveBerkeleyRound round = new ActiveBerkeleyRound(roundId, node.id());
        activeRounds.put(roundId, round);

        List<Integer> unresponsiveNodes = new ArrayList<>();
        try {
            // 1. Add daemon's own reading locally (RTT = 0)
            long daemonOffset = driftModel.currentOffsetMillis(wallClock.instant());
            round.readings.add(new NodeClockReading(node.id(), daemonOffset, 0.0));

            // 2. Poll remote peers over UDP
            for (int peerId : targets) {
                if (peerId == node.id()) {
                    continue;
                }
                CompletableFuture<NodeClockReading> future = new CompletableFuture<>();
                round.pollFutures.put(peerId, future);
                round.sendTimesNanos.put(peerId, System.nanoTime());

                long stamped;
                synchronized (node.clock()) {
                    stamped = node.clock().tick();
                }
                int peerPort = peerPortResolver.applyAsInt(peerId);
                ClockMessage pollMsg = ClockMessage.poll(node.id(), stamped, roundId);
                try {
                    sendUdp(peerPort, pollMsg);
                } catch (IOException e) {
                    log.warn("Node {}: failed to send POLL to Node {}: {}", node.id(), peerId, e.getMessage());
                }
            }

            // 3. Await poll replies until timeout
            long timeoutMillis = timeout.toMillis();
            for (int peerId : targets) {
                if (peerId == node.id()) {
                    continue;
                }
                CompletableFuture<NodeClockReading> future = round.pollFutures.get(peerId);
                try {
                    NodeClockReading reading = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
                    round.readings.add(reading);
                } catch (TimeoutException | ExecutionException | InterruptedException e) {
                    unresponsiveNodes.add(peerId);
                    log.info("Node {}: peer Node {} did not reply in round {} (timeout or offline)",
                            node.id(), peerId, roundId);
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            // 4. Compute consensus round
            BerkeleyAveragingCoordinator coordinator = new BerkeleyAveragingCoordinator();
            BerkeleyRoundResult result = coordinator.computeRound(node.id(), round.readings, outlierThresholdMillis);

            // 5. Send adjustments
            for (NodeAdjustment adj : result.adjustments()) {
                if (adj.nodeId() == node.id()) {
                    // Daemon adjusts its own clock too (Requirement 5c)
                    driftModel.applyAdjustment(adj.adjustmentMillis(), wallClock.instant());
                    publishAdjustmentEvent(roundId, adj);
                } else {
                    CompletableFuture<Long> ackFuture = new CompletableFuture<>();
                    round.adjustFutures.put(adj.nodeId(), ackFuture);

                    long stamped;
                    synchronized (node.clock()) {
                        stamped = node.clock().tick();
                    }
                    int peerPort = peerPortResolver.applyAsInt(adj.nodeId());
                    ClockMessage adjMsg = ClockMessage.adjust(
                            node.id(), stamped, roundId, adj.adjustmentMillis(), adj.outlier());
                    try {
                        sendUdp(peerPort, adjMsg);
                    } catch (IOException e) {
                        log.warn("Node {}: failed to send ADJUST to Node {}: {}", node.id(), adj.nodeId(), e.getMessage());
                    }
                }
            }

            // 6. Await ACKs briefly (ignore failures)
            for (Map.Entry<Integer, CompletableFuture<Long>> entry : round.adjustFutures.entrySet()) {
                try {
                    entry.getValue().get(Math.min(timeoutMillis, 200), TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    // Non-blocking: unacknowledged adjust is logged but does not stop round
                }
            }

            // 7. Publish round finished event
            bus.publish(EventDraft.of(MODULE, node.id(), "BERKELEY_ROUND_FINISHED", node.clock().current())
                    .withMessage("Berkeley round " + roundId + " finished: spread " + result.spreadBeforeMillis()
                            + " ms -> " + result.spreadAfterMillis() + " ms")
                    .withData(new LinkedHashMap<>(Map.of(
                            "roundId", roundId,
                            "targetOffset", result.averageOffsetMillis(),
                            "spreadBefore", result.spreadBeforeMillis(),
                            "spreadAfter", result.spreadAfterMillis(),
                            "participatingNodes", result.participatingNodes(),
                            "outlierNodes", result.outlierNodes(),
                            "unresponsiveNodes", List.copyOf(unresponsiveNodes)
                    ))));

            return result;
        } finally {
            activeRounds.remove(roundId);
        }
    }

    private void publishAdjustmentEvent(long roundId, NodeAdjustment adj) {
        bus.publish(EventDraft.of(MODULE, adj.nodeId(), "BERKELEY_NODE_ADJUSTED", node.clock().current())
                .withPeer(node.id())
                .withMessage("Node " + adj.nodeId() + " adjusted by " + adj.adjustmentMillis() + " ms (outlier=" + adj.outlier() + ")")
                .withData(Map.of(
                        "roundId", roundId,
                        "beforeOffset", adj.beforeOffsetMillis(),
                        "adjustment", adj.adjustmentMillis(),
                        "afterOffset", adj.afterOffsetMillis(),
                        "outlier", adj.outlier(),
                        "rttMillis", adj.rttMillis()
                )));
    }

    // -------------------------------------------------------------------------
    // UDP Socket and Listener Loop
    // -------------------------------------------------------------------------

    private void bindAndListen() {
        int bindPort = node.ports().clock();
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(LOOPBACK, bindPort));
        } catch (SocketException e) {
            bus.publish(EventDraft.of(MODULE, node.id(), "SERVICE_START_FAILED", node.clock().tick())
                    .withMessage("Failed to bind clock UDP socket on port " + bindPort)
                    .withData(Map.of("port", bindPort, "error", e.getMessage())));
            throw new IllegalStateException("Clock UDP socket failed to bind on port " + bindPort, e);
        }

        listenerThread = new Thread(this::listenLoop, "clock-" + node.id() + "-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void closeSocket() {
        if (listenerThread != null) {
            listenerThread.interrupt();
            listenerThread = null;
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
            socket = null;
        }
    }

    private void listenLoop() {
        byte[] buffer = new byte[ClockProtocol.MAX_DATAGRAM_SIZE];
        while (running && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                handleDatagram(packet);
            } catch (SocketException e) {
                // Expected when socket is closed during crash or shutdown
                break;
            } catch (Exception e) {
                if (running) {
                    log.warn("Node {}: UDP receive loop error: {}", node.id(), e.getMessage());
                }
            }
        }
    }

    private void handleDatagram(DatagramPacket packet) {
        ClockMessage msg;
        try {
            msg = ClockProtocol.decode(packet.getData(), packet.getLength());
        } catch (ProtocolException e) {
            log.warn("Node {}: dropped malformed datagram: {}", node.id(), e.getMessage());
            return;
        }

        switch (msg.type()) {
            case LAMPORT -> {
                // Lamport Rule 3: update(received)
                long updated;
                synchronized (node.clock()) {
                    updated = node.clock().update(msg.lamportTime());
                    eventLog.record(ClockEvent.receive(
                            node.id(), updated, msg.senderId(), msg.lamportTime(), msg.textPayload(), wallClock.instant()));
                    bus.publish(EventDraft.of(MODULE, node.id(), "CLOCK_MESSAGE_RECEIVED", updated)
                            .withPeer(msg.senderId())
                            .withMessage(msg.textPayload())
                            .withData(Map.of(
                                    "messageId", msg.id(),
                                    "causedByTime", msg.lamportTime(),
                                    "payload", msg.textPayload()
                            )));
                }
            }
            case BERKELEY_POLL -> {
                // Lamport Rule 3 on receive
                long stamped;
                synchronized (node.clock()) {
                    node.clock().update(msg.lamportTime());
                    stamped = node.clock().tick();
                }
                long offset = driftModel.currentOffsetMillis(wallClock.instant());
                int daemonPort = peerPortResolver.applyAsInt(msg.senderId());
                ClockMessage reply = ClockMessage.pollReply(node.id(), stamped, msg.id(), offset);
                try {
                    sendUdp(daemonPort, reply);
                } catch (IOException e) {
                    log.warn("Node {}: failed to send POLL_REPLY to Node {}: {}", node.id(), msg.senderId(), e.getMessage());
                }
            }
            case BERKELEY_POLL_REPLY -> {
                synchronized (node.clock()) {
                    node.clock().update(msg.lamportTime());
                }
                ActiveBerkeleyRound round = activeRounds.get(msg.id());
                if (round != null) {
                    long sendNanos = round.sendTimesNanos.getOrDefault(msg.senderId(), System.nanoTime());
                    long rttNanos = Math.max(0, System.nanoTime() - sendNanos);
                    double rttMillis = rttNanos / 1_000_000.0;
                    CompletableFuture<NodeClockReading> future = round.pollFutures.get(msg.senderId());
                    if (future != null) {
                        future.complete(new NodeClockReading(msg.senderId(), msg.payloadValue(), rttMillis));
                    }
                } else {
                    log.debug("Node {}: ignored late or unknown POLL_REPLY for round {}", node.id(), msg.id());
                }
            }
            case BERKELEY_ADJUST -> {
                long stamped;
                synchronized (node.clock()) {
                    node.clock().update(msg.lamportTime());
                    stamped = node.clock().tick();
                }
                long adjustment = msg.payloadValue();
                driftModel.applyAdjustment(adjustment, wallClock.instant());
                long afterOffset = driftModel.currentOffsetMillis(wallClock.instant());
                NodeAdjustment adj = new NodeAdjustment(
                        node.id(), afterOffset - adjustment, adjustment, afterOffset, msg.outlier(), 0.0);
                publishAdjustmentEvent(msg.id(), adj);

                int daemonPort = peerPortResolver.applyAsInt(msg.senderId());
                ClockMessage ack = ClockMessage.adjustAck(node.id(), stamped, msg.id(), afterOffset);
                try {
                    sendUdp(daemonPort, ack);
                } catch (IOException e) {
                    log.warn("Node {}: failed to send ADJUST_ACK to Node {}: {}", node.id(), msg.senderId(), e.getMessage());
                }
            }
            case BERKELEY_ADJUST_ACK -> {
                synchronized (node.clock()) {
                    node.clock().update(msg.lamportTime());
                }
                ActiveBerkeleyRound round = activeRounds.get(msg.id());
                if (round != null) {
                    CompletableFuture<Long> future = round.adjustFutures.get(msg.senderId());
                    if (future != null) {
                        future.complete(msg.payloadValue());
                    }
                }
            }
        }
    }

    private void sendUdp(int targetPort, ClockMessage msg) throws IOException {
        if (!running || socket == null || socket.isClosed()) {
            throw new NodeDownException(node.id());
        }
        byte[] data = ClockProtocol.encode(msg);
        DatagramPacket packet = new DatagramPacket(data, data.length, LOOPBACK, targetPort);
        socket.send(packet);
    }

    private void checkRunning() {
        if (!running) {
            throw new NodeDownException(node.id());
        }
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve 127.0.0.1", e);
        }
    }
}
