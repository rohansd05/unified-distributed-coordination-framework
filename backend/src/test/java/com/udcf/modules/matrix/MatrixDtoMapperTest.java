package com.udcf.modules.matrix;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.udcf.modules.matrix.dto.ComputeDto;
import com.udcf.modules.matrix.dto.MatrixPhaseDto;
import com.udcf.modules.matrix.dto.MatrixRunDto;
import com.udcf.modules.matrix.dto.MatrixTimingDto;
import com.udcf.modules.mpi.CollectiveOperation;
import com.udcf.modules.mpi.CollectiveOutcome;
import com.udcf.modules.mpi.RmiCollectiveTransport;
import com.udcf.modules.mpi.TransferStatus;
import com.udcf.modules.mpi.dto.RootSource;
import com.udcf.modules.mpi.dto.TransferDto;
import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.PayloadSizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MatrixRunResult to wire DTO on real results of E10b's runner (no network): every figure copied, null kept
 * null, the speedups withheld unless COMPLETE and matched, the basis texts set exactly when they apply, no
 * derived seed anywhere, no number a JavaScript number cannot hold.
 */
class MatrixDtoMapperTest {

    private static final Instant AT = Instant.parse("2026-10-16T12:00:00Z");
    private final JsonMapper json = JsonMapper.builder().findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    private static MatrixRunDto map(MatrixRunResult result) {
        return MatrixDtoMapper.run(AT, RootSource.LEADER, result, MatrixTestResults.BASIS);
    }

    @Test
    @DisplayName("COMPLETE: every figure is copied; phases without seed; speedups present; every basis text set")
    void complete() {
        MatrixRunResult result = MatrixTestResults.complete(3, 10);
        MatrixRunDto dto = map(result);
        MatrixTiming t = result.timing();

        assertThat(dto.runId()).isEqualTo(3);
        assertThat(dto.startedAt()).isEqualTo(AT);
        assertThat(dto.size()).isEqualTo(10);
        assertThat(dto.seed()).isEqualTo(MatrixTestResults.SEED);
        assertThat(dto.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        assertThat(dto.rootRank()).isZero();
        assertThat(dto.rankNodeIds()).containsExactly(1, 2, 3);
        assertThat(dto.refusal()).isNull();
        MatrixPhaseDto scatter = dto.scatter();
        assertThat(scatter.collectiveId()).isEqualTo(MatrixRunRequest.COLLECTIVE_ID_BASE + 6);
        assertThat(scatter.operation()).isEqualTo(CollectiveOperation.SCATTER);
        assertThat(scatter.totalNanos()).isEqualTo(result.scatter().totalNanos());
        assertThat(scatter.transfers()).hasSize(3);
        assertThat(scatter.latencyBasis()).isEqualTo(RmiCollectiveTransport.LATENCY_BASIS);
        assertThat(scatter.payloadSizeBasis()).isEqualTo(PayloadSizer.LABEL);
        assertThat(dto.broadcast().collectiveId()).isEqualTo(MatrixRunRequest.COLLECTIVE_ID_BASE + 7);
        assertThat(dto.computes()).extracting(ComputeDto::nodeId).containsExactly(1, 2, 3);
        assertThat(dto.computes()).extracting(ComputeDto::rowCount).containsExactly(4, 3, 3);
        assertThat(dto.computes().get(0).local()).isTrue();
        assertThat(dto.computes().get(0).roundTripNanos()).isNull();
        assertThat(dto.computes().get(1).roundTripNanos()).isEqualTo(result.computes().get(1).roundTripNanos());
        assertThat(dto.gather()).extracting(TransferDto::status)
                .containsExactly(TransferStatus.LOCAL, TransferStatus.DELIVERED, TransferStatus.DELIVERED);

        MatrixTimingDto.Measured m = dto.timing().measured();
        assertThat(m.sequentialNanos()).isEqualTo(t.sequentialNanos());
        assertThat(m.payloadGenerationNanos()).isEqualTo(result.payloadGenerationNanos());
        assertThat(m.parallelTotalNanos()).isEqualTo(t.parallelTotalNanos());
        assertThat(m.gatherNanos()).isEqualTo(t.gatherNanos());
        MatrixTimingDto.Derived d = dto.timing().derived();
        assertThat(d.speedup()).isNotNull().isEqualTo(t.speedup());
        assertThat(d.computeBoundSpeedup()).isEqualTo(t.computeBoundSpeedup());
        assertThat(d.communicationNanos()).isEqualTo(t.communicationNanos());
        assertThat(d.otherNanos()).isEqualTo(t.otherNanos());
        assertThat(d.speedupCeiling()).isEqualTo(3);
        assertThat(d.multiplyAdds()).isEqualTo(1000);
        assertThat(d.speedupWithheldReason()).isNull();
        assertThat(dto.verification().passed()).isTrue();
        assertThat(dto.verification().mismatchedElements()).isZero();
        assertThat(dto.verification().parallelChecksum()).isEqualTo(dto.verification().sequentialChecksum());

        assertThat(dto.speedupBasis()).startsWith(MatrixTestResults.BASIS.text(3, 1))
                .contains(MatrixTexts.fairness(1)).endsWith(MatrixTexts.OTHER_ACTIVITY);
        assertThat(dto.computeRoundTripBasis()).isEqualTo(MatrixTexts.COMPUTE_ROUND_TRIP_BASIS);
        assertThat(dto.gatherLatencyBasis()).isEqualTo(MatrixTexts.GATHER_LATENCY_BASIS);
        assertThat(dto.payloadSizeBasis()).isEqualTo(PayloadSizer.LABEL);
    }

    @Test
    @DisplayName("REFUSED: refusal set; ranks, phases, computes, gather, timing, verification and bases null or empty")
    void refused() {
        MatrixRunDto dto = map(MatrixTestResults.refused(1));

        assertThat(dto.outcome()).isEqualTo(CollectiveOutcome.REFUSED);
        assertThat(dto.refusal().reason()).isEqualTo(MatrixRefusal.Reason.SIZE_BELOW_PARTICIPANTS);
        assertThat(dto.refusal().message()).isNotBlank();
        assertThat(dto.rootRank()).isNull();
        assertThat(dto.rankNodeIds()).isEmpty();
        assertThat(dto.scatter()).isNull();
        assertThat(dto.broadcast()).isNull();
        assertThat(dto.computes()).isEmpty();
        assertThat(dto.gather()).isEmpty();
        assertThat(dto.timing()).isNull();
        assertThat(dto.verification()).isNull();
        assertThat(dto.inputsDelivered()).isNull();
        assertThat(dto.speedupBasis()).isNull();
        assertThat(dto.computeRoundTripBasis()).isNull();
        assertThat(dto.gatherLatencyBasis()).isNull();
        assertThat(dto.payloadSizeBasis()).isNull();
    }

    @Test
    @DisplayName("PARTIAL: the hole stays null (never 0); no speedup, with the reason; passed and the whole checksum unknown")
    void partialKeepsNulls() {
        MatrixRunDto dto = map(MatrixTestResults.partial(1, 10));

        assertThat(dto.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
        ComputeDto hole = dto.computes().get(2);
        assertThat(hole.status()).isEqualTo(ComputeRecord.Status.FAILED);
        assertThat(hole.failureStage()).isEqualTo(CallStage.LOOKUP);
        assertThat(hole.timeToFailureNanos()).isNotNull();
        assertThat(hole.computeNanos()).isNull();
        assertThat(hole.roundTripNanos()).isNull();
        assertThat(hole.checksum()).isNull();
        assertThat(hole.senderLamportAtSend()).isNull();
        assertThat(dto.gather().get(2).status()).isEqualTo(TransferStatus.NOT_ATTEMPTED);
        assertThat(dto.gather().get(2).latencyNanos()).isNull();
        assertThat(dto.verification().blocksMissing()).isEqualTo(1);
        assertThat(dto.verification().passed()).isNull();
        assertThat(dto.verification().parallelChecksum()).isNull();
        assertThat(dto.verification().checksumsMatch()).isNull();
        MatrixTimingDto.Derived d = dto.timing().derived();
        assertThat(d.speedup()).isNull();
        assertThat(d.computeBoundSpeedup()).isNull();
        assertThat(d.speedupWithheldReason())
                .isEqualTo("No speedup: the run was partial, so its parallel time is not the time of the whole product.");
    }

    @Test
    @DisplayName("FAILED and a COMPLETE run that did not match never get a speedup; each says why")
    void withheld() {
        MatrixRunDto failed = map(MatrixTestResults.failed(1, 10));
        assertThat(failed.outcome()).isEqualTo(CollectiveOutcome.FAILED);
        assertThat(failed.timing().derived().speedup()).isNull();
        assertThat(failed.timing().derived().speedupWithheldReason())
                .isEqualTo("No speedup: the run was failed, so its parallel time is not the time of the whole product.");
        assertThat(failed.timing().measured().gatherNanos()).isNull();
        assertThat(failed.gatherLatencyBasis()).isNull();

        MatrixRunDto mismatch = map(MatrixTestResults.mismatch(1, 10));
        assertThat(mismatch.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        assertThat(mismatch.verification().passed()).isFalse();
        assertThat(mismatch.timing().derived().speedup()).isNull();
        assertThat(mismatch.timing().derived().computeBoundSpeedup()).isNull();
        assertThat(mismatch.timing().derived().speedupWithheldReason()).isEqualTo(MatrixTexts.WITHHELD_MISMATCH);
    }

    @Test
    @DisplayName("only the run itself has a seed field; no record of the wire format has another")
    void noSeedFieldButTheUsers() {
        for (Class<?> type : List.of(MatrixPhaseDto.class, ComputeDto.class, MatrixTimingDto.class,
                MatrixTimingDto.Measured.class, MatrixTimingDto.Derived.class, TransferDto.class)) {
            assertThat(Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName))
                    .as(type.getSimpleName()).noneMatch(name -> name.toLowerCase().contains("seed"));
        }
        assertThat(Arrays.stream(MatrixRunDto.class.getRecordComponents()).map(RecordComponent::getName)
                .filter(name -> name.toLowerCase().contains("seed"))).containsExactly("seed");
    }

    @Test
    @DisplayName("serialized: no derived seed in any form, and every number fits a JavaScript number (all outcomes)")
    void wireIsSafe() throws Exception {
        for (MatrixRunResult result : List.of(MatrixTestResults.complete(1, 10), MatrixTestResults.partial(2, 10),
                MatrixTestResults.failed(3, 10), MatrixTestResults.mismatch(4, 10), MatrixTestResults.refused(5))) {
            String body = json.writeValueAsString(map(result));
            MatrixJsonChecks.assertNoDerivedSeed(body, List.of(MatrixTestResults.SEED));
            MatrixJsonChecks.assertSafeNumbers(json.readTree(body));
            assertThat(body).doesNotContain("NaN").doesNotContain("Infinity");
        }
    }

    @Test
    @DisplayName("a missing phase maps to null")
    void missingPhase() {
        assertThat(MatrixDtoMapper.phase(null)).isNull();
    }
}
