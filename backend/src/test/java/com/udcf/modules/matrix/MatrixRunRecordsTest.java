package com.udcf.modules.matrix;

import com.udcf.modules.mpi.CollectiveOutcome;
import com.udcf.modules.mpi.VectorChecksum;
import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.RemoteFailureDetail;
import com.udcf.modules.rmi.RemoteFailureKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rules of the E10b records: what is set exactly when, copies of arrays, the collective ids of a run. */
class MatrixRunRecordsTest {

    private static final String SUM = VectorChecksum.of(new double[] {1, 2});
    private static final RemoteFailureDetail REFUSED = new RemoteFailureDetail(CallStage.LOOKUP, RemoteFailureKind.CONNECTION_REFUSED,
            "java.rmi.ConnectException", "refused", "java.net.ConnectException");

    @Test
    @DisplayName("ComputeAck: a stamp, a run and rank, at least one row, a non-negative time and a real checksum")
    void computeAck() {
        assertThat(new ComputeAck(2, 5, 1, 0, 3, 0, SUM).rowCount()).isEqualTo(3);
        assertThatThrownBy(() -> new ComputeAck(0, 5, 1, 0, 3, 0, SUM)).hasMessage("nodeId must be >= 1, was 0");
        assertThatThrownBy(() -> new ComputeAck(2, 5, 0, 0, 3, 0, SUM)).hasMessage("runId must be >= 1, was 0");
        assertThatThrownBy(() -> new ComputeAck(2, 5, 1, 0, 0, 0, SUM)).hasMessage("rowCount must be >= 1, was 0");
        assertThatThrownBy(() -> new ComputeAck(2, 5, 1, 0, 3, -1, SUM)).hasMessage("computeNanos must be >= 0, was -1");
        assertThatThrownBy(() -> new ComputeAck(2, 5, 1, 0, 3, 0, "x")).hasMessageStartingWith("checksum must be 64");
    }

    @Test
    @DisplayName("BlockReply: values copied in and out, compared element by element")
    void blockReply() {
        double[] values = {1, 2};
        BlockReply reply = new BlockReply(2, 5, 1, 0, values);
        values[0] = 99;
        reply.values()[1] = 99;
        assertThat(reply.values()).containsExactly(1, 2);
        assertThat(reply).isEqualTo(new BlockReply(2, 5, 1, 0, new double[] {1, 2})).hasSameHashCodeAs(new BlockReply(2, 5, 1, 0,
                new double[] {1, 2}));
        assertThat(reply.toString()).contains("length=2");
        assertThatThrownBy(() -> new BlockReply(2, 5, 1, 0, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("ComputeRecord: DONE, FAILED and NOT_ATTEMPTED each carry exactly their own fields; unknown is null, never 0")
    void computeRecord() {
        assertThat(new ComputeRecord(1, 2, 0, 3, false, ComputeRecord.Status.DONE, 10L, null, 5L, SUM, null, null, null, 1L, 3L, 4L,
                null).status()).isEqualTo(ComputeRecord.Status.DONE);
        assertThat(new ComputeRecord(0, 1, 0, 3, true, ComputeRecord.Status.DONE, null, null, 5L, SUM, null, null, null, null, null,
                null, null).local()).isTrue();
        assertThat(new ComputeRecord(1, 2, 0, 3, false, ComputeRecord.Status.FAILED, null, 7L, null, null, REFUSED, null, null, null,
                null, null, null).failure()).isEqualTo(REFUSED);

        assertThatThrownBy(() -> new ComputeRecord(1, 2, 0, 3, false, ComputeRecord.Status.DONE, null, null, 5L, SUM, null, null, null,
                1L, 3L, 4L, null)).hasMessage("roundTripNanos is set exactly for a remote DONE");
        assertThatThrownBy(() -> new ComputeRecord(1, 2, 0, 3, false, ComputeRecord.Status.FAILED, null, 7L, null, null, null, null,
                null, null, null, null, null)).hasMessage("failure is set exactly for a remote FAILED");
        assertThatThrownBy(() -> new ComputeRecord(0, 1, 0, 3, true, ComputeRecord.Status.FAILED, null, 7L, null, null, null, null,
                null, null, null, null, null)).hasMessage("a local FAILED compute has a nodeReason");
        assertThatThrownBy(() -> new ComputeRecord(1, 2, 0, 3, false, ComputeRecord.Status.NOT_ATTEMPTED, null, null, null, null, null,
                null, null, null, null, null, null)).hasMessage("notAttemptedReason is set exactly for NOT_ATTEMPTED");
        assertThatThrownBy(() -> new ComputeRecord(1, 2, 0, 3, false, ComputeRecord.Status.FAILED, null, 7L, null, null, REFUSED,
                MatrixComputeException.Reason.INPUT_MISSING, null, null, null, null, null))
                .hasMessage("nodeReason and nodeMessage are set together");
        assertThatThrownBy(() -> new ComputeRecord(0, 1, 0, 3, true, ComputeRecord.Status.DONE, null, null, 5L, SUM, null, null, null,
                1L, null, null, null)).hasMessage("a local compute has no Lamport time");
        assertThatThrownBy(() -> new ComputeRecord(1, 2, 0, 3, false, ComputeRecord.Status.DONE, 10L, null, -1L, SUM, null, null, null,
                1L, 3L, 4L, null)).hasMessage("computeNanos must be >= 0");
    }

    @Test
    @DisplayName("MatrixRunRequest: matrix collective ids are 1000000000 + 2 x runId and the next; run ids and sizes are checked")
    void runRequest() {
        MatrixRunRequest request = new MatrixRunRequest(3, 10, -5, 1, List.of(1, 2), Set.of(1, 2));
        assertThat(request.scatterCollectiveId()).isEqualTo(1_000_000_006L);
        assertThat(request.broadcastCollectiveId()).isEqualTo(1_000_000_007L);
        MatrixRunRequest last = new MatrixRunRequest(MatrixRunRequest.MAX_RUN_ID, 10, 0, 1, List.of(1), Set.of(1));
        assertThat(last.broadcastCollectiveId()).isPositive();
        assertThatThrownBy(() -> new MatrixRunRequest(0, 10, 0, 1, List.of(1), Set.of(1))).hasMessageStartingWith("runId must be in 1..");
        assertThatThrownBy(() -> new MatrixRunRequest(MatrixRunRequest.MAX_RUN_ID + 1, 10, 0, 1, List.of(1), Set.of(1)))
                .hasMessageStartingWith("runId must be in 1..");
        assertThatThrownBy(() -> new MatrixRunRequest(1, 0, 0, 1, List.of(1), Set.of(1))).hasMessage("n must be >= 1, was 0");
    }

    @Test
    @DisplayName("MatrixRunResult: a refusal has no ranks, times or verification; a run that ran has all of them")
    void runResult() {
        MatrixRefusal refusal = new MatrixRefusal(MatrixRefusal.Reason.SIZE_ABOVE_MAXIMUM, "too big");
        MatrixRunResult refused = new MatrixRunResult(1, 400, 0, 1, null, List.of(), List.of(), List.of(), CollectiveOutcome.REFUSED,
                refusal, 10_000, null, null, null, List.of(), List.of(), null, null, null);
        assertThat(refused.refusal()).isEqualTo(refusal);
        assertThatThrownBy(() -> new MatrixRunResult(1, 400, 0, 1, null, List.of(), List.of(), List.of(), CollectiveOutcome.REFUSED,
                refusal, 10_000, 5L, null, null, List.of(), List.of(), null, null, null))
                .hasMessage("a refused run has no ranks, transfers, times or verification");
        assertThatThrownBy(() -> new MatrixRunResult(1, 400, 0, 1, null, List.of(), List.of(), List.of(), CollectiveOutcome.REFUSED,
                null, 10_000, null, null, null, List.of(), List.of(), null, null, null))
                .hasMessage("refusal is set exactly for REFUSED");
        assertThatThrownBy(() -> new MatrixRunResult(1, 10, 0, 1, 0, List.of(1), List.of(), List.of(), CollectiveOutcome.COMPLETE,
                null, 10_000, 5L, null, null, List.of(), List.of(), null, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
