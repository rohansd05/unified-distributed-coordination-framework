package com.udcf.modules.mpi;

import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.RemoteFailureDetail;
import com.udcf.modules.rmi.RemoteFailureKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validation and defensive copies of the Experiment 9 records. */
class MpiRecordsTest {

    private static final String CHECKSUM = VectorChecksum.of(new double[] {1});

    @Test
    @DisplayName("CollectiveRequest copies its lists and rejects invalid ids and counts")
    void request() {
        List<Integer> participants = new ArrayList<>(List.of(1, 2));
        Set<Integer> live = new HashSet<>(Set.of(1, 2));
        CollectiveRequest request = new CollectiveRequest(1, CollectiveOperation.GATHER, 1, participants, live, 2, 0);
        participants.add(3);
        live.add(3);
        assertThat(request.participantNodeIds()).containsExactly(1, 2);
        assertThat(request.liveNodeIds()).containsExactlyInAnyOrder(1, 2);
        assertThatThrownBy(() -> new CollectiveRequest(0, CollectiveOperation.GATHER, 1, participants, live, 2, 0))
                .hasMessage("collectiveId must be >= 1, was 0");
        assertThatThrownBy(() -> new CollectiveRequest(1, CollectiveOperation.GATHER, 0, participants, live, 2, 0))
                .hasMessage("rootNodeId must be >= 1, was 0");
        assertThatThrownBy(() -> new CollectiveRequest(1, CollectiveOperation.GATHER, 1, participants, live, 0, 0))
                .hasMessage("count must be >= 1, was 0");
        assertThatThrownBy(() -> new CollectiveRequest(1, null, 1, participants, live, 1, 0)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("ReceiveReceipt and Contribution check their stamp, ids and checksum")
    void replies() {
        assertThatThrownBy(() -> new ReceiveReceipt(0, 1, 1, 0, 1, CHECKSUM)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReceiveReceipt(1, 1, 0, 0, 1, CHECKSUM)).hasMessage("collectiveId must be >= 1, was 0");
        assertThatThrownBy(() -> new ReceiveReceipt(1, 1, 1, -1, 1, CHECKSUM)).hasMessage("rank must be >= 0, was -1");
        assertThatThrownBy(() -> new ReceiveReceipt(1, 1, 1, 0, -1, CHECKSUM)).hasMessage("elementCount must be >= 0, was -1");
        assertThatThrownBy(() -> new ReceiveReceipt(1, 1, 1, 0, 1, "abc")).hasMessageStartingWith("checksum must be 64");
        assertThatThrownBy(() -> new Contribution(1, -1, 1, 0, new double[] {1}, CHECKSUM)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Contribution(1, 1, 1, 0, null, CHECKSUM)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Contribution copies its values in and out and compares them element by element")
    void contributionCopies() {
        double[] values = {1, 2};
        Contribution contribution = new Contribution(1, 1, 1, 0, values, CHECKSUM);
        values[0] = 9;
        contribution.values()[1] = 9;
        assertThat(contribution.values()).containsExactly(1, 2);
        assertThat(contribution).isEqualTo(new Contribution(1, 1, 1, 0, new double[] {1, 2}, CHECKSUM))
                .hasSameHashCodeAs(new Contribution(1, 1, 1, 0, new double[] {1, 2}, CHECKSUM));
        assertThat(contribution.toString()).contains("length=2");
    }

    @Test
    @DisplayName("GatheredBlock: a hole has neither values nor checksum; a block's values are copied and must match its length")
    void gatheredBlock() {
        GatheredBlock hole = new GatheredBlock(1, 2, 3, 2, null, null);
        assertThat(hole.hole()).isTrue();
        assertThat(hole.values()).isNull();
        double[] values = {1, 2};
        GatheredBlock block = new GatheredBlock(1, 2, 3, 2, values, CHECKSUM);
        values[0] = 9;
        assertThat(block.values()).containsExactly(1, 2);
        assertThat(block).isEqualTo(new GatheredBlock(1, 2, 3, 2, new double[] {1, 2}, CHECKSUM));
        assertThatThrownBy(() -> new GatheredBlock(1, 2, 3, 2, values, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatheredBlock(1, 2, 3, 3, values, CHECKSUM)).hasMessage("block 1 must have 3 values, has 2");
    }

    @Test
    @DisplayName("TransferRecord enforces null-never-0 per status: latency only when DELIVERED, failure only when FAILED")
    void transferRecordRules() {
        RemoteFailureDetail failure = RemoteFailureDetail.observed(CallStage.CALL, RemoteFailureKind.OTHER, "x", 10);
        assertThatThrownBy(() -> new TransferRecord(1, 1, 2, TransferDirection.TO_RANK, TransferStatus.DELIVERED, 0, 1,
                null, null, 1L, 1L, CHECKSUM, CHECKSUM, true, null, 1L, 2L, 3L, null)).hasMessage("latencyNanos is set exactly for DELIVERED");
        assertThatThrownBy(() -> new TransferRecord(1, 1, 2, TransferDirection.TO_RANK, TransferStatus.FAILED, 0, 1,
                5L, 5L, null, null, CHECKSUM, null, null, failure, null, null, null, null))
                .hasMessage("latencyNanos is set exactly for DELIVERED");
        assertThatThrownBy(() -> new TransferRecord(1, 1, 2, TransferDirection.TO_RANK, TransferStatus.FAILED, 0, 1,
                null, 5L, null, null, CHECKSUM, null, true, failure, null, null, null, null))
                .hasMessage("only a DELIVERED or LOCAL part can be verified");
        assertThatThrownBy(() -> new TransferRecord(1, 1, 2, TransferDirection.TO_RANK, TransferStatus.NOT_ATTEMPTED, 0, 1,
                null, null, 10L, null, CHECKSUM, null, null, null, null, null, null, "late"))
                .hasMessage("a NOT_ATTEMPTED part has no size and no Lamport time");
        assertThatThrownBy(() -> new TransferRecord(1, 1, 1, TransferDirection.TO_RANK, TransferStatus.LOCAL, 0, 1,
                null, null, null, null, CHECKSUM, null, null, null, null, null, null, "late"))
                .hasMessage("notAttemptedReason is set exactly for NOT_ATTEMPTED");
    }

    @Test
    @DisplayName("TransferRecord: timeToFailureNanos is set exactly for FAILED (always there), and never negative")
    void timeToFailureRules() {
        RemoteFailureDetail failure = RemoteFailureDetail.observed(CallStage.CALL, RemoteFailureKind.OTHER, "x", 10);
        TransferRecord failed = new TransferRecord(1, 1, 2, TransferDirection.TO_RANK, TransferStatus.FAILED, 0, 1,
                null, 250L, null, null, CHECKSUM, null, null, failure, null, null, null, null);
        assertThat(failed.timeToFailureNanos()).isEqualTo(250L);
        assertThat(failed.latencyNanos()).isNull();
        assertThatThrownBy(() -> new TransferRecord(1, 1, 2, TransferDirection.TO_RANK, TransferStatus.FAILED, 0, 1,
                null, null, null, null, CHECKSUM, null, null, failure, null, null, null, null))
                .hasMessage("timeToFailureNanos is set exactly for FAILED");
        assertThatThrownBy(() -> new TransferRecord(1, 1, 2, TransferDirection.TO_RANK, TransferStatus.FAILED, 0, 1,
                null, -1L, null, null, CHECKSUM, null, null, failure, null, null, null, null))
                .hasMessage("timeToFailureNanos must be >= 0");
        assertThatThrownBy(() -> new TransferRecord(1, 1, 2, TransferDirection.TO_RANK, TransferStatus.DELIVERED, 0, 1,
                5L, 5L, 1L, 1L, CHECKSUM, CHECKSUM, true, null, 1L, 2L, 3L, null))
                .hasMessage("timeToFailureNanos is set exactly for FAILED");
        assertThatThrownBy(() -> new TransferRecord(1, 1, 1, TransferDirection.TO_RANK, TransferStatus.LOCAL, 0, 1,
                null, 5L, null, null, CHECKSUM, null, null, null, null, null, null, null))
                .hasMessage("timeToFailureNanos is set exactly for FAILED");
        assertThatThrownBy(() -> new TransferRecord(1, 1, 2, TransferDirection.TO_RANK, TransferStatus.NOT_ATTEMPTED, 0, 1,
                null, 5L, null, null, CHECKSUM, null, null, null, null, null, null, "late"))
                .hasMessage("timeToFailureNanos is set exactly for FAILED");
    }

    @Test
    @DisplayName("Verification: any mismatch is false; any unknown, or nothing checked, is null; only all matched is true")
    void verificationRules() {
        assertThat(Verification.of(3, 0, 0, null).passed()).isTrue();
        assertThat(Verification.of(2, 1, 0, null).passed()).isFalse();
        assertThat(Verification.of(2, 0, 1, null).passed()).isNull();
        assertThat(Verification.of(1, 1, 1, null).passed()).isFalse();
        assertThat(Verification.of(0, 0, 0, null).passed()).isNull();
        assertThat(Verification.of(3, 0, 0, false).passed()).isFalse();
        assertThatThrownBy(() -> new Verification(true, 3, 1, 1, 0, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("CollectiveResult: a refusal has no transfers or verification; assembled() is null while any block is a hole")
    void resultRules() {
        Refusal refusal = new Refusal(RefusalReason.ROOT_DOWN, "down");
        assertThatThrownBy(() -> new CollectiveResult(1, CollectiveOperation.GATHER, 1, null, List.of(), List.of(), 2, 0,
                CollectiveOutcome.REFUSED, null, null, List.of(), null, List.of(), null, 1)).hasMessage("refusal is set exactly for REFUSED");
        assertThatThrownBy(() -> new CollectiveResult(1, CollectiveOperation.GATHER, 1, null, List.of(), List.of(), 2, 0,
                CollectiveOutcome.REFUSED, refusal, null, List.of(), 5L, List.of(), null, 1)).isInstanceOf(IllegalArgumentException.class);
        String checksum = VectorChecksum.of(new double[] {1});
        CollectiveResult withHole = new CollectiveResult(1, CollectiveOperation.GATHER, 1, 0, List.of(1, 2), List.of(), 2, 0,
                CollectiveOutcome.PARTIAL, null, checksum, List.of(), 1L,
                List.of(new GatheredBlock(0, 1, 0, 1, new double[] {1}, checksum), new GatheredBlock(1, 2, 1, 1, null, null)),
                Verification.of(0, 0, 1, null), 1);
        assertThat(withHole.assembled()).isNull();
    }
}
