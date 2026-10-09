package com.udcf.modules.mapreduce;

import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.EventDraft;
import com.udcf.core.metrics.MetricNames;
import com.udcf.core.module.ModuleBusyException;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.mapreduce.dto.JobDto;
import com.udcf.modules.mapreduce.dto.MapReduceOverviewDto;
import com.udcf.modules.mapreduce.dto.ResultRowDto;
import com.udcf.modules.mapreduce.dto.RunCommand;
import com.udcf.modules.mapreduce.dto.RunDto;
import com.udcf.modules.mapreduce.dto.RunState;
import com.udcf.modules.mapreduce.dto.RunSummaryDto;
import com.udcf.web.InvalidParameterException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * MapReduceModule on real 127.0.0.1 sockets: all three jobs on all three inputs, the run flow,
 * events, status, history, reset, failure, validation, and the sizing proof (C1).
 *
 * <p>Ports: 24501-24505 (five nodes), 24511-24512 (two-node failure test), 24521-24522
 * (reset test), 24531-24532 (sizing proof); all inside Track D's 24501-24599 block.</p>
 */
class MapReduceModuleTest {

    private static final MapReduceModuleProperties PROPS = new MapReduceModuleProperties(262144, 100000, 20, 8192);
    private static final Pattern ALL_CAPS_WORD = Pattern.compile("\\b[A-Z]{2,}\\b");

    private MapReduceModuleHarness h;

    @BeforeEach
    void setUp() {
        h = new MapReduceModuleHarness(5, 24500, MapReduceModuleHarness.wire(4194304), PROPS);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private static RunCommand command(String jobId, InputType input) {
        return new RunCommand(jobId, input, null, null);
    }

    private static Map<String, Long> counts(RunDto run) {
        Map<String, Long> counts = new LinkedHashMap<>();
        run.report().results().forEach(row -> counts.put(row.key(), row.count()));
        return counts;
    }

    private static List<String> sampleLines() throws Exception {
        return RunInputLoader.splitLines(Files.readString(Path.of("src/main/resources/mapreduce/sample-text.txt")));
    }

    @Test
    @DisplayName("overview: id, lab 7, the three jobs and inputs, coordinator 1, five workers, limits, no run, IDLE")
    void overview() {
        MapReduceOverviewDto overview = h.module.overview();

        assertThat(h.module.id()).isEqualTo("mapreduce");
        assertThat(h.module.labNumber()).isEqualTo(7);
        assertThat(h.module.title()).isEqualTo("MapReduce");
        assertThat(overview.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(overview.currentAction()).isNull();
        assertThat(overview.jobs()).extracting(JobDto::id)
                .containsExactly("word-count", "event-category-count", "avg-latency-per-node");
        assertThat(overview.jobs()).extracting(JobDto::title)
                .containsExactly("Word count", "Events per category", "Average latency per node");
        assertThat(overview.inputTypes()).extracting(t -> t.id())
                .containsExactly(InputType.SAMPLE, InputType.UPLOAD, InputType.EVENT_LOG);
        assertThat(overview.coordinatorId()).isEqualTo(1);
        assertThat(overview.workerIds()).containsExactly(1, 2, 3, 4, 5);
        assertThat(overview.limits().uploadMaxBytes()).isEqualTo(262144);
        assertThat(overview.limits().eventLogMaxEvents()).isEqualTo(5000);
        assertThat(overview.limits().taskTimeoutMillis()).isEqualTo(15000);
        assertThat(overview.latestRun()).isNull();
        assertThat(overview.notes()).isNotEmpty();
    }

    @Test
    @DisplayName("word count on the sample: every live worker is started first, R = 5, result equals the in-memory run")
    void sampleWordCount() throws Exception {
        RunDto started = h.module.startRun(command("word-count", InputType.SAMPLE));
        assertThat(started.state()).isEqualTo(RunState.RUNNING);
        assertThat(started.report()).isNull();
        assertThat(started.finishedAt()).isNull();

        RunDto run = h.awaitFinished(started.runId());

        assertThat(run.state()).isEqualTo(RunState.COMPLETED);
        assertThat(run.error()).isNull();
        assertThat(run.coordinatorId()).isEqualTo(1);
        assertThat(run.workerIds()).containsExactly(1, 2, 3, 4, 5);
        assertThat(run.report().reducers()).isEqualTo(5);
        assertThat(run.inputName()).isEqualTo("Bundled sample text");
        assertThat(run.crash()).isNull();
        for (ClusterNode node : h.cluster.nodes()) {
            assertThat(MapReduceNodeService.find(node)).get().satisfies(s -> assertThat(s.isRunning()).isTrue());
        }
        Map<String, String> expected = MapReducePipeline.runLocal(new WordCountJob(), sampleLines(), 5,
                new JobReport("word-count"));
        Map<String, Long> expectedCounts = new LinkedHashMap<>();
        expected.forEach((k, v) -> expectedCounts.put(k, Long.parseLong(v)));
        assertThat(counts(run)).containsExactlyEntriesOf(expectedCounts);
        assertThat(run.report().resultKeys()).isEqualTo(expected.size());
        assertThat(run.report().tasks()).allSatisfy(t -> assertThat(t.attempts()).isEqualTo(1));
        assertThat(run.report().combinerSavingPercent()).isNotNull();
        assertThat(h.module.status()).isEqualTo(ModuleStatus.RUNNING);
    }

    @Test
    @DisplayName("all three jobs run on all three inputs; log jobs on plain text complete empty with an honest notice")
    void allJobsOnAllInputs() {
        h.bus.publish(EventDraft.of("replication", 2, "PUSH_ACKED", 1).withData(Map.of("latencyMillis", 12.0)));
        h.bus.publish(EventDraft.of("replication", 2, "PUSH_ACKED", 2).withData(Map.of("latencyMillis", 18.0)));
        String text = "Lamport clocks order events.\nNodes exchange messages.\n";

        Map<String, RunDto> runs = new LinkedHashMap<>();
        for (String job : List.of("word-count", "event-category-count", "avg-latency-per-node")) {
            runs.put(job + "/SAMPLE", h.runAndWait(command(job, InputType.SAMPLE)));
            runs.put(job + "/UPLOAD", h.runAndWait(new RunCommand(job, InputType.UPLOAD,
                    MapReduceModuleHarness.upload("notes.txt", text), null)));
            runs.put(job + "/EVENT_LOG", h.runAndWait(command(job, InputType.EVENT_LOG)));
        }

        assertThat(runs.values()).allSatisfy(run -> assertThat(run.state()).isEqualTo(RunState.COMPLETED));
        assertThat(runs.get("word-count/UPLOAD").report().resultKeys()).isEqualTo(7);
        assertThat(runs.get("word-count/EVENT_LOG").report().resultKeys()).isPositive();
        for (String job : List.of("event-category-count", "avg-latency-per-node")) {
            for (String input : List.of("SAMPLE", "UPLOAD")) {
                RunDto run = runs.get(job + "/" + input);
                assertThat(run.report().resultKeys()).as(job + "/" + input).isZero();
                assertThat(run.report().pairsEmitted()).isZero();
                assertThat(run.notice()).as(job + "/" + input).isNotBlank();
            }
        }
        assertThat(runs.get("event-category-count/EVENT_LOG").report().results())
                .extracting(ResultRowDto::key).contains("PUSH_ACKED", "CLUSTER_STARTED");
        assertThat(runs.get("avg-latency-per-node/EVENT_LOG").report().results())
                .containsExactly(new ResultRowDto("node-2", "15.00 ms average over 2 events", 2L, 15.0));
        assertThat(runs.get("event-category-count/UPLOAD").notice())
                .isEqualTo("No line in this input is an event log line with a category field, so there was nothing to count.");
        assertThat(runs.get("avg-latency-per-node/SAMPLE").notice())
                .isEqualTo("No line in this input has a node and a measured latency, so no average could be computed.");
        assertThat(runs.get("word-count/EVENT_LOG").report().inputLinesDropped()).isZero();
        assertThat(runs.get("word-count/SAMPLE").report().inputLinesDropped()).isNull();
    }

    @Test
    @DisplayName("an uploaded event log runs the latency job with the same result as the in-memory pipeline")
    void uploadedLogLatency() throws Exception {
        String log = Files.readString(Path.of("src/test/resources/mapreduce/framework-events.log"));

        RunDto run = h.runAndWait(new RunCommand("avg-latency-per-node", InputType.UPLOAD,
                MapReduceModuleHarness.upload("C:\\fakepath\\framework-events.txt", log), null));

        Map<String, String> expected = MapReducePipeline.runLocal(new LatencyPerNodeJob(),
                RunInputLoader.splitLines(log), 5, new JobReport("avg-latency-per-node"));
        List<ResultRowDto> expectedRows = new ArrayList<>();
        expected.forEach((k, v) -> expectedRows.add(RunReportMapper.row(new LatencyPerNodeJob(), k, v)));
        assertThat(run.state()).isEqualTo(RunState.COMPLETED);
        assertThat(run.inputName()).isEqualTo("framework-events.txt");
        assertThat(run.report().results()).isEqualTo(expectedRows);
        assertThat(run.report().results()).allSatisfy(r -> assertThat(r.averageMillis()).isNotNull());
    }

    @Test
    @DisplayName("the event log is snapshotted before the run publishes anything, so a job never reads its own events")
    void eventLogSnapshot() {
        RunDto run = h.runAndWait(command("event-category-count", InputType.EVENT_LOG));

        assertThat(run.state()).isEqualTo(RunState.COMPLETED);
        assertThat(run.report().results()).extracting(ResultRowDto::key)
                .doesNotContain("JOB_STARTED", "TASK_SENT", "TASK_RECEIVED", "SERVICE_STARTED");
        assertThat(run.inputBytes()).isPositive();
    }

    @Test
    @DisplayName("JOB_STARTED and JOB_COMPLETED: module mapreduce, coordinator node, one clock tick each, sentence case, nulls kept, no upload data")
    void jobEvents() {
        RunDto run = h.runAndWait(new RunCommand("word-count", InputType.UPLOAD,
                MapReduceModuleHarness.upload("secret-plan.txt", "zebra quokka"), null));

        List<ClusterEvent> events = h.runEvents(run.runId());
        assertThat(events).extracting(ClusterEvent::type).containsExactly("JOB_STARTED", "JOB_COMPLETED");
        ClusterEvent started = events.get(0);
        ClusterEvent completed = events.get(1);
        assertThat(started.module()).isEqualTo("mapreduce");
        assertThat(started.nodeId()).isEqualTo(1);
        assertThat(started.message()).isEqualTo("Word count job started on 5 workers with an uploaded file");
        assertThat(started.data()).containsEntry("inputType", "UPLOAD").containsEntry("reducers", 5)
                .containsKey("crashWorkerId");
        assertThat(started.data().get("crashWorkerId")).isNull();
        assertThat(completed.message()).startsWith("Word count job completed in ").endsWith(" ms with 2 result keys");
        assertThat(completed.lamportTime()).isGreaterThan(started.lamportTime());
        assertThat(h.cluster.node(1).clock().current()).isGreaterThanOrEqualTo(completed.lamportTime());
        List<Long> node1Times = h.bus.query(null, 1, 5000).stream().map(ClusterEvent::lamportTime).toList();
        assertThat(node1Times).doesNotHaveDuplicates();
        for (ClusterEvent e : h.moduleEvents()) {
            assertThat(e.message()).doesNotContainPattern(ALL_CAPS_WORD);
            assertThat(e.toString()).doesNotContain("secret-plan").doesNotContain("zebra").doesNotContain("quokka");
        }
    }

    @Test
    @DisplayName("metrics: map and reduce tasks per worker, the run per coordinator and its duration, all tagged node_id")
    void metrics() {
        h.runAndWait(command("word-count", InputType.SAMPLE));

        double mapTasks = h.meters.find(MetricNames.MAP_TASKS_TOTAL).counters().stream()
                .mapToDouble(c -> c.count()).sum();
        assertThat(mapTasks).isEqualTo(5.0);
        assertThat(h.meters.get(MetricNames.MAPREDUCE_JOBS_TOTAL).tags(MetricNames.NODE_ID, "1", "outcome", "completed")
                .counter().count()).isEqualTo(1.0);
        assertThat(h.meters.get(MetricNames.MAPREDUCE_JOB_DURATION).timer().count()).isEqualTo(1);
        assertThat(h.meters.find(MetricNames.MAPREDUCE_TASK_ATTEMPTS_FAILED_TOTAL).counter()).isNull();
        assertThat(h.meters.getMeters()).allSatisfy(m -> assertThat(m.getId().getTag(MetricNames.NODE_ID)).isNotNull());
    }

    @Test
    @DisplayName("a second run while one is active is refused with ModuleBusyException and nothing is recorded")
    void busy() {
        try (var ticket = h.module.guard().begin("test action")) {
            assertThat(h.module.status()).isEqualTo(ModuleStatus.BUSY);
            assertThatThrownBy(() -> h.module.startRun(command("word-count", InputType.SAMPLE)))
                    .isInstanceOf(ModuleBusyException.class);
        }
        assertThat(h.module.runs()).isEmpty();
        assertThat(h.moduleEvents()).isEmpty();
    }

    @Test
    @DisplayName("validation: unknown job, missing or stray upload, and every bad crash worker are refused before anything starts")
    void validation() {
        assertThatThrownBy(() -> h.module.startRun(command("nope", InputType.SAMPLE)))
                .isInstanceOf(UnknownJobException.class).hasMessageContaining("nope");
        assertThatThrownBy(() -> h.module.startRun(command("word-count", InputType.UPLOAD)))
                .isInstanceOfSatisfying(InvalidParameterException.class, e -> assertThat(e.parameter()).isEqualTo("upload"));
        assertThatThrownBy(() -> h.module.startRun(new RunCommand("word-count", InputType.SAMPLE,
                MapReduceModuleHarness.upload("a.txt", "x"), null)))
                .isInstanceOfSatisfying(InvalidParameterException.class, e -> assertThat(e.parameter()).isEqualTo("upload"));
        assertThatThrownBy(() -> h.module.startRun(new RunCommand("word-count", InputType.SAMPLE, null, 9)))
                .isInstanceOf(InvalidParameterException.class).hasMessage("there is no node 9");
        assertThatThrownBy(() -> h.module.startRun(new RunCommand("word-count", InputType.SAMPLE, null, 1)))
                .isInstanceOf(InvalidParameterException.class).hasMessageContaining("is the coordinator");
        h.cluster.crash(4);
        assertThatThrownBy(() -> h.module.startRun(new RunCommand("word-count", InputType.SAMPLE, null, 4)))
                .isInstanceOf(InvalidParameterException.class).hasMessage("node 4 is down; choose a live worker");
        for (int id = 3; id <= 5; id++) {
            h.cluster.crash(id);
        }
        h.cluster.crash(1);
        assertThatThrownBy(() -> h.module.startRun(new RunCommand("word-count", InputType.SAMPLE, null, 2)))
                .isInstanceOf(InvalidParameterException.class).hasMessageContaining("is the coordinator");
        assertThat(h.module.runs()).isEmpty();
        assertThat(h.module.status()).isEqualTo(ModuleStatus.IDLE);
    }

    @Test
    @DisplayName("every node down: NoLiveWorkerException, and the overview shows no coordinator and no workers")
    void noLiveWorker() {
        h.cluster.nodes().forEach(node -> h.cluster.crash(node.id()));

        assertThatThrownBy(() -> h.module.startRun(command("word-count", InputType.SAMPLE)))
                .isInstanceOf(NoLiveWorkerException.class);
        MapReduceOverviewDto overview = h.module.overview();
        assertThat(overview.coordinatorId()).isNull();
        assertThat(overview.workerIds()).isEmpty();
    }

    @Test
    @DisplayName("history: newest first, bounded, unknown ids and an empty history answer UnknownRunException")
    void history() {
        try (MapReduceModuleHarness small = new MapReduceModuleHarness(2, 24510, MapReduceModuleHarness.wire(4194304),
                new MapReduceModuleProperties(1024, 100, 2, 0))) {
            assertThatThrownBy(small.module::latestRun).isInstanceOf(UnknownRunException.class)
                    .hasMessage("No MapReduce run has been started yet");
            String first = small.runAndWait(command("word-count", InputType.EVENT_LOG)).runId();
            String second = small.runAndWait(command("word-count", InputType.EVENT_LOG)).runId();
            String third = small.runAndWait(command("word-count", InputType.EVENT_LOG)).runId();

            assertThat(small.module.runs()).extracting(RunSummaryDto::runId).containsExactly(third, second);
            assertThat(small.module.latestRun().runId()).isEqualTo(third);
            assertThat(small.module.overview().latestRun().runId()).isEqualTo(third);
            assertThatThrownBy(() -> small.module.run(first)).isInstanceOf(UnknownRunException.class);
            RunSummaryDto summary = small.module.runs().get(0);
            assertThat(summary.totalMillis()).isNotNull();
            assertThat(summary.resultKeys()).isNotNull();
            assertThat(summary.crashWorkerId()).isNull();
        }
    }

    @Test
    @DisplayName("a failed run is FAILED with nulls for unmeasured stages, sets ERROR, publishes JOB_FAILED; a completed run or reset clears ERROR")
    void failedRunAndError() {
        // Two nodes, node 2 down: the 5.6 KB sample is one split, larger than this 4 KB wire limit.
        try (MapReduceModuleHarness tiny = new MapReduceModuleHarness(2, 24510, MapReduceModuleHarness.wire(4096),
                new MapReduceModuleProperties(1024, 100, 5, 0))) {
            tiny.cluster.crash(2);

            RunDto failed = tiny.runAndWait(command("word-count", InputType.SAMPLE));

            assertThat(failed.state()).isEqualTo(RunState.FAILED);
            assertThat(failed.error()).isNotBlank();
            assertThat(failed.report().pairsEmitted()).isNull();
            assertThat(failed.report().resultKeys()).isNull();
            assertThat(failed.report().timings().totalMillis()).isNull();
            assertThat(failed.report().tasks()).singleElement().satisfies(t -> {
                assertThat(t.completed()).isFalse();
                assertThat(t.workerId()).isNull();
            });
            assertThat(tiny.module.status()).isEqualTo(ModuleStatus.ERROR);
            assertThat(tiny.runEvents(failed.runId())).extracting(ClusterEvent::type)
                    .containsExactly("JOB_STARTED", "JOB_FAILED");
            assertThat(tiny.meters.get(MetricNames.MAPREDUCE_JOBS_TOTAL).tags("outcome", "failed").counter().count())
                    .isEqualTo(1.0);
            assertThat(tiny.meters.find(MetricNames.MAPREDUCE_JOB_DURATION).timer()).isNull();

            RunDto completed = tiny.runAndWait(command("word-count", InputType.EVENT_LOG));
            assertThat(completed.state()).isEqualTo(RunState.COMPLETED);
            assertThat(tiny.module.status()).isEqualTo(ModuleStatus.RUNNING);

            tiny.runAndWait(command("word-count", InputType.SAMPLE));
            assertThat(tiny.module.status()).isEqualTo(ModuleStatus.ERROR);
            tiny.module.reset();
            assertThat(tiny.module.status()).isNotEqualTo(ModuleStatus.ERROR);
            assertThat(tiny.module.runs()).isEmpty();
        }
    }

    @Test
    @DisplayName("reset while a run is active: the run finishes and publishes its events, but its record is dropped")
    void resetDuringRun() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try (MapReduceModuleHarness held = new MapReduceModuleHarness(2, 24520, MapReduceModuleHarness.wire(4194304),
                PROPS, executor)) {
            executor.execute(() -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            RunDto started = held.module.startRun(command("word-count", InputType.EVENT_LOG));
            assertThat(held.module.status()).isEqualTo(ModuleStatus.BUSY);
            assertThat(held.module.run(started.runId()).state()).isEqualTo(RunState.RUNNING);

            held.module.reset();
            release.countDown();

            await().atMost(MapReduceModuleHarness.RUN_TIMEOUT).until(() -> held.module.status() != ModuleStatus.BUSY);
            assertThat(held.module.runs()).isEmpty();
            assertThatThrownBy(() -> held.module.run(started.runId())).isInstanceOf(UnknownRunException.class);
            assertThat(held.runEvents(started.runId())).extracting(ClusterEvent::type)
                    .containsExactly("JOB_STARTED", "JOB_COMPLETED");
        }
    }

    @Test
    @DisplayName("close stops the run thread; a run requested afterwards is refused and leaves nothing behind")
    void closeStopsRunThread() {
        h.module.close();

        assertThatThrownBy(() -> h.module.startRun(command("word-count", InputType.SAMPLE)))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat(h.module.runs()).isEmpty();
        assertThat(h.module.status()).isNotEqualTo(ModuleStatus.BUSY);
    }

    @Test
    @DisplayName("C1 sizing proof: an adversarial upload at exactly the public cap on one live worker completes")
    void adversarialUploadAtCapOnOneWorker() {
        // Public-profile numbers: cap 262144 = max-request-bytes 1048576 / 4.
        StringBuilder text = new StringBuilder(262144);
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        int words = 0;
        outer:
        for (char a : alphabet.toCharArray()) {
            for (char b : alphabet.toCharArray()) {
                for (char c : alphabet.toCharArray()) {
                    for (char d : alphabet.toCharArray()) {
                        if (text.length() + 4 > 262144) {
                            break outer;
                        }
                        if (!text.isEmpty()) {
                            text.append(' ');
                        }
                        text.append(a).append(b).append(c).append(d);
                        words++;
                    }
                }
            }
        }
        assertThat(text.toString().getBytes(StandardCharsets.UTF_8)).hasSize(262144);
        MapReduceModuleProperties publicProps = new MapReduceModuleProperties(262144, 200, 20, 8192);

        try (MapReduceModuleHarness worst = new MapReduceModuleHarness(2, 24530, MapReduceModuleHarness.wire(1048576),
                publicProps)) {
            worst.cluster.crash(2);   // one live worker: the whole input is one split

            RunDto run = worst.runAndWait(new RunCommand("word-count", InputType.UPLOAD,
                    MapReduceModuleHarness.upload("worst-case.txt", text.toString()), null));

            assertThat(run.error()).isNull();
            assertThat(run.state()).isEqualTo(RunState.COMPLETED);
            assertThat(run.workerIds()).containsExactly(1);
            assertThat(run.report().splits()).isEqualTo(1);
            assertThat(run.report().resultKeys()).isEqualTo(words);
            assertThat(run.report().resultsTruncated()).isTrue();
            assertThat(run.report().results()).hasSize(200);
        }
    }
}
