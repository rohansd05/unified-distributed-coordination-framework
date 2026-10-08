package com.udcf.modules.clocksync;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.module.ExperimentModule;
import com.udcf.core.module.ModuleActionGuard;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.clocksync.berkeley.BerkeleyRoundResult;
import com.udcf.modules.clocksync.berkeley.ClockDriftModel;
import com.udcf.modules.clocksync.berkeley.ClockSyncRoleSelector;
import com.udcf.modules.clocksync.berkeley.NodeDriftSnapshot;
import com.udcf.modules.clocksync.dto.BerkeleyRoundAcceptedDto;
import com.udcf.modules.clocksync.dto.BerkeleyRoundCommand;
import com.udcf.modules.clocksync.dto.BerkeleyRoundDto;
import com.udcf.modules.clocksync.dto.CausalVerificationDto;
import com.udcf.modules.clocksync.dto.CausalViolationDto;
import com.udcf.modules.clocksync.dto.ClockSyncLimitsDto;
import com.udcf.modules.clocksync.dto.ClockSyncNodeDto;
import com.udcf.modules.clocksync.dto.ClockSyncOverviewDto;
import com.udcf.modules.clocksync.dto.LocalEventCommand;
import com.udcf.modules.clocksync.dto.LocalEventResult;
import com.udcf.modules.clocksync.dto.NodeAdjustmentDto;
import com.udcf.modules.clocksync.dto.NodeDriftResponseDto;
import com.udcf.modules.clocksync.dto.NodeDriftUpdateCommand;
import com.udcf.modules.clocksync.dto.RandomTrafficCommand;
import com.udcf.modules.clocksync.dto.SendLamportResult;
import com.udcf.modules.clocksync.dto.SendMessageCommand;
import com.udcf.modules.clocksync.dto.TimelineEventDto;
import com.udcf.modules.clocksync.dto.TimelineResponseDto;
import com.udcf.modules.clocksync.dto.TrafficSessionDto;
import com.udcf.modules.clocksync.lamport.CausalInvariantChecker;
import com.udcf.modules.clocksync.lamport.CausalVerificationResult;
import com.udcf.modules.clocksync.lamport.ClockEvent;
import com.udcf.modules.clocksync.lamport.ClockEventLog;
import com.udcf.web.InvalidParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Experiment 3 (Clock Synchronization) module.
 *
 * <p>Coordinates Lamport logical clock tracking and Berkeley physical clock synchronization
 * across the cluster nodes. Manages per-node drift models and the central event log.</p>
 */
@Component
public class ClockSyncModule implements ExperimentModule, AutoCloseable {

    public static final String ID = "clocksync";
    public static final String TITLE = "Clock Synchronization";

    private static final Logger log = LoggerFactory.getLogger(ClockSyncModule.class);

    private static final List<String> OVERVIEW_NOTES = List.of(
            "Lamport logical clocks maintain causal event ordering using happens-before relations across message passing.",
            "Physical clock drift and offsets are simulated mathematically because all nodes execute on a single physical host.",
            "All Berkeley round polling, replies, and clock adjustments use real UDP datagrams on loopback socket bindings.",
            "UDP transport is connectionless and unacknowledged: messages transmitted to crashed nodes report delivery status UNKNOWN."
    );

    private final Cluster cluster;
    private final ClockSyncProperties properties;
    private final ClusterEventBus bus;
    private final Clock wallClock;
    private final ClockEventLog eventLog;
    private final Map<Integer, ClockDriftModel> driftModels = new ConcurrentHashMap<>();
    private final ModuleActionGuard guard = new ModuleActionGuard(ID);
    private final CausalInvariantChecker causalChecker = new CausalInvariantChecker();
    private final ExecutorService executor;
    private final AtomicLong nextMessageId = new AtomicLong();
    private final AtomicLong nextRoundId = new AtomicLong();
    private volatile BerkeleyRoundDto latestRound;

    @org.springframework.beans.factory.annotation.Autowired
    public ClockSyncModule(Cluster cluster, ClockSyncProperties properties,
                           ClusterEventBus bus, Clock wallClock) {
        this(cluster, properties, bus, wallClock, Executors.newVirtualThreadPerTaskExecutor());
    }

    public ClockSyncModule(Cluster cluster, ClockSyncProperties properties,
                           ClusterEventBus bus, Clock wallClock, ExecutorService executor) {
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.eventLog = new ClockEventLog(properties.retainedEventsCapacity());

        for (ClusterNode node : cluster.nodes()) {
            ClockSyncProperties.NodeDriftConfig cfg = properties.configFor(node.id());
            driftModels.put(node.id(), new ClockDriftModel(node.id(), cfg.initialOffsetMillis(), cfg.driftRateMsPerSec(), wallClock.instant()));
            if (node.isUp()) {
                try {
                    ensureService(node);
                } catch (Exception e) {
                    log.warn("Node {}: failed to start ClockNodeService at startup: {}", node.id(), e.getMessage());
                }
            }
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int labNumber() {
        return 3;
    }

    @Override
    public String title() {
        return TITLE;
    }

    @Override
    public ModuleStatus status() {
        return guard.isBusy() ? ModuleStatus.BUSY : ModuleStatus.IDLE;
    }

    @Override
    public void reset() {
        eventLog.clear();
        for (ClusterNode node : cluster.nodes()) {
            ClockDriftModel model = driftModels.get(node.id());
            if (model != null) {
                ClockSyncProperties.NodeDriftConfig cfg = properties.configFor(node.id());
                model.reset(cfg.initialOffsetMillis(), cfg.driftRateMsPerSec(), wallClock.instant());
            }
        }
        latestRound = null;
    }

    public ClockNodeService ensureService(ClusterNode node) {
        ClockDriftModel model = driftModels.get(node.id());
        return ClockNodeService.on(node, cluster, eventLog, model, bus, properties, wallClock);
    }

    public List<ClusterNode> liveNodes() {
        return cluster.nodes().stream().filter(ClusterNode::isUp).toList();
    }

    public ClockSyncOverviewDto overview() {
        List<ClusterNode> live = liveNodes();
        Integer daemonId = live.isEmpty() ? null : ClockSyncRoleSelector.selectTimeDaemon(
                live.stream().map(ClusterNode::id).toList());

        List<ClockSyncNodeDto> nodes = cluster.nodes().stream().map(node -> {
            ClockDriftModel model = driftModels.get(node.id());
            NodeDriftResponseDto driftDto = null;
            if (model != null) {
                NodeDriftSnapshot snap = model.snapshot(wallClock.instant());
                driftDto = new NodeDriftResponseDto(
                        node.id(),
                        snap.offsetMillis(),
                        snap.driftRateMsPerSec(),
                        snap.simulatedTime().toEpochMilli(),
                        true,
                        "Physical clock drift is simulated mathematically; all network operations are real UDP."
                );
            }
            boolean serviceRunning = ClockNodeService.find(node)
                    .map(ClockNodeService::isRunning)
                    .orElse(false);
            return new ClockSyncNodeDto(
                    node.id(),
                    node.status().name(),
                    node.clock().current(),
                    driftDto,
                    serviceRunning
            );
        }).toList();

        ClockSyncLimitsDto limits = new ClockSyncLimitsDto(
                properties.defaultTrafficSeconds(),
                properties.maxTrafficSeconds(),
                properties.defaultMessagesPerSecond(),
                properties.maxMessagesPerSecond(),
                properties.retainedEventsCapacity()
        );

        return new ClockSyncOverviewDto(
                status(),
                guard.currentAction().orElse(null),
                daemonId,
                nodes,
                latestRound,
                limits,
                OVERVIEW_NOTES
        );
    }

    public LocalEventResult recordLocalEvent(int nodeId, LocalEventCommand command) {
        ClusterNode node = cluster.node(nodeId);
        if (!node.isUp()) {
            throw new NodeDownException(nodeId);
        }
        ClockNodeService service = ensureService(node);
        String desc = (command != null && command.description() != null && !command.description().isBlank())
                ? command.description()
                : "Local event on node " + nodeId;
        long lamportTime = service.recordLocalEvent(desc);
        return new LocalEventResult(nodeId, lamportTime, desc, wallClock.instant());
    }

    public SendLamportResult sendLamportMessage(SendMessageCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        int fromId = Objects.requireNonNull(command.from(), "from must not be null");
        int toId = Objects.requireNonNull(command.to(), "to must not be null");

        ClusterNode sender = cluster.node(fromId);
        ClusterNode receiver = cluster.node(toId);

        if (!sender.isUp()) {
            throw new NodeDownException(fromId);
        }

        ClockNodeService senderService = ensureService(sender);
        String payload = (command.payload() != null && !command.payload().isBlank())
                ? command.payload()
                : "Lamport message";
        long messageId = nextMessageId.incrementAndGet();
        long lamportTime = senderService.sendLamportMessage(toId, messageId, payload);

        boolean receiverAlive = receiver.isUp();
        String deliveryStatus = receiverAlive ? "SENT" : "UNKNOWN";
        String deliveryNote = receiverAlive
                ? "Datagram sent via UDP to port " + receiver.ports().clock() + "; delivery is unacknowledged."
                : "UDP message sent to port " + receiver.ports().clock() + " of crashed node " + toId
                + "; datagram was transmitted over UDP socket but delivery cannot be confirmed in unreliable connectionless protocol.";

        return new SendLamportResult(
                fromId,
                toId,
                messageId,
                lamportTime,
                payload,
                deliveryStatus,
                deliveryNote,
                wallClock.instant()
        );
    }

    public TrafficSessionDto startRandomTraffic(RandomTrafficCommand command) {
        int sec = (command != null && command.seconds() != null)
                ? command.seconds()
                : properties.defaultTrafficSeconds();
        int rate = (command != null && command.messagesPerSecond() != null)
                ? command.messagesPerSecond()
                : properties.defaultMessagesPerSecond();

        if (sec < 1 || sec > properties.maxTrafficSeconds()) {
            throw new InvalidParameterException("seconds",
                    "must be between 1 and " + properties.maxTrafficSeconds() + ", was " + sec);
        }
        if (rate < 1 || rate > properties.maxMessagesPerSecond()) {
            throw new InvalidParameterException("messagesPerSecond",
                    "must be between 1 and " + properties.maxMessagesPerSecond() + ", was " + rate);
        }

        ModuleActionGuard.ActionTicket ticket = guard.begin("Random traffic session (" + sec + "s, " + rate + " msg/s)");
        String sessionId = UUID.randomUUID().toString();

        executor.submit(() -> {
            try (ticket) {
                long intervalNanos = 1_000_000_000L / rate;
                long endTimeNanos = System.nanoTime() + (long) sec * 1_000_000_000L;
                int seq = 0;
                while (System.nanoTime() < endTimeNanos) {
                    List<ClusterNode> live = liveNodes();
                    if (live.size() >= 2) {
                        ClusterNode from = live.get(ThreadLocalRandom.current().nextInt(live.size()));
                        List<ClusterNode> others = live.stream().filter(n -> n.id() != from.id()).toList();
                        if (!others.isEmpty()) {
                            ClusterNode to = others.get(ThreadLocalRandom.current().nextInt(others.size()));
                            try {
                                ClockNodeService svc = ensureService(from);
                                long msgId = nextMessageId.incrementAndGet();
                                svc.sendLamportMessage(to.id(), msgId, "Traffic #" + (++seq) + " from Node " + from.id());
                            } catch (Exception e) {
                                log.debug("Traffic transmission failed: {}", e.getMessage());
                            }
                        }
                    }
                    try {
                        Thread.sleep(intervalNanos / 1_000_000L, (int) (intervalNanos % 1_000_000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("Random traffic session {} encountered error: {}", sessionId, e.getMessage());
            }
        });

        return new TrafficSessionDto(sessionId, sec, rate, "ACCEPTED", wallClock.instant());
    }

    public BerkeleyRoundAcceptedDto runBerkeleyRound(BerkeleyRoundCommand command) {
        long threshold = (command != null && command.outlierThresholdMillis() != null)
                ? command.outlierThresholdMillis()
                : properties.outlierThresholdMillis();

        if (threshold < 0) {
            throw new InvalidParameterException("outlierThresholdMillis",
                    "must be >= 0, was " + threshold);
        }

        List<ClusterNode> live = liveNodes();
        if (live.isEmpty()) {
            throw new IllegalStateException("No live nodes available to coordinate Berkeley round");
        }
        List<Integer> liveIds = live.stream().map(ClusterNode::id).toList();
        int daemonId = ClockSyncRoleSelector.selectTimeDaemon(liveIds);
        ClusterNode daemonNode = cluster.node(daemonId);
        ClockNodeService daemonService = ensureService(daemonNode);

        ModuleActionGuard.ActionTicket ticket = guard.begin("Berkeley round coordinated by Node " + daemonId);
        long roundId = nextRoundId.incrementAndGet();

        executor.submit(() -> {
            try (ticket) {
                BerkeleyRoundResult result = daemonService.runBerkeleyRound(liveIds, threshold);
                this.latestRound = toDto(roundId, result);
            } catch (Exception e) {
                log.error("Berkeley round {} coordination failed: {}", roundId, e.getMessage(), e);
            }
        });

        return new BerkeleyRoundAcceptedDto(roundId, daemonId, threshold, "ACCEPTED", wallClock.instant());
    }

    public BerkeleyRoundDto runBerkeleyRoundDirect(long threshold) {
        if (threshold < 0) {
            throw new InvalidParameterException("outlierThresholdMillis",
                    "must be >= 0, was " + threshold);
        }
        List<ClusterNode> live = liveNodes();
        if (live.isEmpty()) {
            throw new IllegalStateException("No live nodes available to coordinate Berkeley round");
        }
        List<Integer> liveIds = live.stream().map(ClusterNode::id).toList();
        int daemonId = ClockSyncRoleSelector.selectTimeDaemon(liveIds);
        ClusterNode daemonNode = cluster.node(daemonId);
        ClockNodeService daemonService = ensureService(daemonNode);

        ModuleActionGuard.ActionTicket ticket = guard.begin("Berkeley round coordinated by Node " + daemonId);
        try (ticket) {
            long roundId = nextRoundId.incrementAndGet();
            BerkeleyRoundResult result = daemonService.runBerkeleyRound(liveIds, threshold);
            BerkeleyRoundDto dto = toDto(roundId, result);
            this.latestRound = dto;
            return dto;
        }
    }

    public NodeDriftResponseDto updateDrift(int nodeId, NodeDriftUpdateCommand command) {
        ClusterNode node = cluster.node(nodeId);
        if (!node.isUp()) {
            throw new NodeDownException(nodeId);
        }
        ClockDriftModel model = driftModels.get(nodeId);
        if (model == null) {
            ClockSyncProperties.NodeDriftConfig cfg = properties.configFor(nodeId);
            model = new ClockDriftModel(nodeId, cfg.initialOffsetMillis(), cfg.driftRateMsPerSec(), wallClock.instant());
            driftModels.put(nodeId, model);
        }

        if (command != null) {
            long newOffset = command.initialOffsetMillis() != null
                    ? command.initialOffsetMillis()
                    : model.currentOffsetMillis(wallClock.instant());
            double newRate = command.driftRateMsPerSec() != null
                    ? command.driftRateMsPerSec()
                    : model.driftRateMsPerSec();
            model.reset(newOffset, newRate, wallClock.instant());
        }

        NodeDriftSnapshot snap = model.snapshot(wallClock.instant());
        return new NodeDriftResponseDto(
                nodeId,
                snap.offsetMillis(),
                snap.driftRateMsPerSec(),
                snap.simulatedTime().toEpochMilli(),
                true,
                "Physical clock drift is simulated mathematically; all network operations are real UDP."
        );
    }

    public CausalVerificationDto verifyCausalInvariants() {
        CausalVerificationResult result = causalChecker.verify(eventLog);
        List<CausalViolationDto> violationDtos = result.violations().stream()
                .map(v -> new CausalViolationDto(
                        v.nodeId(),
                        v.type().name(),
                        v.actualLamportTime(),
                        v.expectedRelationTime(),
                        v.peerId(),
                        v.message()
                ))
                .toList();

        int retained = eventLog.size();
        long dropped = eventLog.droppedCount();
        String note = dropped > 0
                ? "Log capacity of " + eventLog.capacity() + " reached: oldest " + dropped
                + " events dropped. Verification applies to the retained window."
                : "Log within capacity of " + eventLog.capacity() + " events. All recorded events verified.";

        return new CausalVerificationDto(
                result.totalEventsChecked(),
                result.receiveEventsChecked(),
                result.violationsCount(),
                result.passed(),
                result.summary(),
                violationDtos,
                retained,
                (int) dropped,
                note
        );
    }

    public TimelineResponseDto timeline(int limit) {
        if (limit < 1) {
            throw new InvalidParameterException("limit", "must be greater than or equal to 1");
        }
        if (limit > properties.retainedEventsCapacity()) {
            throw new InvalidParameterException("limit",
                    "must be less than or equal to " + properties.retainedEventsCapacity());
        }

        List<ClockEvent> ordered = eventLog.causallyOrdered();
        List<ClockEvent> selected = ordered.size() <= limit
                ? ordered
                : ordered.subList(ordered.size() - limit, ordered.size());

        List<TimelineEventDto> eventDtos = selected.stream()
                .map(e -> new TimelineEventDto(
                        e.nodeId(),
                        e.type().name(),
                        e.lamportTime(),
                        e.peerId() != 0 ? e.peerId() : null,
                        e.messageId(),
                        e.causedByTime() != 0 ? e.causedByTime() : null,
                        e.description(),
                        e.wallTime()
                ))
                .toList();

        return new TimelineResponseDto(
                limit,
                eventDtos.size(),
                eventLog.size(),
                (int) eventLog.droppedCount(),
                eventDtos
        );
    }

    public ClockEventLog eventLog() {
        return eventLog;
    }

    public ClockDriftModel driftModel(int nodeId) {
        return driftModels.get(nodeId);
    }

    public ModuleActionGuard guard() {
        return guard;
    }

    public ClockSyncProperties properties() {
        return properties;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private BerkeleyRoundDto toDto(long roundId, BerkeleyRoundResult result) {
        if (result == null) {
            return null;
        }
        List<NodeAdjustmentDto> adjustments = result.adjustments().stream()
                .map(a -> new NodeAdjustmentDto(
                        a.nodeId(),
                        a.beforeOffsetMillis(),
                        a.adjustmentMillis(),
                        a.afterOffsetMillis(),
                        a.outlier(),
                        a.rttMillis()
                ))
                .toList();
        return new BerkeleyRoundDto(
                roundId,
                result.daemonNodeId(),
                result.outlierThresholdMillis(),
                result.averageOffsetMillis(),
                result.spreadBeforeMillis(),
                result.spreadAfterMillis(),
                result.participatingNodes(),
                result.outlierNodes(),
                adjustments,
                true,
                "Offsets and drift rates are simulated; UDP messages and Berkeley polling rounds are real network traffic."
        );
    }
}
