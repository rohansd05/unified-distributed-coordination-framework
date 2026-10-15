package com.udcf.modules.faulttolerance;

import com.udcf.core.cluster.NodeStatus;
import com.udcf.modules.faulttolerance.dto.DataLossDto;
import com.udcf.modules.faulttolerance.dto.MeasurementsDto;
import com.udcf.modules.faulttolerance.dto.MilestoneDto;
import com.udcf.modules.faulttolerance.dto.NodeStateDto;
import com.udcf.modules.faulttolerance.dto.RunDto;
import com.udcf.modules.faulttolerance.dto.SplitBrainDto;
import com.udcf.modules.faulttolerance.dto.StreamDto;
import com.udcf.modules.faulttolerance.dto.ViolationDto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Experiment 8 records to wire records. Nulls stay null (R7: unknown is never 0). No nano-clock
 * instant leaves the backend: a run's instants become offsets in milliseconds from its first
 * instant, so every JSON number stays far below 2^53 - 1.
 *
 * <p>Covered by FaultToleranceDtoMapperTest.</p>
 */
final class FaultToleranceDtoMapper {

    private static final double NANOS_PER_MILLI = 1_000_000d;

    private FaultToleranceDtoMapper() {
    }

    /** A node's role, epoch, believed primary and rejoin state are null while it is down: a crashed node holds no role. */
    static NodeStateDto node(NodeFailoverState state) {
        boolean up = NodeStatus.UP.name().equals(state.nodeStatus());
        boolean known = up && state.epoch() != null;
        return new NodeStateDto(state.nodeId(), state.nodeStatus(),
                known ? (state.actingPrimary() ? FailoverRole.PRIMARY.name() : FailoverRole.BACKUP.name()) : null,
                state.serving(), known ? state.epoch() : null, up ? state.believedPrimaryId() : null,
                up && state.rejoin() != null ? state.rejoin().name() : null);
    }

    static StreamDto stream(UpdateStream.Snapshot s) {
        return new StreamDto(s.running() ? "RUNNING" : "STOPPED", s.mode(), s.ratePerSecond(), s.sent(),
                s.acknowledged(), s.gaveUp(), s.inFlightSequence(), s.maxUpdates(), s.remainingUpdates(),
                s.startedAt(), s.stoppedAt(), s.stopReason() == null ? null : s.stopReason().name());
    }

    static SplitBrainDto splitBrain(SplitBrainReport report, int violationsSeen) {
        return new SplitBrainDto(report.passed(), report.highestEpoch(), report.livePrimaries(),
                report.violations().stream().map(FaultToleranceDtoMapper::violation).toList(), violationsSeen);
    }

    static ViolationDto violation(SplitBrainViolation v) {
        return new ViolationDto(v.kind().name(), v.nodeIds(), v.epoch(), v.highestEpoch(), violationMessage(v));
    }

    static String violationMessage(SplitBrainViolation v) {
        if (v.kind() == SplitBrainViolation.Kind.DUPLICATE_PRIMARY) {
            return "Nodes " + v.nodeIds().stream().map(String::valueOf).collect(Collectors.joining(" and "))
                    + " act as primary at the same epoch, " + v.epoch();
        }
        return "Node " + v.nodeIds().get(0) + " acts as primary at epoch " + v.epoch()
                + ", below the highest epoch " + v.highestEpoch();
    }

    /** The simulated delay and its reason appear only when one applied; otherwise both are null. */
    static DataLossDto dataLoss(DataLossReport report, String simulatedReason) {
        return new DataLossDto(report.acknowledged(), report.lost(), report.lostKeys(), report.lostSynchronous(),
                report.lostAsynchronous(), report.simulated(),
                report.simulated() ? Long.valueOf(report.simulatedDelayMillis()) : null,
                report.simulated() ? simulatedReason : null,
                report.notAssessed() == null ? null : report.notAssessed().name());
    }

    static MeasurementsDto measurements(FailoverMeasurements m) {
        return new MeasurementsDto(m.detectionMillis(), m.failoverMillis(), m.serviceRestoredMillis(),
                m.outageMillis(), m.recoveryMillis());
    }

    /** @param dataLoss the loss measured when the run restored the service; null before that */
    static RunDto run(FailoverRun run, DataLossDto dataLoss) {
        return new RunDto(run.runId(), run.oldPrimaryId(), run.oldEpoch(),
                run.crashSource() == null ? null : run.crashSource().name(), run.detectedByNodeId(),
                run.newPrimaryId(), run.newEpoch(), run.outcome().name(), milestones(run),
                measurements(run.measurements()), dataLoss);
    }

    /** Recorded instants only, in time order, as offsets from the earliest one. */
    static List<MilestoneDto> milestones(FailoverRun run) {
        List<MilestoneAt> all = new ArrayList<>();
        add(all, "CRASHED", run.oldPrimaryId(), run.crashAtNanos());
        add(all, "DETECTED", run.detectedByNodeId(), run.detectedAtNanos());
        add(all, "ELECTED", run.newPrimaryId(), run.electedAtNanos());
        add(all, "PROMOTED", run.newPrimaryId(), run.promotedAtNanos());
        add(all, "SERVICE_RESTORED", run.newPrimaryId(), run.restoredAtNanos());
        add(all, "OLD_PRIMARY_RECOVERED", run.oldPrimaryId(), run.oldPrimaryRecoveredAtNanos());
        add(all, "DEMOTED", run.oldPrimaryId(), run.demotedAtNanos());
        add(all, "RESYNCHRONISED", run.oldPrimaryId(), run.resyncedAtNanos());
        if (all.isEmpty()) {
            return List.of();
        }
        long origin = all.stream().mapToLong(MilestoneAt::nanos).min().orElseThrow();
        return all.stream()
                .sorted(Comparator.comparingLong(MilestoneAt::nanos))
                .map(at -> new MilestoneDto(at.kind(), at.nodeId(), (at.nanos() - origin) / NANOS_PER_MILLI))
                .toList();
    }

    private record MilestoneAt(String kind, Integer nodeId, long nanos) {
    }

    private static void add(List<MilestoneAt> all, String kind, Integer nodeId, Long nanos) {
        if (nanos != null) {
            all.add(new MilestoneAt(kind, nodeId, nanos));
        }
    }
}
