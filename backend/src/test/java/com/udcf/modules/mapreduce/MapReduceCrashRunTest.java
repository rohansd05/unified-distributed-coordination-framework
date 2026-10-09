package com.udcf.modules.mapreduce;

import com.udcf.core.events.ClusterEvent;
import com.udcf.core.metrics.MetricNames;
import com.udcf.modules.mapreduce.dto.CrashDto;
import com.udcf.modules.mapreduce.dto.FailedAttemptDto;
import com.udcf.modules.mapreduce.dto.RunCommand;
import com.udcf.modules.mapreduce.dto.RunDto;
import com.udcf.modules.mapreduce.dto.RunState;
import com.udcf.modules.mapreduce.dto.TaskRowDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Experiment 7's done-when on real sockets: "a worker crash produces a retried task and an
 * identical result". Each test compares the whole result of a crash run with a run without a
 * crash on the same cluster (R = 5 in both, because the crashed node stays in the worker list
 * of the run it was crashed in).
 *
 * <p>Ports 24701-24705 (Track D's crash and retry block 24701-24799).</p>
 */
class MapReduceCrashRunTest {

    private static final MapReduceModuleProperties PROPS = new MapReduceModuleProperties(262144, 100000, 20, 8192);

    private MapReduceModuleHarness h;

    @BeforeEach
    void setUp() {
        h = new MapReduceModuleHarness(5, 24700, MapReduceModuleHarness.wire(4194304), PROPS);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private long lastSequence() {
        List<ClusterEvent> all = h.bus.query(null, null, 5000);
        return all.stream().mapToLong(ClusterEvent::sequence).max().orElse(0);
    }

    /** Events of the mapreduce module published after {@code afterSequence}, in arrival order. */
    private List<ClusterEvent> eventsSince(long afterSequence) {
        return h.moduleEvents().stream().filter(e -> e.sequence() > afterSequence)
                .sorted((a, b) -> Long.compare(a.sequence(), b.sequence())).toList();
    }

    private static List<TaskRowDto> retried(RunDto run) {
        return run.report().tasks().stream().filter(t -> t.attempts() > 1).toList();
    }

    @Test
    @DisplayName("crash during the map stage: the task is retried on another worker and the whole result is identical")
    void crashDuringMap() {
        RunDto baseline = h.runAndWait(new RunCommand("word-count", InputType.SAMPLE, null, null));
        long before = lastSequence();

        RunDto crashed = h.runAndWait(new RunCommand("word-count", InputType.SAMPLE, null, 3));

        assertThat(crashed.state()).isEqualTo(RunState.COMPLETED);
        assertThat(crashed.crash()).isEqualTo(new CrashDto(3, true, true, "MAP"));
        assertThat(h.cluster.node(3).isUp()).isFalse();
        assertThat(crashed.report().results()).isEqualTo(baseline.report().results());
        assertThat(crashed.report().resultsTruncated()).isFalse();
        assertThat(crashed.report().resultKeys()).isEqualTo(baseline.report().resultKeys());
        assertThat(retried(crashed)).isNotEmpty().allSatisfy(t -> {
            assertThat(t.completed()).isTrue();
            assertThat(t.workerId()).isNotEqualTo(3);
            assertThat(t.failedAttempts()).extracting(FailedAttemptDto::workerId).containsOnly(3);
        });
        assertThat(crashed.report().retriedTasks()).isEqualTo(retried(crashed).size());
        assertThat(crashed.notice()).contains("Node 3 was crashed right after its first map task was sent")
                .contains("stays down");

        List<ClusterEvent> events = eventsSince(before);
        assertThat(events.stream().filter(e -> e.type().equals("WORKER_CRASH_TRIGGERED")).toList())
                .singleElement().satisfies(e -> {
                    assertThat(e.peerId()).isEqualTo(3);
                    assertThat(e.data()).containsEntry("taskType", "MAP").containsEntry("nodeCrashed", true);
                    assertThat(e.message()).isEqualTo("The module crashed node 3 right after sending it its first map task");
                });
        long sentTo3 = events.stream().filter(e -> e.type().equals("TASK_SENT") && Integer.valueOf(3).equals(e.peerId()))
                .mapToLong(ClusterEvent::sequence).min().orElseThrow();
        long trigger = events.stream().filter(e -> e.type().equals("WORKER_CRASH_TRIGGERED"))
                .mapToLong(ClusterEvent::sequence).min().orElseThrow();
        long failedOn3 = events.stream()
                .filter(e -> e.type().equals("TASK_ATTEMPT_FAILED") && Integer.valueOf(3).equals(e.peerId()))
                .mapToLong(ClusterEvent::sequence).min().orElseThrow();
        assertThat(sentTo3).isLessThan(trigger);
        assertThat(trigger).isLessThan(failedOn3);
        assertThat(h.bus.query("cluster", 3, 100)).extracting(ClusterEvent::type).contains("NODE_CRASHED");
        assertThat(h.meters.get(MetricNames.MAPREDUCE_TASK_ATTEMPTS_FAILED_TOTAL)
                .tags(MetricNames.NODE_ID, "3", "task_type", "map").counter().count()).isPositive();
    }

    @Test
    @DisplayName("the latency job (sum;count) gives the identical result after a crash")
    void crashWithLatencyJob() throws Exception {
        String log = Files.readString(Path.of("src/test/resources/mapreduce/framework-events.log"));
        RunCommand plain = new RunCommand("avg-latency-per-node", InputType.UPLOAD,
                MapReduceModuleHarness.upload("events.txt", log), null);
        RunDto baseline = h.runAndWait(plain);

        RunDto crashed = h.runAndWait(new RunCommand("avg-latency-per-node", InputType.UPLOAD,
                MapReduceModuleHarness.upload("events.txt", log), 2));

        assertThat(crashed.state()).isEqualTo(RunState.COMPLETED);
        assertThat(crashed.crash().triggered()).isTrue();
        assertThat(retried(crashed)).isNotEmpty();
        assertThat(crashed.report().results()).isNotEmpty().isEqualTo(baseline.report().results());
    }

    @Test
    @DisplayName("C2: a worker that only receives reduce tasks is crashed during the reduce stage, and the result is identical")
    void crashDuringReduceOnly() {
        // Two lines and five workers: map tasks go to nodes 1 and 2 only; node 4 only gets a reduce task.
        String text = "alpha bravo charlie delta echo foxtrot golf hotel india juliett kilo lima\n"
                + "mike november oscar papa quebec romeo sierra tango uniform victor whiskey xray yankee zulu\n";
        RunDto baseline = h.runAndWait(new RunCommand("word-count", InputType.UPLOAD,
                MapReduceModuleHarness.upload("words.txt", text), null));
        assertThat(baseline.report().mapTasks()).isEqualTo(2);
        assertThat(baseline.report().partitions()).isGreaterThanOrEqualTo(4);

        RunDto crashed = h.runAndWait(new RunCommand("word-count", InputType.UPLOAD,
                MapReduceModuleHarness.upload("words.txt", text), 4));

        assertThat(crashed.state()).isEqualTo(RunState.COMPLETED);
        assertThat(crashed.crash()).isEqualTo(new CrashDto(4, true, true, "REDUCE"));
        assertThat(crashed.report().tasks().stream().filter(t -> t.taskType().equals("MAP")))
                .allSatisfy(t -> {
                    assertThat(t.attempts()).isEqualTo(1);
                    assertThat(t.workerId()).isIn(1, 2);
                });
        assertThat(retried(crashed)).isNotEmpty().allSatisfy(t -> {
            assertThat(t.taskType()).isEqualTo("REDUCE");
            assertThat(t.failedAttempts()).extracting(FailedAttemptDto::workerId).containsOnly(4);
        });
        assertThat(crashed.report().results()).isEqualTo(baseline.report().results());
        assertThat(crashed.notice()).contains("first reduce task");
        assertThat(h.cluster.node(4).isUp()).isFalse();
    }

    @Test
    @DisplayName("a chosen worker that receives no task is not crashed, and the report says so")
    void workerWithoutTask() {
        RunDto run = h.runAndWait(new RunCommand("word-count", InputType.UPLOAD,
                MapReduceModuleHarness.upload("one.txt", "hello"), 5));

        assertThat(run.state()).isEqualTo(RunState.COMPLETED);
        assertThat(run.crash()).isEqualTo(new CrashDto(5, false, null, null));
        assertThat(h.cluster.node(5).isUp()).isTrue();
        assertThat(run.notice()).isEqualTo("Node 5 received no task in this run, so it was not crashed.");
        assertThat(h.runEvents(run.runId())).extracting(ClusterEvent::type)
                .containsExactly("JOB_STARTED", "JOB_COMPLETED");
    }
}
