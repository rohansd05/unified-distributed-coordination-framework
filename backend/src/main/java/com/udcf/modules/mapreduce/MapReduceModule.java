package com.udcf.modules.mapreduce;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.core.events.EventLogExporter;
import com.udcf.core.events.EventProperties;
import com.udcf.core.module.ExperimentModule;
import com.udcf.core.module.ModuleActionGuard;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.mapreduce.dto.CrashDto;
import com.udcf.modules.mapreduce.dto.InputTypeDto;
import com.udcf.modules.mapreduce.dto.JobDto;
import com.udcf.modules.mapreduce.dto.JobReportDto;
import com.udcf.modules.mapreduce.dto.MapReduceLimitsDto;
import com.udcf.modules.mapreduce.dto.MapReduceOverviewDto;
import com.udcf.modules.mapreduce.dto.RunCommand;
import com.udcf.modules.mapreduce.dto.RunDto;
import com.udcf.modules.mapreduce.dto.RunState;
import com.udcf.modules.mapreduce.dto.RunSummaryDto;
import com.udcf.web.InvalidParameterException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Experiment 7 (lab 7) on the shared cluster: runs a MapReduce job over TCP workers on
 * {@code ports().mapreduce()} (730k) on the bundled sample, an uploaded .txt file or the
 * cluster's own live event log (link L5), optionally crashing a worker during the run.
 *
 * <p><b>Roles.</b> The coordinator is the lowest live node and every live node is a worker,
 * from {@link MapReduceRoleSelector} ({@code // TODO(L1): replaced by the shared role
 * provider in Phase 9A}). R, the number of reducers, is the number of workers.</p>
 *
 * <p><b>A run.</b> Everything about the request is checked on the request thread before the
 * module's {@link ModuleActionGuard} is taken, so a rejected request changes nothing; a second
 * run while one is active gets HTTP 409. The run itself executes on the module's own run
 * thread ({@value #RUN_THREAD_NAME}), never on the HTTP thread: it reads its input (the event
 * log is snapshotted once, here, before the run publishes anything, so a job never reads its
 * own events), starts the mapreduce service on every live worker (E7b's lazy workers would
 * otherwise look crashed), builds a {@link TcpTaskTransport} for the coordinator and runs
 * {@link MapReducePipeline}. The guard is released in {@code finally}.</p>
 *
 * <p><b>Crash.</b> With a {@code crashWorkerId}, {@link WorkerCrashTrigger} crashes that node
 * through {@code Cluster.crash} right after the first task is sent to it, on the pipeline's
 * attempt thread; the task is retried on another worker and the result is the same. The node
 * stays down; it is recovered on the Cluster page.</p>
 *
 * <p><b>Events</b> (module {@value #ID}, node = coordinator, one tick of the coordinator's
 * Lamport clock each, data with {@code runId}): JOB_STARTED, WORKER_CRASH_TRIGGERED,
 * JOB_COMPLETED, JOB_FAILED; besides E7b's TASK_* events. Neither the uploaded file's content
 * nor its name ever goes into an event or a log line.</p>
 *
 * <p><b>Status.</b> BUSY while a run is active; otherwise ERROR if the last run failed (cleared
 * by the next completed run or by {@link #reset}); otherwise RUNNING while any node's
 * mapreduce service is listening; otherwise IDLE.</p>
 *
 * <p><b>Reset.</b> Forgets the last run and the history and clears ERROR. It never throws. A run
 * still active at that moment finishes and publishes its events (they really happened), but
 * its record is dropped, so the history stays empty and its id answers 404. A cluster reset is
 * refused anyway while the module is BUSY.</p>
 */
@Component
public class MapReduceModule implements ExperimentModule, AutoCloseable {

    public static final String ID = MapReduceNodeService.MODULE;
    public static final String TITLE = "MapReduce";
    static final String RUN_THREAD_NAME = "udcf-mapreduce-run";
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    /** Sentence-case titles for the registry's jobs, in the order the page lists them. */
    static final Map<String, String> JOB_TITLES = orderedTitles();

    static final List<InputTypeDto> INPUT_TYPES = List.of(
            new InputTypeDto(InputType.SAMPLE, RunInputLoader.SAMPLE_NAME,
                    "A short text about distributed systems that ships with the backend."),
            new InputTypeDto(InputType.UPLOAD, "Your own .txt file",
                    "A UTF-8 text file from your computer. It is kept in memory for the run only and never stored."),
            new InputTypeDto(InputType.EVENT_LOG, RunInputLoader.EVENT_LOG_NAME,
                    "The events this cluster has recorded so far, read once when the run starts."));

    static final List<String> NOTES = List.of(
            "Every map and reduce task travels over a real TCP connection to the worker's own port; "
                    + "nothing on this page is simulated.",
            "For now the coordinator is the lowest-numbered live node and every live node is a worker; "
                    + "from Phase 9A the coordinator will be the elected leader.",
            "The combiner adds up values on the mapper's node before anything is sent, so fewer pairs "
                    + "reach the shuffle than the mappers emitted.",
            "The average latency job carries a sum and a count through every stage and divides only at "
                    + "the end, because an average of averages is not the true average.",
            "A crash during a run is injected by this module right after the first task is sent to the "
                    + "chosen worker. It is a real crash of that node on every protocol, and the node stays "
                    + "down until you recover it on the Cluster page.");

    private static final Logger log = LoggerFactory.getLogger(MapReduceModule.class);

    private final Cluster cluster;
    private final ClusterEventBus bus;
    private final MapReduceProperties wire;
    private final MapReduceModuleProperties properties;
    private final int eventLogMaxEvents;
    private final Clock clock;
    private final ExecutorService runExecutor;
    private final JobRegistry registry = JobRegistry.standard();
    private final RunInputLoader inputs;
    private final RunHistory history;
    private final MapReduceMetrics metrics;
    private final ModuleActionGuard guard = new ModuleActionGuard(ID);
    private final Object stateLock = new Object();
    private boolean lastRunFailed;   // guarded by stateLock

    @Autowired
    public MapReduceModule(Cluster cluster, ClusterEventBus bus, MapReduceProperties wire,
                           MapReduceModuleProperties properties, EventLogExporter exporter,
                           EventProperties eventProperties, MeterRegistry meterRegistry, Clock clock) {
        this(cluster, bus, wire, properties, exporter, eventProperties, meterRegistry, clock,
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, RUN_THREAD_NAME);
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    /** For tests: a chosen run executor. */
    MapReduceModule(Cluster cluster, ClusterEventBus bus, MapReduceProperties wire,
                    MapReduceModuleProperties properties, EventLogExporter exporter,
                    EventProperties eventProperties, MeterRegistry meterRegistry, Clock clock,
                    ExecutorService runExecutor) {
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.wire = Objects.requireNonNull(wire, "wire must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        Objects.requireNonNull(eventProperties, "eventProperties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.runExecutor = Objects.requireNonNull(runExecutor, "runExecutor must not be null");
        properties.requireFitsWire(wire);   // fail fast at startup, in every profile
        this.eventLogMaxEvents = eventProperties.bufferSize();
        this.inputs = new RunInputLoader(properties, exporter, eventLogMaxEvents);
        this.history = new RunHistory(properties.runHistorySize());
        this.metrics = new MapReduceMetrics(meterRegistry);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int labNumber() {
        return 7;
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
        synchronized (stateLock) {
            if (lastRunFailed) {
                return ModuleStatus.ERROR;
            }
        }
        boolean listening = cluster.nodes().stream()
                .anyMatch(node -> MapReduceNodeService.find(node).map(MapReduceNodeService::isRunning).orElse(false));
        return listening ? ModuleStatus.RUNNING : ModuleStatus.IDLE;
    }

    @Override
    public void reset() {
        synchronized (stateLock) {
            history.clear();
            lastRunFailed = false;
        }
    }

    // ------------------------------------------------------------------ queries

    public MapReduceOverviewDto overview() {
        Roles roles = currentRoles();
        return new MapReduceOverviewDto(status(), guard.currentAction().orElse(null), jobs(), INPUT_TYPES,
                roles == null ? null : roles.coordinatorId(), roles == null ? List.of() : roles.workerIds(),
                limits(), history.latest().orElse(null), NOTES);
    }

    /** @throws UnknownRunException if no kept run has this id */
    public RunDto run(String runId) {
        return history.find(runId).orElseThrow(() -> new UnknownRunException(runId));
    }

    /** @throws UnknownRunException (with a null id) if no run is kept */
    public RunDto latestRun() {
        return history.latest().orElseThrow(() -> new UnknownRunException(null));
    }

    /** The kept runs, newest first. */
    public List<RunSummaryDto> runs() {
        return history.all().stream().map(MapReduceModule::summary).toList();
    }

    // ------------------------------------------------------------------ action

    /**
     * Starts one run in the background and returns it as RUNNING.
     *
     * @throws InvalidParameterException a missing field, a bad upload or a bad crash worker (HTTP 400)
     * @throws UnknownJobException       an unknown job id (404)
     * @throws NoLiveWorkerException     every node is down (409)
     * @throws com.udcf.core.module.ModuleBusyException another run is active (409)
     * @throws java.util.concurrent.RejectedExecutionException the run could not start; nothing changed
     */
    public RunDto startRun(RunCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.jobId() == null || command.jobId().isBlank()) {
            throw new InvalidParameterException("jobId", "jobId is required");
        }
        if (!registry.contains(command.jobId())) {
            throw new UnknownJobException(command.jobId());
        }
        if (command.inputType() == null) {
            throw new InvalidParameterException("inputType", "inputType is required");
        }
        RunInput upload = null;
        if (command.inputType() == InputType.UPLOAD) {
            upload = inputs.upload(command.upload());
        } else if (command.upload() != null) {
            throw new InvalidParameterException("upload", "must be left out unless inputType is UPLOAD");
        }
        Roles roles = currentRoles();
        if (roles == null) {
            throw new NoLiveWorkerException();
        }
        Integer crashWorkerId = command.crashWorkerId();
        if (crashWorkerId != null) {
            checkCrashWorker(crashWorkerId, roles);
        }

        MapReduceJob job = registry.get(command.jobId());
        RunPlan plan = new RunPlan(UUID.randomUUID().toString().substring(0, 8), job, command.inputType(), upload,
                roles.coordinatorId(), roles.workerIds(), crashWorkerId, clock.instant());
        ModuleActionGuard.ActionTicket ticket = guard.begin("MapReduce run of the "
                + jobTitle(job).toLowerCase(Locale.ROOT) + " job on " + inputPhrase(plan.inputType()));
        RunDto started = runningDto(plan);
        long generation;
        synchronized (stateLock) {
            generation = history.generation();
            history.record(generation, started);
        }
        try {
            runExecutor.execute(() -> {
                try {
                    execute(plan, generation);
                } finally {
                    ticket.close();
                }
            });
        } catch (RuntimeException e) {
            history.remove(plan.runId());
            ticket.close();
            throw e;
        }
        return started;
    }

    // ------------------------------------------------------------------ the run (run thread)

    private void execute(RunPlan plan, long generation) {
        MapReduceJob job = plan.job();
        ClusterNode coordinator = cluster.node(plan.coordinatorId());
        List<String> notices = new ArrayList<>();
        List<Integer> workers = new ArrayList<>();
        RunInput input = null;
        JobReport report = new JobReport(job.name());
        TaskAttemptRecorder recorder = new TaskAttemptRecorder();
        WorkerCrashTrigger trigger = new WorkerCrashTrigger(plan.crashWorkerId(), cluster::crash,
                (workerId, taskType, nodeCrashed) -> publishCrash(plan, coordinator, workerId, taskType, nodeCrashed));
        Map<String, String> results = null;
        Exception failure = null;
        try {
            input = switch (plan.inputType()) {
                case SAMPLE -> inputs.sample();
                case UPLOAD -> plan.upload();
                case EVENT_LOG -> inputs.eventLog();
            };
            if (input.notice() != null) {
                notices.add(input.notice());
            }
            for (int workerId : plan.workerIds()) {
                try {
                    MapReduceNodeService.on(cluster.node(workerId), cluster, registry, wire, bus);
                    workers.add(workerId);
                } catch (RuntimeException e) {
                    notices.add("Node " + workerId + " could not start its worker, so it was left out of this run.");
                }
            }
            publishStarted(plan, coordinator, input, workers);
            if (workers.isEmpty()) {
                throw new IOException("No worker could be started for this run");
            }
            TcpTaskTransport tcp = new TcpTaskTransport(coordinator,
                    trigger.resolver(id -> cluster.node(id).ports().mapreduce()), bus, wire);
            MapReducePipeline pipeline = new MapReducePipeline(workers, trigger.wrap(tcp),
                    Duration.ofMillis(wire.taskTimeoutMillis()), workers.size());
            try {
                results = pipeline.run(job, input.lines(), report, recorder);
            } finally {
                shutdownQuietly(pipeline);
            }
        } catch (IOException | RuntimeException e) {
            failure = e;
            log.warn("MapReduce run {} failed: {}", plan.runId(), messageOf(e));
        }

        boolean completed = failure == null;
        if (plan.crashWorkerId() != null) {
            notices.add(crashNotice(trigger));
        }
        if (completed && results.isEmpty()) {
            String empty = emptyResultNotice(job, input);
            if (empty != null) {
                notices.add(empty);
            }
        }
        JobReportDto reportDto = input == null ? null : RunReportMapper.map(job, report, results, recorder.rows(),
                Math.max(1, workers.size()), input.droppedLines(), properties.resultRowsMax());
        RunDto finished = new RunDto(plan.runId(), completed ? RunState.COMPLETED : RunState.FAILED, job.name(),
                jobTitle(job), plan.inputType(), input == null ? inputName(plan) : input.displayName(),
                input == null ? null : input.bytes(), plan.coordinatorId(),
                input == null ? plan.workerIds() : List.copyOf(workers), crashDto(trigger),
                plan.startedAt(), clock.instant(), reportDto,
                completed ? null : messageOf(failure),
                notices.isEmpty() ? null : String.join(" ", notices));

        metrics.recordRun(job.name(), plan.coordinatorId(), completed, report.mapTasksPerNode(),
                report.reduceTasksPerNode(), completed ? report.totalMillis() : null, recorder.failures());
        if (completed) {
            publishCompleted(plan, coordinator, report, results.size());
        } else {
            publishFailed(plan, coordinator, messageOf(failure));
        }
        synchronized (stateLock) {
            if (history.record(generation, finished)) {
                lastRunFailed = !completed;
            }
        }
    }

    private static void shutdownQuietly(MapReducePipeline pipeline) {
        try {
            pipeline.shutdown();
        } catch (IllegalStateException e) {
            log.warn("MapReduce pipeline threads did not stop: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ validation and roles

    private void checkCrashWorker(int crashWorkerId, Roles roles) {
        if (!roles.workerIds().contains(crashWorkerId)) {
            if (crashWorkerId < 1 || crashWorkerId > cluster.size()) {
                throw new InvalidParameterException("crashWorkerId", "there is no node " + crashWorkerId);
            }
            throw new InvalidParameterException("crashWorkerId",
                    "node " + crashWorkerId + " is down; choose a live worker");
        }
        if (crashWorkerId == roles.coordinatorId()) {
            throw new InvalidParameterException("crashWorkerId",
                    "node " + crashWorkerId + " is the coordinator of this run; choose another live worker");
        }
        if (roles.workerIds().size() < 2) {
            throw new InvalidParameterException("crashWorkerId",
                    "a crash run needs at least two live workers, so the task can be retried elsewhere");
        }
    }

    private record Roles(int coordinatorId, List<Integer> workerIds) {
    }

    /** The current coordinator and workers (TODO(L1)), or {@code null} when every node is down. */
    private Roles currentRoles() {
        try {
            int coordinatorId = MapReduceRoleSelector.selectCoordinator(cluster);
            List<Integer> workers = MapReduceRoleSelector.selectWorkers(cluster);
            return workers.isEmpty() ? null : new Roles(coordinatorId, List.copyOf(workers));
        } catch (IllegalStateException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ DTOs

    private record RunPlan(String runId, MapReduceJob job, InputType inputType, RunInput upload,
                           int coordinatorId, List<Integer> workerIds, Integer crashWorkerId, Instant startedAt) {
    }

    private List<JobDto> jobs() {
        List<String> order = new ArrayList<>(JOB_TITLES.keySet());
        return registry.all().stream()
                .sorted(Comparator.comparingInt((MapReduceJob job) -> {
                    int index = order.indexOf(job.name());
                    return index < 0 ? Integer.MAX_VALUE : index;
                }).thenComparing(MapReduceJob::name))
                .map(job -> new JobDto(job.name(), jobTitle(job), job.description()))
                .toList();
    }

    private MapReduceLimitsDto limits() {
        return new MapReduceLimitsDto(properties.uploadMaxBytes(), properties.requestBodyMaxBytes(),
                properties.uploadMaxBytes(), eventLogMaxEvents, properties.resultRowsMax(),
                properties.runHistorySize(), wire.taskTimeoutMillis());
    }

    private RunDto runningDto(RunPlan plan) {
        Long bytes = switch (plan.inputType()) {
            case SAMPLE -> inputs.sample().bytes();
            case UPLOAD -> plan.upload().bytes();
            case EVENT_LOG -> null;
        };
        CrashDto crash = plan.crashWorkerId() == null ? null : new CrashDto(plan.crashWorkerId(), false, null, null);
        return new RunDto(plan.runId(), RunState.RUNNING, plan.job().name(), jobTitle(plan.job()), plan.inputType(),
                inputName(plan), bytes, plan.coordinatorId(), plan.workerIds(), crash, plan.startedAt(),
                null, null, null, null);
    }

    private static CrashDto crashDto(WorkerCrashTrigger trigger) {
        if (trigger.workerId() == null) {
            return null;
        }
        TaskType type = trigger.taskType();
        return new CrashDto(trigger.workerId(), trigger.triggered(), trigger.nodeCrashed(),
                type == null ? null : type.name());
    }

    private static RunSummaryDto summary(RunDto run) {
        JobReportDto report = run.report();
        return new RunSummaryDto(run.runId(), run.state(), run.jobId(), run.inputType(), run.inputName(),
                run.startedAt(), run.finishedAt(),
                report == null || run.state() != RunState.COMPLETED ? null : report.timings().totalMillis(),
                report == null ? null : report.resultKeys(),
                report == null ? null : report.retriedTasks(),
                run.crash() == null ? null : run.crash().workerId());
    }

    private static String inputName(RunPlan plan) {
        return switch (plan.inputType()) {
            case SAMPLE -> RunInputLoader.SAMPLE_NAME;
            case UPLOAD -> plan.upload().displayName();
            case EVENT_LOG -> RunInputLoader.EVENT_LOG_NAME;
        };
    }

    static String jobTitle(MapReduceJob job) {
        return JOB_TITLES.getOrDefault(job.name(), job.name());
    }

    private static String crashNotice(WorkerCrashTrigger trigger) {
        int workerId = trigger.workerId();
        if (!trigger.triggered()) {
            return "Node " + workerId + " received no task in this run, so it was not crashed.";
        }
        String task = taskWord(trigger.taskType());
        if (Boolean.TRUE.equals(trigger.nodeCrashed())) {
            return "Node " + workerId + " was crashed right after its first " + task + " task was sent, and that "
                    + "task was retried on another worker. The node stays down until you recover it on the "
                    + "Cluster page.";
        }
        return "Node " + workerId + " was already down when its first " + task + " task was sent, so the module "
                + "did not crash it; the task was retried on another worker.";
    }

    /** A plain sentence for a completed run with no result keys, or {@code null} if the input notice already explains it. */
    private static String emptyResultNotice(MapReduceJob job, RunInput input) {
        if (input.type() == InputType.EVENT_LOG && input.lines().isEmpty()) {
            return null;   // the input notice already says there were no events
        }
        if (input.lines().stream().allMatch(String::isBlank)) {
            return "The input has no text, so there was nothing to count.";
        }
        return switch (job.name()) {
            case "word-count" -> "The input contains no words made of letters or digits, so there was nothing to count.";
            case "event-category-count" -> "No line in this input is an event log line with a category field, "
                    + "so there was nothing to count.";
            case "avg-latency-per-node" -> "No line in this input has a node and a measured latency, "
                    + "so no average could be computed.";
            default -> "The job found nothing to report in this input.";
        };
    }

    // ------------------------------------------------------------------ events

    private void publishStarted(RunPlan plan, ClusterNode coordinator, RunInput input, List<Integer> workers) {
        Map<String, Object> data = runData(plan);
        data.put("inputType", plan.inputType().name());
        data.put("inputLines", input.lines().size());
        data.put("inputBytes", input.bytes());
        data.put("coordinatorId", plan.coordinatorId());
        data.put("workerIds", List.copyOf(workers));
        data.put("reducers", workers.size());
        data.put("crashWorkerId", plan.crashWorkerId());
        publish(coordinator, "JOB_STARTED", jobTitle(plan.job()) + " job started on " + workers.size()
                + (workers.size() == 1 ? " worker" : " workers") + " with " + inputPhrase(plan.inputType()), null, data);
    }

    private void publishCompleted(RunPlan plan, ClusterNode coordinator, JobReport report, int resultKeys) {
        Map<String, Object> data = runData(plan);
        data.put("totalMillis", report.totalMillis());
        data.put("resultKeys", resultKeys);
        data.put("retriedTasks", report.failedTasksRetried());
        publish(coordinator, "JOB_COMPLETED", jobTitle(plan.job()) + " job completed in " + report.totalMillis()
                + " ms with " + resultKeys + (resultKeys == 1 ? " result key" : " result keys"), null, data);
    }

    private void publishFailed(RunPlan plan, ClusterNode coordinator, String error) {
        Map<String, Object> data = runData(plan);
        data.put("error", error);
        publish(coordinator, "JOB_FAILED", jobTitle(plan.job()) + " job failed", null, data);
    }

    private void publishCrash(RunPlan plan, ClusterNode coordinator, int workerId, TaskType taskType,
                              boolean nodeCrashed) {
        Map<String, Object> data = runData(plan);
        data.put("workerId", workerId);
        data.put("taskType", taskType == null ? null : taskType.name());
        data.put("nodeCrashed", nodeCrashed);
        String task = taskWord(taskType);
        publish(coordinator, "WORKER_CRASH_TRIGGERED", nodeCrashed
                ? "The module crashed node " + workerId + " right after sending it its first " + task + " task"
                : "Node " + workerId + " was already down when its first " + task + " task was sent", workerId, data);
    }

    private void publish(ClusterNode coordinator, String type, String message, Integer peer, Map<String, Object> data) {
        bus.publish(EventDraft.of(ID, coordinator.id(), type, coordinator.clock().tick())
                .withPeer(peer).withMessage(message).withData(data));
    }

    private static Map<String, Object> runData(RunPlan plan) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", plan.runId());
        data.put("jobId", plan.job().name());
        return data;
    }

    // ------------------------------------------------------------------ helpers

    private static String inputPhrase(InputType type) {
        return switch (type) {
            case SAMPLE -> "the bundled sample text";
            case UPLOAD -> "an uploaded file";
            case EVENT_LOG -> "the live cluster event log";
        };
    }

    private static String taskWord(TaskType type) {
        return type == null ? "" : type.name().toLowerCase(Locale.ROOT);
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static Map<String, String> orderedTitles() {
        Map<String, String> titles = new LinkedHashMap<>();
        titles.put("word-count", "Word count");
        titles.put("event-category-count", "Events per category");
        titles.put("avg-latency-per-node", "Average latency per node");
        return java.util.Collections.unmodifiableMap(titles);
    }

    /** For tests: the action guard, to hold the module busy. */
    ModuleActionGuard guard() {
        return guard;
    }

    /** Stops the run thread; waits at most 5 s. Called by Spring on context shutdown. */
    @Override
    public void close() {
        runExecutor.shutdownNow();
        try {
            if (!runExecutor.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("MapReduce run thread did not stop within " + CLOSE_TIMEOUT.toSeconds() + " s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping the MapReduce run thread", e);
        }
    }
}
