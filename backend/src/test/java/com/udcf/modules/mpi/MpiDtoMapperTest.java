package com.udcf.modules.mpi;

import com.udcf.modules.mpi.dto.CollectiveDto;
import com.udcf.modules.mpi.dto.GatheredBlockDto;
import com.udcf.modules.mpi.dto.RootSource;
import com.udcf.modules.mpi.dto.TransferDto;
import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.PayloadSizer;
import com.udcf.modules.rmi.RemoteFailureKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire mapping: values copied as measured, null (never 0) for everything not measured or not
 * applicable, and no vector on the wire. Results come from E9a's executor; no socket.
 */
class MpiDtoMapperTest {

    private static final Instant AT = Instant.parse("2026-10-13T12:00:00Z");

    private static CollectiveDto map(CollectiveResult result) {
        return MpiDtoMapper.collective(AT, RootSource.LOWEST_LIVE_ID, result);
    }

    /** The names of TransferDto's Long components that are not null. */
    private static Set<String> setNumbers(TransferDto transfer) throws Exception {
        Set<String> set = new TreeSet<>();
        for (RecordComponent component : TransferDto.class.getRecordComponents()) {
            if (component.getType() == Long.class && component.getAccessor().invoke(transfer) != null) {
                set.add(component.getName());
            }
        }
        return set;
    }

    private static TransferDto toNode(CollectiveDto dto, int nodeId) {
        return dto.transfers().stream().filter(t -> t.toNodeId() == nodeId).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("COMPLETE broadcast: identity copied, the root's LOCAL part has no number at all, delivered parts have every one")
    void completeBroadcast() throws Exception {
        CollectiveResult result = MpiTestResults.complete(CollectiveOperation.BROADCAST, 1);
        CollectiveDto dto = map(result);

        assertThat(dto.id()).isEqualTo(1);
        assertThat(dto.startedAt()).isEqualTo(AT);
        assertThat(dto.rootSource()).isEqualTo(RootSource.LOWEST_LIVE_ID);
        assertThat(dto.rootNodeId()).isEqualTo(1);
        assertThat(dto.rootRank()).isZero();
        assertThat(dto.rankNodeIds()).containsExactly(1, 2, 3);
        assertThat(dto.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        assertThat(dto.refusal()).isNull();
        assertThat(dto.inputChecksum()).isEqualTo(result.inputChecksum());
        assertThat(dto.totalNanos()).isEqualTo(result.totalNanos()).isPositive();
        assertThat(dto.payloadSizeBasis()).isEqualTo(PayloadSizer.LABEL);
        assertThat(dto.latencyBasis()).isEqualTo(RmiCollectiveTransport.LATENCY_BASIS);
        assertThat(dto.verification().passed()).isTrue();
        assertThat(dto.verification().checked()).isEqualTo(2);
        assertThat(dto.gathered()).isEmpty();

        TransferDto local = dto.transfers().get(0);
        assertThat(local.status()).isEqualTo(TransferStatus.LOCAL);
        assertThat(setNumbers(local)).isEmpty();
        assertThat(local.failureStage()).isNull();
        assertThat(local.failureKind()).isNull();
        assertThat(local.failureDescription()).isNull();

        TransferDto delivered = toNode(dto, 2);
        assertThat(delivered.status()).isEqualTo(TransferStatus.DELIVERED);
        assertThat(setNumbers(delivered)).containsExactly("lamportAfterReply", "latencyNanos", "replyBytes", "replyLamport",
                "requestBytes", "senderLamportAtSend");
        assertThat(delivered.verified()).isTrue();
        assertThat(delivered).usingRecursiveComparison().isEqualTo(MpiDtoMapper.transfer(result.transfers().get(1)));
        assertThat(delivered.latencyNanos()).isEqualTo(result.transfers().get(1).latencyNanos());
    }

    @Test
    @DisplayName("a LOOKUP failure: only the time to failure is a number; stage, kind, description and classes are set; verified null")
    void lookupFailure() throws Exception {
        CollectiveDto dto = map(MpiTestResults.partialLookup(CollectiveOperation.BROADCAST));
        TransferDto failed = toNode(dto, 3);

        assertThat(dto.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
        assertThat(failed.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(setNumbers(failed)).containsExactly("timeToFailureNanos");
        assertThat(failed.failureStage()).isEqualTo(CallStage.LOOKUP);
        assertThat(failed.failureKind()).isEqualTo(RemoteFailureKind.CONNECTION_REFUSED);
        assertThat(failed.failureDescription()).isEqualTo(RemoteFailureKind.CONNECTION_REFUSED.description());
        assertThat(failed.failureClass()).isEqualTo("java.rmi.ConnectException");
        assertThat(failed.failureMessage()).contains("Connection refused");
        assertThat(failed.verified()).isNull();
        assertThat(failed.reportedChecksum()).isNull();
        assertThat(dto.verification().passed()).isNull();
        assertThat(dto.verification().unknown()).isEqualTo(1);
    }

    @Test
    @DisplayName("NOT_ATTEMPTED: no number at all, the reason is set")
    void notAttempted() throws Exception {
        CollectiveDto dto = map(MpiTestResults.notAttempted());
        TransferDto skipped = toNode(dto, 3);

        assertThat(skipped.status()).isEqualTo(TransferStatus.NOT_ATTEMPTED);
        assertThat(setNumbers(skipped)).isEmpty();
        assertThat(skipped.notAttemptedReason()).contains(String.valueOf(MpiTestResults.PROPERTIES.collectiveDeadlineMillis()));
        assertThat(skipped.verified()).isNull();
    }

    @Test
    @DisplayName("REFUSED: the refusal, and no transfers, checksum, total, verification or basis")
    void refused() {
        CollectiveDto dto = map(MpiTestResults.refused(4));

        assertThat(dto.id()).isEqualTo(4);
        assertThat(dto.outcome()).isEqualTo(CollectiveOutcome.REFUSED);
        assertThat(dto.refusal().reason()).isEqualTo(RefusalReason.COUNT_BELOW_PARTICIPANTS);
        assertThat(dto.refusal().message()).contains("count 2").contains("3 live participants");
        assertThat(dto.transfers()).isEmpty();
        assertThat(dto.rootRank()).isNull();
        assertThat(dto.inputChecksum()).isNull();
        assertThat(dto.totalNanos()).isNull();
        assertThat(dto.verification()).isNull();
        assertThat(dto.payloadSizeBasis()).isNull();
        assertThat(dto.latencyBasis()).isNull();
    }

    @Test
    @DisplayName("gather: each block's checksum as the root computed it, a missing block is a hole with no checksum, no values on the wire")
    void gatherBlocks() {
        CollectiveResult complete = MpiTestResults.complete(CollectiveOperation.GATHER, 1);
        CollectiveDto dto = map(complete);
        assertThat(dto.gathered()).extracting(GatheredBlockDto::rank, GatheredBlockDto::nodeId, GatheredBlockDto::offset,
                GatheredBlockDto::length).containsExactly(
                org.assertj.core.groups.Tuple.tuple(0, 1, 0, 4), org.assertj.core.groups.Tuple.tuple(1, 2, 4, 3),
                org.assertj.core.groups.Tuple.tuple(2, 3, 7, 3));
        assertThat(dto.gathered()).extracting(GatheredBlockDto::checksum)
                .containsExactlyElementsOf(complete.gathered().stream().map(GatheredBlock::checksum).toList());
        assertThat(dto.gathered()).noneMatch(GatheredBlockDto::hole);
        assertThat(dto.verification().wholeVectorMatches()).isTrue();

        CollectiveDto partial = map(MpiTestResults.partialLookup(CollectiveOperation.GATHER));
        GatheredBlockDto hole = partial.gathered().get(2);
        assertThat(hole.hole()).isTrue();
        assertThat(hole.checksum()).isNull();
        assertThat(partial.verification().wholeVectorMatches()).isNull();

        List<String> components = Arrays.stream(GatheredBlockDto.class.getRecordComponents()).map(RecordComponent::getName).toList();
        assertThat(components).containsExactly("rank", "nodeId", "offset", "length", "checksum", "hole");
    }
}
