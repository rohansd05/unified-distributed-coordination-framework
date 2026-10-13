package com.udcf.modules.mpi;

import com.udcf.modules.mpi.dto.CollectiveDto;
import com.udcf.modules.mpi.dto.GatheredBlockDto;
import com.udcf.modules.mpi.dto.RefusalDto;
import com.udcf.modules.mpi.dto.RootSource;
import com.udcf.modules.mpi.dto.TransferDto;
import com.udcf.modules.mpi.dto.VerificationDto;
import com.udcf.modules.rmi.PayloadSizer;
import com.udcf.modules.rmi.RemoteFailureDetail;

import java.time.Instant;
import java.util.Objects;

/**
 * Turns a {@link CollectiveResult} into its wire DTO. Every value is copied from what was measured;
 * what was not measured or does not apply stays null (R7). The vectors are left out (only their
 * checksums travel). Pure and stateless.
 */
final class MpiDtoMapper {

    private MpiDtoMapper() {
    }

    static CollectiveDto collective(Instant startedAt, RootSource rootSource, CollectiveResult result) {
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(rootSource, "rootSource must not be null");
        Objects.requireNonNull(result, "result must not be null");
        boolean sized = result.transfers().stream().anyMatch(t -> t.requestBytes() != null || t.replyBytes() != null);
        boolean timed = result.transfers().stream().anyMatch(t -> t.latencyNanos() != null);
        Refusal refusal = result.refusal();
        Verification v = result.verification();
        return new CollectiveDto(result.collectiveId(), startedAt, result.operation(), result.rootNodeId(), rootSource,
                result.rootRank(), result.rankNodeIds(), result.skippedNodeIds(), result.count(), result.seed(),
                result.outcome(), refusal == null ? null : new RefusalDto(refusal.reason(), refusal.message()),
                result.inputChecksum(), result.totalNanos(), result.deadlineMillis(),
                result.transfers().stream().map(MpiDtoMapper::transfer).toList(),
                result.gathered().stream().map(b -> new GatheredBlockDto(b.rank(), b.nodeId(), b.offset(), b.length(),
                        b.checksum(), b.hole())).toList(),
                v == null ? null : new VerificationDto(v.passed(), v.checked(), v.matched(), v.mismatched(), v.unknown(),
                        v.wholeVectorMatches()),
                sized ? PayloadSizer.LABEL : null, timed ? RmiCollectiveTransport.LATENCY_BASIS : null);
    }

    static TransferDto transfer(TransferRecord t) {
        Objects.requireNonNull(t, "transfer must not be null");
        RemoteFailureDetail f = t.failure();
        return new TransferDto(t.rank(), t.fromNodeId(), t.toNodeId(), t.direction(), t.status(), t.offset(),
                t.elementCount(), t.latencyNanos(), t.timeToFailureNanos(), t.requestBytes(), t.replyBytes(),
                t.expectedChecksum(), t.reportedChecksum(), t.verified(),
                f == null ? null : f.stage(), f == null ? null : f.kind(), f == null ? null : f.kind().description(),
                f == null ? null : f.failureClass(), f == null ? null : f.failureMessage(),
                f == null ? null : f.failureCauseClass(),
                t.senderLamportAtSend(), t.replyLamport(), t.lamportAfterReply(), t.notAttemptedReason());
    }
}
