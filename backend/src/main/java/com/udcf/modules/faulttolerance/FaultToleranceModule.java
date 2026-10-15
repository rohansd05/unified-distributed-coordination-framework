package com.udcf.modules.faulttolerance;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.module.ExperimentModule;
import com.udcf.core.module.ModuleActionGuard;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.election.ElectionProperties;
import com.udcf.modules.faulttolerance.dto.CrashDto;
import com.udcf.modules.faulttolerance.dto.DataLossDto;
import com.udcf.modules.faulttolerance.dto.FaultToleranceOverviewDto;
import com.udcf.modules.faulttolerance.dto.FaultToleranceStatusDto;
import com.udcf.modules.faulttolerance.dto.GlossaryEntryDto;
import com.udcf.modules.faulttolerance.dto.ModeCommand;
import com.udcf.modules.faulttolerance.dto.RecoverDto;
import com.udcf.modules.faulttolerance.dto.RunDto;
import com.udcf.modules.faulttolerance.dto.SettingsDto;
import com.udcf.modules.faulttolerance.dto.StartDto;
import com.udcf.modules.faulttolerance.dto.StreamCommand;
import com.udcf.modules.faulttolerance.dto.StreamDto;
import com.udcf.modules.replication.ConsistencyModel;
import com.udcf.modules.replication.ReplicationProperties;
import com.udcf.web.InvalidParameterException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Experiment 8 (lab 8) on the shared cluster: a continuous client update stream through the
 * replication primary, a crash of that primary, the failover E8b runs on the shared failure
 * detector (link L2), the recovery of the old primary, and the four measurements (detection,
 * failover, recovery, data loss). The engine is E8b's {@link FailoverCluster}; the new primary
 * comes from its {@link FailoverRoleSelector} ({@code // TODO(L1)}).
 *
 * <p><b>Guard.</b> Start, stream start, crash and recover hold the {@link ModuleActionGuard} for
 * their synchronous part only; every check runs before the guard is taken, so a refused request
 * changes nothing. The stream itself never holds it: crashing the primary while it runs is the
 * point. Status is BUSY only while the guard is held (not during a failover: a failover with no
 * candidate never ends, and would block the cluster reset).</p>
 *
 * <p><b>Measurements.</b> Data loss is measured once per run, against the acknowledged updates,
 * on the first update the new primary accepts (the stream's outcome, on the client thread). The
 * recovery interval is published when the old primary has resynchronised: on its
 * {@code RESYNCHRONISED} event, and again at every stream outcome and module action, because
 * E8b publishes that event just before it records the instant.</p>
 *
 * <p><b>Reads record nothing.</b> {@link #currentState()}, {@link #overview()} and the run reads
 * run the no-two-primaries check and the live data-loss check, but publish no event and change
 * no counter. Distinct violations are recorded at a stream outcome, at a stream stop, at a
 * restore and at a recovery; {@code SPLIT_BRAIN_CHECKED} is published at the last three and when
 * a violation is first seen.</p>
 *
 * <p><b>Events</b> (module {@value #ID}; node events carry the node's Lamport tick, node 0 the
 * cluster clock, as {@link FailoverCluster#publish}): STREAM_STARTED, STREAM_STOPPED,
 * MODE_CHANGED, OLD_PRIMARY_RECOVERED, FAILOVER_MEASURED, DATA_LOSS_MEASURED, RECOVERY_MEASURED,
 * SPLIT_BRAIN_CHECKED. None per update.</p>
 *
 * <p><b>Reset.</b> Stops the stream without an event (the cluster reset clears the history),
 * gives a fresh update budget, forgets the measurements and the violations, then resets the
 * {@link FailoverCluster}. Sequence numbers go on (see {@link UpdateStream}).</p>
 *
 * <p>Covered by FaultToleranceModuleTest, FaultToleranceControllerTest and FaultToleranceEndToEndTest.</p>
 */
@Component
public class FaultToleranceModule implements ExperimentModule, AutoCloseable {

    public static final String ID = FaultToleranceNodeService.MODULE;
    public static final String TITLE = "Fault Tolerance";
    static final String STARTED = "STARTED";
    static final String ALREADY_STARTED = "ALREADY_STARTED";

    static final String ASYNC_DELAY_REASON = "Asynchronous updates wait this long before they are pushed to the "
            + "backups. The delay is simulated: it stands in for network latency.";
    static final String CO_HOSTING_REASON = "All nodes run in one backend process. Their messages cross real "
            + "sockets, but over the loopback interface, not a network.";

    static final List<GlossaryEntryDto> GLOSSARY = List.of(
            new GlossaryEntryDto("detection", "Detection time", "measured",
                    "From the crash of the primary to the first moment any live node's failure detector suspected it."),
            new GlossaryEntryDto("failover", "Failover time", "measured",
                    "From that detection to the moment the new primary started acting as primary, after its "
                            + "catch-up and term record."),
            new GlossaryEntryDto("serviceRestored", "Time to first update", "measured",
                    "From the promotion to the first client update the new primary accepted."),
            new GlossaryEntryDto("outage", "Outage", "measured",
                    "From the crash of the primary to the first client update the new primary accepted, measured "
                            + "directly rather than added up."),
            new GlossaryEntryDto("recovery", "Recovery time", "measured",
                    "From the old primary coming back to the moment it had demoted itself and copied the new "
                            + "primary's store."),
            new GlossaryEntryDto("dataLoss", "Data loss", "measured",
                    "Updates the client was told had succeeded that the new primary does not hold at that version "
                            + "or newer, checked when the service is restored."),
            new GlossaryEntryDto("expectedDetection", "Expected detection window", "derived",
                    "Between the heartbeat timeout minus one interval and the timeout plus one interval after the "
                            + "crash: the last heartbeat may arrive up to one interval before the crash, and silence is "
                            + "checked once per interval. From the failure detector's settings; not a measurement."),
            new GlossaryEntryDto("worstCaseFailover", "Worst-case failover", "derived",
                    "Detection plus two promotion attempts, from the configured timeouts. The client keeps retrying "
                            + "at least this long."));

    private static final Logger log = LoggerFactory.getLogger(FaultToleranceModule.class);

    private final Cluster cluster;
    private final FaultToleranceProperties properties;
    private final FaultToleranceModuleProperties moduleProperties;
    private final ElectionProperties electionProperties;
    private final ReplicationProperties replicationProperties;
    private final FailoverCluster failover;
    private final FaultToleranceMetrics metrics;
    private final UpdateStream stream;
    private final ModuleActionGuard guard = new ModuleActionGuard(ID);
    private final ClusterEventBus.Subscription subscription;
    private final List<String> notes;
    private final Object stateLock = new Object();

    // guarded by stateLock
    private long session;
    private final Map<Long, DataLossDto> capturedLoss = new HashMap<>();
    private final Set<Long> recoveryPublished = new HashSet<>();
    private final Set<String> violationsSeen = new HashSet<>();

    @Autowired
    public FaultToleranceModule(Cluster cluster, ClusterEventBus bus, FaultToleranceProperties properties,
                                FaultToleranceModuleProperties moduleProperties, ElectionProperties electionProperties,
                                ReplicationProperties replicationProperties, MeterRegistry registry, Clock clock) {
        this(cluster, bus, properties, moduleProperties, electionProperties, replicationProperties, registry, clock,
                new FailoverCluster(cluster, properties, electionProperties, replicationProperties, bus, registry));
    }

    /** For tests: a {@link FailoverCluster} built by the test (FaultToleranceModuleHarness). */
    FaultToleranceModule(Cluster cluster, ClusterEventBus bus, FaultToleranceProperties properties,
                         FaultToleranceModuleProperties moduleProperties, ElectionProperties electionProperties,
                         ReplicationProperties replicationProperties, MeterRegistry registry, Clock clock,
                         FailoverCluster failover) {
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
        Objects.requireNonNull(bus, "bus must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.moduleProperties = Objects.requireNonNull(moduleProperties, "moduleProperties must not be null");
        this.electionProperties = Objects.requireNonNull(electionProperties, "electionProperties must not be null");
        this.replicationProperties = Objects.requireNonNull(replicationProperties,
                "replicationProperties must not be null");
        this.failover = Objects.requireNonNull(failover, "failover must not be null");
        this.metrics = new FaultToleranceMetrics(registry);
        this.stream = new UpdateStream(failover::submit, new StreamListener(), moduleProperties.stream().maxUpdates(),
                clock, System::nanoTime);
        this.notes = notes(replicationProperties.asyncDelayMillis());
        this.subscription = bus.subscribe(this::onClusterEvent);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int labNumber() {
        return 8;
    }

    @Override
    public String title() {
        return TITLE;
    }

    @Override
    public ModuleStatus status() {
        if (guard.isBusy()) {
            return ModuleStatus.BUSY;
        }
        return stream.snapshot().running() || failover.snapshot().started() ? ModuleStatus.RUNNING : ModuleStatus.IDLE;
    }

    @Override
    public void reset() {
        synchronized (stateLock) {
            session++;
            capturedLoss.clear();
            recoveryPublished.clear();
            violationsSeen.clear();
        }
        stream.reset();
        failover.reset();
    }

    // ------------------------------------------------------------------ reads (record nothing, publish nothing)

    public FaultToleranceOverviewDto overview() {
        Optional<FailoverRun> latest = failover.machine().latestRun();
        return new FaultToleranceOverviewDto(currentState(), latest.map(this::runDto).orElse(null), settings(),
                GLOSSARY, notes);
    }

    public FaultToleranceStatusDto currentState() {
        FailoverSnapshot snapshot = failover.snapshot();
        Integer serving = failover.currentPrimary().orElse(null);
        Long primaryEpoch = serving != null && serving.equals(snapshot.primaryId()) ? snapshot.primaryEpoch() : null;
        if (primaryEpoch == null) {
            serving = null;
        }
        // null while no primary store exists (DataLoss reports NOTHING_ACKNOWLEDGED first, even without one)
        DataLossReport loss = serving == null ? null : failover.dataLoss();
        DataLossDto lossNow = loss == null || loss.notAssessed() == DataLossReport.NotAssessed.NO_PRIMARY_STORE
                ? null : FaultToleranceDtoMapper.dataLoss(loss, ASYNC_DELAY_REASON);
        int seen;
        synchronized (stateLock) {
            seen = violationsSeen.size();
        }
        return new FaultToleranceStatusDto(status(), guard.currentAction().orElse(null), snapshot.started(),
                snapshot.phase().name(), serving, primaryEpoch, snapshot.highestEpoch(),
                snapshot.nodes().stream().map(FaultToleranceDtoMapper::node).toList(),
                FaultToleranceDtoMapper.stream(stream.snapshot()),
                FaultToleranceDtoMapper.splitBrain(failover.splitBrain(), seen), lossNow,
                snapshot.acknowledgedUpdates(), snapshot.rejectedEventCount());
    }

    /** The kept runs, newest first. */
    public List<RunDto> runs() {
        List<RunDto> runs = new ArrayList<>(failover.machine().runs().stream().map(this::runDto).toList());
        Collections.reverse(runs);
        return runs;
    }

    /** @throws UnknownFailoverRunException (null id) if no run is kept */
    public RunDto latestRun() {
        return failover.machine().latestRun().map(this::runDto).orElseThrow(() -> new UnknownFailoverRunException(null));
    }

    /** @throws UnknownFailoverRunException if no kept run has this id */
    public RunDto run(long runId) {
        return failover.machine().runs().stream().filter(run -> run.runId() == runId).findFirst().map(this::runDto)
                .orElseThrow(() -> new UnknownFailoverRunException(runId));
    }

    // ------------------------------------------------------------------ actions

    /** Starts every service on every live node and appoints the first primary (asynchronously). Idempotent. */
    public StartDto start() {
        boolean started;
        try (ModuleActionGuard.ActionTicket ignored = guard.begin("Start of the fault tolerance experiment")) {
            started = startServices();
        }
        return new StartDto(started ? STARTED : ALREADY_STARTED,
                started ? null : "Experiment 8 is already running; nothing changed.", currentState());
    }

    /**
     * Starts the update stream, starting Experiment 8 first if needed (inside the same guard: the
     * guard is taken once, and {@link #startServices()} takes none).
     *
     * @throws InvalidParameterException       a missing model or rate, or a rate above the limit (400)
     * @throws FaultToleranceConflictException a stream runs, or the session's budget is used (409)
     */
    public StreamDto startStream(StreamCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.mode() == null) {
            throw new InvalidParameterException("mode", "must not be null");
        }
        int max = moduleProperties.stream().maxRatePerSecond();
        if (command.ratePerSecond() == null || command.ratePerSecond() < 1 || command.ratePerSecond() > max) {
            throw new InvalidParameterException("ratePerSecond", "must be between 1 and " + max);
        }
        UpdateStream.Snapshot before = stream.snapshot();
        if (before.running()) {
            throw streamRunning();
        }
        if (before.remainingUpdates() == 0) {
            throw limitReached();
        }
        UpdateStream.StartResult result;
        try (ModuleActionGuard.ActionTicket ignored = guard.begin("Start of the update stream")) {
            startServices();
            result = stream.start(command.mode(), command.ratePerSecond());
        }
        switch (result) {
            case ALREADY_RUNNING -> throw streamRunning();
            case LIMIT_REACHED -> throw limitReached();
            case STARTED -> {
            }
        }
        UpdateStream.Snapshot started = stream.snapshot();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", command.mode().name());
        data.put("ratePerSecond", command.ratePerSecond());
        data.put("maxUpdates", started.maxUpdates());
        data.put("remainingUpdates", started.remainingUpdates());
        failover.publish(0, "STREAM_STARTED", null, "Update stream started: " + modeWord(command.mode()) + " updates, "
                + command.ratePerSecond() + " per second", data);
        publishRecoveryIfMeasured();
        return FaultToleranceDtoMapper.stream(started);
    }

    /** @throws FaultToleranceConflictException if no stream runs (409) */
    public StreamDto stopStream() {
        if (!stream.stop()) {
            throw new FaultToleranceConflictException(FaultToleranceConflictException.Reason.STREAM_NOT_RUNNING,
                    "No update stream is running.");
        }
        return FaultToleranceDtoMapper.stream(stream.snapshot());
    }

    /** The model of the next updates, whether a stream runs or not. */
    public StreamDto setMode(ModeCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.mode() == null) {
            throw new InvalidParameterException("mode", "must not be null");
        }
        ConsistencyModel previous = stream.setMode(command.mode());
        if (previous != command.mode()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("mode", command.mode().name());
            data.put("previousMode", previous.name());
            failover.publish(0, "MODE_CHANGED", null, "Next updates are " + modeWord(command.mode()), data);
        }
        return FaultToleranceDtoMapper.stream(stream.snapshot());
    }

    /**
     * Crashes the serving primary on every protocol (the crash instant is stamped ACTION). Blocks
     * while the node's services close and join their threads.
     *
     * @throws FaultToleranceConflictException if no primary serves (409)
     */
    public CrashDto crashPrimary() {
        if (failover.currentPrimary().isEmpty()) {
            throw noServingPrimary();
        }
        int nodeId;
        long epoch;
        try (ModuleActionGuard.ActionTicket ignored = guard.begin("Crash of the primary")) {
            Optional<Integer> primary = failover.currentPrimary();
            if (primary.isEmpty() || failover.machine().primaryEpoch().isEmpty()) {
                throw noServingPrimary();
            }
            epoch = failover.machine().primaryEpoch().getAsLong();
            try {
                nodeId = failover.crashPrimary();
            } catch (IllegalStateException e) {
                throw noServingPrimary();
            }
        }
        return new CrashDto(nodeId, epoch, InstantSource.ACTION.name(), currentState());
    }

    /**
     * Recovers the old primary of the latest run; it rejoins on its own.
     *
     * @throws FaultToleranceConflictException if no primary failed since the reset, or it is already up (409)
     */
    public RecoverDto recoverOldPrimary() {
        FailoverRun run = failover.machine().latestRun().orElseThrow(() -> new FaultToleranceConflictException(
                FaultToleranceConflictException.Reason.NOTHING_TO_RECOVER,
                "No primary has failed since the last reset, so there is nothing to recover."));
        int nodeId = run.oldPrimaryId();
        if (cluster.node(nodeId).isUp()) {
            throw alreadyUp(nodeId);
        }
        boolean recovered;
        try (ModuleActionGuard.ActionTicket ignored = guard.begin("Recovery of the old primary")) {
            recovered = cluster.recover(nodeId);
        }
        if (!recovered) {
            throw alreadyUp(nodeId);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", run.runId());
        data.put("previousEpoch", run.oldEpoch());
        failover.publish(nodeId, "OLD_PRIMARY_RECOVERED", null, "Node " + nodeId + ", primary at epoch "
                + run.oldEpoch() + " before its crash, is back and asks the cluster for its role", data);
        publishRecoveryIfMeasured();
        return new RecoverDto(nodeId, run.oldEpoch(), currentState());
    }

    // ------------------------------------------------------------------ measurements (stream and bus threads)

    /** Client thread: one update ended. */
    private void updateEnded(UpdateOutcome outcome) {
        metrics.updateFinished(outcome);
        if (record(failover.splitBrain())) {
            publishSplitBrain("a new violation");
        }
        if (outcome.accepted()) {
            captureRestore();
        }
        publishRecoveryIfMeasured();
    }

    /** The first accepted update of a restored run: measures its data loss once and publishes the run's figures. */
    private void captureRestore() {
        FailoverRun run = failover.machine().latestRun().orElse(null);
        if (run == null || run.outcome() != FailoverRun.Outcome.RESTORED || run.newPrimaryId() == null) {
            return;
        }
        long captureSession;
        synchronized (stateLock) {
            if (capturedLoss.containsKey(run.runId())) {
                return;
            }
            captureSession = session;
        }
        DataLossReport report = failover.dataLoss();
        DataLossDto dto = FaultToleranceDtoMapper.dataLoss(report, ASYNC_DELAY_REASON);
        synchronized (stateLock) {
            if (captureSession != session || capturedLoss.putIfAbsent(run.runId(), dto) != null) {
                return;
            }
            Set<Long> kept = failover.machine().runs().stream().map(FailoverRun::runId).collect(Collectors.toSet());
            capturedLoss.keySet().retainAll(kept);
        }
        int newPrimary = run.newPrimaryId();
        metrics.dataLossMeasured(newPrimary, report);
        FailoverMeasurements m = run.measurements();
        Map<String, Object> intervals = runData(run);
        intervals.put("detectionMillis", m.detectionMillis());
        intervals.put("failoverMillis", m.failoverMillis());
        intervals.put("serviceRestoredMillis", m.serviceRestoredMillis());
        intervals.put("outageMillis", m.outageMillis());
        intervals.put("recoveryMillis", m.recoveryMillis());
        failover.publish(newPrimary, "FAILOVER_MEASURED", run.oldPrimaryId(), "Failover from node " + run.oldPrimaryId()
                + " to node " + newPrimary + " measured: outage " + millis(m.outageMillis()), intervals);
        Map<String, Object> loss = runData(run);
        loss.put("acknowledged", report.acknowledged());
        loss.put("lost", report.lost());
        loss.put("lostSynchronous", report.lostSynchronous());
        loss.put("lostAsynchronous", report.lostAsynchronous());
        loss.put("simulated", report.simulated());
        loss.put("simulatedDelayMillis", report.simulated() ? report.simulatedDelayMillis() : null);
        loss.put("notAssessed", report.notAssessed() == null ? null : report.notAssessed().name());
        failover.publish(newPrimary, "DATA_LOSS_MEASURED", null, report.assessed()
                ? "Node " + newPrimary + " lacks " + report.lost() + " of " + report.acknowledged()
                + " acknowledged updates"
                : "Data loss could not be assessed on node " + newPrimary, loss);
        publishSplitBrain("the service was restored");
    }

    /** Publishes the latest run's recovery interval once it is recorded. */
    private void publishRecoveryIfMeasured() {
        FailoverRun run = failover.machine().latestRun().orElse(null);
        if (run == null || run.measurements().recoveryMillis() == null) {
            return;
        }
        synchronized (stateLock) {
            if (!recoveryPublished.add(run.runId())) {
                return;
            }
        }
        Map<String, Object> data = runData(run);
        data.put("recoveryMillis", run.measurements().recoveryMillis());
        failover.publish(run.oldPrimaryId(), "RECOVERY_MEASURED", null, "Node " + run.oldPrimaryId()
                + " recovered as a backup in " + millis(run.measurements().recoveryMillis()), data);
        publishSplitBrain("the old primary recovered");
    }

    /** Records the report's distinct violations; true if any is new. */
    private boolean record(SplitBrainReport report) {
        boolean anyNew = false;
        for (SplitBrainViolation violation : report.violations()) {
            String key = violation.kind() + "|" + violation.nodeIds() + "|" + violation.epoch();
            boolean added;
            synchronized (stateLock) {
                added = violationsSeen.add(key);
            }
            if (added) {
                anyNew = true;
                log.warn("Experiment 8: {}", FaultToleranceDtoMapper.violationMessage(violation));
            }
        }
        return anyNew;
    }

    /** Runs the check, records its violations and publishes SPLIT_BRAIN_CHECKED. */
    private void publishSplitBrain(String when) {
        SplitBrainReport report = failover.splitBrain();
        record(report);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("checkedWhen", when);
        data.put("passed", report.passed());
        data.put("livePrimaries", report.livePrimaries());
        data.put("highestEpoch", report.highestEpoch());
        List<Map<String, Object>> violations = new ArrayList<>();
        for (SplitBrainViolation v : report.violations()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("kind", v.kind().name());
            one.put("nodeIds", v.nodeIds());
            one.put("epoch", v.epoch());
            one.put("highestEpoch", v.highestEpoch());
            violations.add(one);
        }
        data.put("violations", violations);
        String message;
        if (!report.passed()) {
            message = "Single-primary rule broken when " + when + ": "
                    + FaultToleranceDtoMapper.violationMessage(report.violations().get(0));
        } else if (report.livePrimaries().isEmpty()) {
            message = "No two primaries when " + when + ": no node acts as primary";
        } else {
            message = "No two primaries when " + when + ": node " + report.livePrimaries().get(0)
                    + " is the only primary";
        }
        failover.publish(0, "SPLIT_BRAIN_CHECKED", null, message, data);
    }

    /** Event dispatcher thread. */
    private void onClusterEvent(ClusterEvent event) {
        if (ID.equals(event.module()) && "RESYNCHRONISED".equals(event.type())) {
            try {
                publishRecoveryIfMeasured();
            } catch (RuntimeException e) {
                log.warn("Experiment 8: could not publish the recovery interval", e);
            }
        }
    }

    private final class StreamListener implements UpdateStream.Listener {

        @Override
        public void onOutcome(UpdateOutcome outcome) {
            try {
                updateEnded(outcome);
            } catch (RuntimeException e) {
                log.warn("Experiment 8: could not record the outcome of update {}", outcome.update().key(), e);
            }
        }

        @Override
        public void onStopped(UpdateStream.Snapshot snapshot) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reason", snapshot.stopReason().name());
            data.put("sent", snapshot.sent());
            data.put("acknowledged", snapshot.acknowledged());
            data.put("gaveUp", snapshot.gaveUp());
            data.put("remainingUpdates", snapshot.remainingUpdates());
            String why = switch (snapshot.stopReason()) {
                case REQUESTED -> "stopped on request";
                case LIMIT_REACHED -> "stopped: the update budget of this session is used up";
                case ERROR -> "stopped after an error";
            };
            failover.publish(0, "STREAM_STOPPED", null, "Update stream " + why + " after " + snapshot.sent()
                    + " updates", data);
            publishSplitBrain("the stream stopped");
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Starts Experiment 8 if it is not started. Takes no guard: callers hold it. True if it started now. */
    private boolean startServices() {
        boolean before = failover.snapshot().started();
        failover.start();
        return !before;
    }

    private RunDto runDto(FailoverRun run) {
        DataLossDto loss;
        synchronized (stateLock) {
            loss = capturedLoss.get(run.runId());
        }
        return FaultToleranceDtoMapper.run(run, loss);
    }

    private SettingsDto settings() {
        long delay = replicationProperties.asyncDelayMillis();
        return new SettingsDto(moduleProperties.stream().defaultRatePerSecond(),
                moduleProperties.stream().maxRatePerSecond(), moduleProperties.stream().maxUpdates(),
                properties.historyLimit(), electionProperties.heartbeatIntervalMillis(),
                electionProperties.heartbeatTimeoutMillis(), properties.retryWindowMillis(),
                properties.worstCaseFailoverMillis(electionProperties, replicationProperties), delay, delay > 0,
                ASYNC_DELAY_REASON, true, CO_HOSTING_REASON);
    }

    static List<String> notes(long asyncDelayMillis) {
        return List.of(
                "Every update, heartbeat, role query and copy crosses a real socket on 127.0.0.1. The nodes share "
                        + "one process, so the network between them is the loopback interface.",
                "In asynchronous mode the primary waits " + asyncDelayMillis + " ms before pushing each update to "
                        + "the backups. That delay is simulated: it stands in for network latency, and it is the "
                        + "window in which a crash loses updates the client was told had succeeded.",
                "Synchronous mode confirms an update only after every backup push has finished, and the new "
                        + "primary catches up from the other live backups before taking over, so a crash of the "
                        + "primary loses no confirmed update.",
                "Data loss counts only updates the client was told had succeeded. An update still being retried "
                        + "when the primary crashed is not lost; it is sent again to the new primary.",
                "All intervals come from one monotonic clock in the backend. A crash started on the Cluster page "
                        + "is stamped as the node goes down, slightly late, and is marked so.",
                "For now the new primary is the lowest-numbered live node that is ready; from Phase 9A it will be "
                        + "the leader elected by Experiment 4.",
                "Backups refuse any update stamped with an older epoch than the newest they know, so a returning "
                        + "old primary cannot overwrite newer data even if it misbehaves.",
                "Until Phase 9A this experiment and Experiment 5 share each node's replication service, so a "
                        + "write on the Experiment 5 page can move the primary.");
    }

    private FaultToleranceConflictException noServingPrimary() {
        String message;
        if (!failover.snapshot().started()) {
            message = "Experiment 8 has not started yet, so there is no primary to crash. Start it or start the "
                    + "update stream first.";
        } else if (failover.machine().latestRun().map(run -> run.outcome() == FailoverRun.Outcome.IN_PROGRESS)
                .orElse(false)) {
            message = "A failover is in progress: wait until the new primary serves before crashing it.";
        } else {
            message = "No primary serves yet: the first primary is still being appointed.";
        }
        return new FaultToleranceConflictException(FaultToleranceConflictException.Reason.NO_SERVING_PRIMARY, message);
    }

    private static FaultToleranceConflictException alreadyUp(int nodeId) {
        return new FaultToleranceConflictException(FaultToleranceConflictException.Reason.NOTHING_TO_RECOVER,
                "Node " + nodeId + ", the old primary, is already up.");
    }

    private static FaultToleranceConflictException streamRunning() {
        return new FaultToleranceConflictException(FaultToleranceConflictException.Reason.STREAM_RUNNING,
                "An update stream is already running. Stop it first, or change its mode.");
    }

    private FaultToleranceConflictException limitReached() {
        return new FaultToleranceConflictException(FaultToleranceConflictException.Reason.UPDATE_LIMIT_REACHED,
                "This session has used its budget of " + moduleProperties.stream().maxUpdates() + " updates. "
                        + "A cluster reset gives a fresh budget.");
    }

    private static Map<String, Object> runData(FailoverRun run) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", run.runId());
        data.put("oldPrimaryId", run.oldPrimaryId());
        data.put("newPrimaryId", run.newPrimaryId());
        data.put("newEpoch", run.newEpoch());
        return data;
    }

    private static String modeWord(ConsistencyModel model) {
        return model == ConsistencyModel.SYNCHRONOUS ? "synchronous" : "asynchronous";
    }

    private static String millis(Double value) {
        return value == null ? "not measured" : String.format(java.util.Locale.ROOT, "%.1f ms", value);
    }

    /** For tests: the action guard, to hold the module busy. */
    ModuleActionGuard guard() {
        return guard;
    }

    /** For tests: the engine. */
    FailoverCluster failover() {
        return failover;
    }

    /** Stops the stream thread, the bus subscription and the engine's client thread; each wait at most 5 s. */
    @Override
    public void close() {
        subscription.close();
        try {
            stream.close();
        } finally {
            failover.close();
        }
    }
}
