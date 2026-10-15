package com.udcf.modules.matrix;

import com.udcf.modules.matrix.dto.ComputeDto;
import com.udcf.modules.matrix.dto.MatrixPhaseDto;
import com.udcf.modules.matrix.dto.MatrixRefusalDto;
import com.udcf.modules.matrix.dto.MatrixRunDto;
import com.udcf.modules.matrix.dto.MatrixTimingDto;
import com.udcf.modules.matrix.dto.MatrixVerificationDto;
import com.udcf.modules.mpi.CollectiveOutcome;
import com.udcf.modules.mpi.CollectiveResult;
import com.udcf.modules.mpi.MpiDtoMapper;
import com.udcf.modules.mpi.Refusal;
import com.udcf.modules.mpi.RmiCollectiveTransport;
import com.udcf.modules.mpi.TransferRecord;
import com.udcf.modules.mpi.Verification;
import com.udcf.modules.mpi.dto.RefusalDto;
import com.udcf.modules.mpi.dto.RootSource;
import com.udcf.modules.mpi.dto.VerificationDto;
import com.udcf.modules.rmi.PayloadSizer;
import com.udcf.modules.rmi.RemoteFailureDetail;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Turns a {@link MatrixRunResult} into its wire DTO. Every value is copied from what was measured; what was
 * not measured or does not apply stays null (R7). A, B and C are left out (only checksums travel), and so are
 * the seeds of the scatter and the broadcast, which are derived from the user's seed (full 64-bit values a
 * JavaScript number cannot hold). The speedups are withheld unless the run was COMPLETE and matched the
 * sequential product. Pure and stateless.
 */
final class MatrixDtoMapper {

    private MatrixDtoMapper() {
    }

    static MatrixRunDto run(Instant startedAt, RootSource rootSource, MatrixRunResult result, SpeedupBasis basis) {
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(rootSource, "rootSource must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(basis, "basis must not be null");
        boolean refused = result.outcome() == CollectiveOutcome.REFUSED;
        MatrixRefusal refusal = result.refusal();
        List<TransferRecord> gather = result.gather();
        boolean roundTrips = result.computes().stream().anyMatch(c -> c.roundTripNanos() != null);
        boolean fetchTimed = gather.stream().anyMatch(t -> t.latencyNanos() != null);
        boolean fetchSized = gather.stream().anyMatch(t -> t.requestBytes() != null || t.replyBytes() != null);
        int ranks = result.rankNodeIds().size();
        return new MatrixRunDto(result.runId(), startedAt, result.n(), result.seed(), result.rootNodeId(), rootSource,
                result.rootRank(), result.rankNodeIds(), result.skippedNodeIds(), result.outcome(),
                refusal == null ? null : new MatrixRefusalDto(refusal.reason(), refusal.message()), result.deadlineMillis(),
                phase(result.scatter()), phase(result.broadcast()),
                result.computes().stream().map(MatrixDtoMapper::compute).toList(),
                gather.stream().map(MpiDtoMapper::transfer).toList(),
                refused ? null : timing(result, basis.ceiling(ranks)),
                verification(result.verification()), result.inputsDelivered(),
                refused ? null : MatrixTexts.speedupBasis(basis, ranks, result.timing().warmupProducts()),
                roundTrips ? MatrixTexts.COMPUTE_ROUND_TRIP_BASIS : null,
                fetchTimed ? MatrixTexts.GATHER_LATENCY_BASIS : null,
                fetchSized ? PayloadSizer.LABEL : null);
    }

    /** Experiment 9's collective of a run, without its (derived) seed. */
    static MatrixPhaseDto phase(CollectiveResult c) {
        if (c == null) {
            return null;
        }
        boolean sized = c.transfers().stream().anyMatch(t -> t.requestBytes() != null || t.replyBytes() != null);
        boolean timed = c.transfers().stream().anyMatch(t -> t.latencyNanos() != null);
        Refusal refusal = c.refusal();
        Verification v = c.verification();
        return new MatrixPhaseDto(c.collectiveId(), c.operation(), c.outcome(),
                refusal == null ? null : new RefusalDto(refusal.reason(), refusal.message()), c.inputChecksum(),
                c.totalNanos(), c.deadlineMillis(), c.transfers().stream().map(MpiDtoMapper::transfer).toList(),
                v == null ? null : new VerificationDto(v.passed(), v.checked(), v.matched(), v.mismatched(), v.unknown(),
                        v.wholeVectorMatches()),
                sized ? PayloadSizer.LABEL : null, timed ? RmiCollectiveTransport.LATENCY_BASIS : null);
    }

    static ComputeDto compute(ComputeRecord c) {
        Objects.requireNonNull(c, "compute must not be null");
        RemoteFailureDetail f = c.failure();
        return new ComputeDto(c.rank(), c.nodeId(), c.rowStart(), c.rowCount(), c.local(), c.status(), c.roundTripNanos(),
                c.timeToFailureNanos(), c.computeNanos(), c.checksum(),
                f == null ? null : f.stage(), f == null ? null : f.kind(), f == null ? null : f.kind().description(),
                f == null ? null : f.failureClass(), f == null ? null : f.failureMessage(),
                f == null ? null : f.failureCauseClass(), c.nodeReason(), c.nodeMessage(), c.senderLamportAtSend(),
                c.replyLamport(), c.lamportAfterReply(), c.notAttemptedReason());
    }

    /** The times; the speedups only for a COMPLETE run whose result matched, otherwise null with the reason. */
    static MatrixTimingDto timing(MatrixRunResult result, int speedupCeiling) {
        MatrixTiming t = result.timing();
        String withheld = withheldReason(result);
        boolean speedups = withheld == null;
        return new MatrixTimingDto(t.warmupProducts(),
                new MatrixTimingDto.Measured(t.sequentialNanos(), result.payloadGenerationNanos(), t.scatterNanos(),
                        t.broadcastNanos(), t.computePhaseNanos(), t.gatherNanos(), t.parallelTotalNanos()),
                new MatrixTimingDto.Derived(t.communicationNanos(), t.otherNanos(), t.communicationShare(),
                        speedups ? t.speedup() : null, speedups ? t.computeBoundSpeedup() : null, speedupCeiling,
                        t.multiplyAdds(), withheld));
    }

    /** Why the speedups of a run that ran are withheld; null when they are not. */
    static String withheldReason(MatrixRunResult result) {
        if (result.outcome() != CollectiveOutcome.COMPLETE) {
            return MatrixTexts.withheld(result.outcome());
        }
        return Boolean.TRUE.equals(result.verification().passed()) ? null : MatrixTexts.WITHHELD_MISMATCH;
    }

    private static MatrixVerificationDto verification(MatrixVerification v) {
        return v == null ? null : new MatrixVerificationDto(v.rowsExpected(), v.rowsReceived(), v.blocksMissing(),
                v.blocksWithBadChecksum(), v.elementsCompared(), v.mismatchedElements(), v.parallelChecksum(),
                v.sequentialChecksum(), v.checksumsMatch(), v.passed());
    }
}
