package com.udcf.modules.faulttolerance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udcf.modules.faulttolerance.dto.DataLossDto;
import com.udcf.modules.faulttolerance.dto.MilestoneDto;
import com.udcf.modules.faulttolerance.dto.NodeStateDto;
import com.udcf.modules.faulttolerance.dto.RunDto;
import com.udcf.modules.faulttolerance.dto.SplitBrainDto;
import com.udcf.modules.faulttolerance.dto.StreamDto;
import com.udcf.modules.replication.ConsistencyModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Experiment 8 records to wire records: nulls kept, offsets instead of nano instants. No ports. */
class FaultToleranceDtoMapperTest {

    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;   // 2^53 - 1
    private static final long BASE = Long.MAX_VALUE - 60_000_000_000L;      // a nano clock near its end
    private static final long MS = 1_000_000L;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private static FailoverRun fullRun() {
        return new FailoverRun(1, 1, 2, BASE, InstantSource.ACTION, BASE + 2500 * MS, 3, BASE + 2510 * MS, 2, 3L,
                BASE + 2600 * MS, BASE + 2700 * MS, BASE + 9000 * MS, BASE + 9300 * MS, BASE + 9400 * MS,
                FailoverRun.Outcome.RESTORED);
    }

    @Test
    @DisplayName("a full run: milestones in time order as offsets from the crash, measurements in ms, no nano value on the wire")
    void fullRunAsOffsets() throws Exception {
        RunDto dto = FaultToleranceDtoMapper.run(fullRun(), null);
        assertThat(dto.milestones()).extracting(MilestoneDto::kind).containsExactly("CRASHED", "DETECTED", "ELECTED",
                "PROMOTED", "SERVICE_RESTORED", "OLD_PRIMARY_RECOVERED", "DEMOTED", "RESYNCHRONISED");
        assertThat(dto.milestones()).extracting(MilestoneDto::nodeId).containsExactly(1, 3, 2, 2, 2, 1, 1, 1);
        assertThat(dto.milestones()).extracting(MilestoneDto::offsetMillis)
                .containsExactly(0.0, 2500.0, 2510.0, 2600.0, 2700.0, 9000.0, 9300.0, 9400.0);
        assertThat(dto.measurements().detectionMillis()).isEqualTo(2500.0);
        assertThat(dto.measurements().failoverMillis()).isEqualTo(100.0);
        assertThat(dto.measurements().serviceRestoredMillis()).isEqualTo(100.0);
        assertThat(dto.measurements().outageMillis()).isEqualTo(2700.0);
        assertThat(dto.measurements().recoveryMillis()).isEqualTo(400.0);
        assertThat(dto.crashSource()).isEqualTo("ACTION");
        assertNoUnsafeNumber(json.valueToTree(dto));
        assertThat(json.writeValueAsString(dto)).doesNotContain("Nanos");
    }

    @Test
    @DisplayName("a run that has only crashed: one milestone, every interval null (never 0), data loss null")
    void crashedOnlyKeepsNulls() throws Exception {
        FailoverRun run = new FailoverRun(1, 1, 2, BASE, InstantSource.OBSERVED, null, null, null, null, null, null,
                null, null, null, null, FailoverRun.Outcome.IN_PROGRESS);
        RunDto dto = FaultToleranceDtoMapper.run(run, null);
        assertThat(dto.milestones()).containsExactly(new MilestoneDto("CRASHED", 1, 0.0));
        JsonNode node = json.valueToTree(dto);
        for (String field : List.of("detectionMillis", "failoverMillis", "serviceRestoredMillis", "outageMillis",
                "recoveryMillis")) {
            assertThat(node.path("measurements").get(field).isNull()).as(field).isTrue();
        }
        assertThat(node.get("dataLoss").isNull()).isTrue();
        assertThat(node.get("newPrimaryId").isNull()).isTrue();
        assertThat(node.get("newEpoch").isNull()).isTrue();
        assertThat(node.get("crashSource").asText()).isEqualTo("OBSERVED");
    }

    @Test
    @DisplayName("a run begun by a suspicion has no crash milestone; offsets start at the detection")
    void runWithoutCrash() {
        FailoverRun run = new FailoverRun(1, 1, 2, null, null, BASE, 2, null, null, null, null, null, null, null, null,
                FailoverRun.Outcome.IN_PROGRESS);
        assertThat(FaultToleranceDtoMapper.milestones(run)).containsExactly(new MilestoneDto("DETECTED", 2, 0.0));
    }

    @Test
    @DisplayName("a node that is down has no role, epoch or believed primary; an up node without replication has none either")
    void nodeNullRules() {
        NodeStateDto down = FaultToleranceDtoMapper.node(new NodeFailoverState(1, "CRASHED", false, false, false, 2L,
                null, RejoinState.READY));
        assertThat(down.role()).isNull();
        assertThat(down.epoch()).isNull();
        assertThat(down.believedPrimaryId()).isNull();
        assertThat(down.rejoin()).as("a crashed node is not rejoining yet").isNull();
        NodeStateDto primary = FaultToleranceDtoMapper.node(new NodeFailoverState(2, "UP", true, true, true, 3L, 2,
                RejoinState.READY));
        assertThat(primary).isEqualTo(new NodeStateDto(2, "UP", "PRIMARY", true, 3L, 2, "READY"));
        NodeStateDto backup = FaultToleranceDtoMapper.node(new NodeFailoverState(3, "UP", true, false, false, 3L, 2,
                RejoinState.REJOINING));
        assertThat(backup.role()).isEqualTo("BACKUP");
        assertThat(backup.rejoin()).isEqualTo("REJOINING");
        NodeStateDto bare = FaultToleranceDtoMapper.node(new NodeFailoverState(4, "UP", false, false, false, null,
                null, null));
        assertThat(bare).isEqualTo(new NodeStateDto(4, "UP", null, false, null, null, null));
    }

    @Test
    @DisplayName("data loss: not assessed keeps null counts; a measured 0 stays 0; the delay appears only when simulated")
    void dataLossNullRules() {
        DataLossDto none = FaultToleranceDtoMapper.dataLoss(new DataLossReport(0, null, List.of(), null, null, false, 0,
                DataLossReport.NotAssessed.NOTHING_ACKNOWLEDGED), "why");
        assertThat(none.lost()).isNull();
        assertThat(none.lostSynchronous()).isNull();
        assertThat(none.notAssessed()).isEqualTo("NOTHING_ACKNOWLEDGED");
        assertThat(none.simulatedDelayMillis()).isNull();
        DataLossDto zero = FaultToleranceDtoMapper.dataLoss(new DataLossReport(7, 0, List.of(), 0, 0, false, 0, null),
                "why");
        assertThat(zero.lost()).isZero();
        assertThat(zero.notAssessed()).isNull();
        assertThat(zero.simulatedReason()).isNull();
        DataLossDto async = FaultToleranceDtoMapper.dataLoss(new DataLossReport(7, 2, List.of("setting-0006",
                "setting-0007"), 0, 2, true, 450, null), "why");
        assertThat(async).isEqualTo(new DataLossDto(7, 2, List.of("setting-0006", "setting-0007"), 0, 2, true, 450L,
                "why", null));
    }

    @Test
    @DisplayName("split brain: violations mapped with sentence-case messages")
    void splitBrainMessages() {
        SplitBrainReport report = new SplitBrainReport(false, 4L, List.of(1, 2, 3), List.of(
                new SplitBrainViolation(SplitBrainViolation.Kind.DUPLICATE_PRIMARY, List.of(2, 3), 4, 4),
                new SplitBrainViolation(SplitBrainViolation.Kind.STALE_PRIMARY, List.of(1), 2, 4)));
        SplitBrainDto dto = FaultToleranceDtoMapper.splitBrain(report, 2);
        assertThat(dto.passed()).isFalse();
        assertThat(dto.violationsSeen()).isEqualTo(2);
        assertThat(dto.violations().get(0).message()).isEqualTo("Nodes 2 and 3 act as primary at the same epoch, 4");
        assertThat(dto.violations().get(1).message()).isEqualTo("Node 1 acts as primary at epoch 2, below the highest epoch 4");
        assertThat(dto.violations()).allSatisfy(v -> assertThat(FailoverFixture.ALL_CAPS_WORD.matcher(v.message()).find())
                .isFalse());
        SplitBrainDto none = FaultToleranceDtoMapper.splitBrain(new SplitBrainReport(true, null, List.of(), List.of()), 0);
        assertThat(none.highestEpoch()).isNull();
    }

    @Test
    @DisplayName("stream: unknown values stay null; the budget is always known")
    void streamNullRules() {
        StreamDto fresh = FaultToleranceDtoMapper.stream(new UpdateStream.Snapshot(false, ConsistencyModel.SYNCHRONOUS,
                null, 0, 0, 0, null, 400, 400, null, null, null));
        assertThat(fresh).isEqualTo(new StreamDto("STOPPED", ConsistencyModel.SYNCHRONOUS, null, 0, 0, 0, null, 400, 400,
                null, null, null));
        Instant at = Instant.parse("2026-10-15T10:00:00Z");
        StreamDto stopped = FaultToleranceDtoMapper.stream(new UpdateStream.Snapshot(false,
                ConsistencyModel.ASYNCHRONOUS, 4, 10, 9, 1, null, 400, 390, at, at, UpdateStream.StopReason.LIMIT_REACHED));
        assertThat(stopped.stopReason()).isEqualTo("LIMIT_REACHED");
        assertThat(stopped.remainingUpdates()).isEqualTo(390);
    }

    private static void assertNoUnsafeNumber(JsonNode node) {
        if (node.isNumber()) {
            assertThat(Math.abs(node.asDouble())).as(node.toString()).isLessThanOrEqualTo((double) MAX_SAFE_INTEGER);
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            assertNoUnsafeNumber(it.next().getValue());
        }
        if (node.isArray()) {
            node.forEach(FaultToleranceDtoMapperTest::assertNoUnsafeNumber);
        }
    }
}
