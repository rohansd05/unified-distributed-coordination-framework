package com.udcf.modules.mpi;

import com.udcf.core.clock.LamportClock;
import com.udcf.modules.rmi.CallContext;
import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.PayloadSizer;
import com.udcf.modules.rmi.RemoteFailureKind;
import com.udcf.modules.rmi.ScriptedNanoClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.rmi.UnmarshalException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectiveExecutorTest {

    private static final MpiProperties PROPERTIES = new MpiProperties(1000, 10_000, 20, 8, 300);
    private static final List<Integer> NODES = List.of(1, 2, 3, 4);
    private static final Set<Integer> ALL_LIVE = Set.of(1, 2, 3, 4);
    private static final long ID = 7;
    private static final long SEED = 42;

    /** A clock that advances 1000 ns on every read, starting at 0. */
    private static LongSupplier stepping() {
        AtomicLong now = new AtomicLong();
        return () -> now.getAndAdd(1000);
    }

    private static CollectiveRequest request(CollectiveOperation operation, int root, Set<Integer> live, int count) {
        return new CollectiveRequest(ID, operation, root, NODES, live, count, SEED);
    }

    private static ScriptedCollectiveTransport transport() {
        return new ScriptedCollectiveTransport(PROPERTIES, 1, 2, 3, 4);
    }

    private static CollectiveResult run(CollectiveRequest request, ScriptedCollectiveTransport transport) {
        return new CollectiveExecutor(PROPERTIES, stepping()).execute(request, new LamportClock(), transport);
    }

    private static TransferRecord transferTo(CollectiveResult result, int rank) {
        return result.transfers().get(rank);
    }

    // ------------------------------------------------------------------ the three collectives

    @Test
    @DisplayName("broadcast: the root sends the whole vector to every other rank, in rank order; all verified; COMPLETE")
    void broadcastCompletes() {
        ScriptedCollectiveTransport transport = transport();
        CollectiveResult result = run(request(CollectiveOperation.BROADCAST, 1, ALL_LIVE, 10), transport);

        double[] vector = PayloadGenerator.vector(SEED, 10);
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        assertThat(result.inputChecksum()).isEqualTo(VectorChecksum.of(vector));
        assertThat(transport.sent()).extracting(ScriptedCollectiveTransport.Sent::targetNodeId).containsExactly(2, 3, 4);
        assertThat(result.transfers()).extracting(TransferRecord::status).containsExactly(
                TransferStatus.LOCAL, TransferStatus.DELIVERED, TransferStatus.DELIVERED, TransferStatus.DELIVERED);
        for (int node = 2; node <= 4; node++) {
            assertThat(transport.node(node).received(ID)).get().isEqualTo(vector);
            assertThat(transferTo(result, node - 1).reportedChecksum()).isEqualTo(result.inputChecksum());
        }
        assertThat(result.verification()).isEqualTo(new Verification(true, 3, 3, 0, 0, null));
        assertThat(result.gathered()).isEmpty();
    }

    @Test
    @DisplayName("scatter n = 10 over 4 ranks with root node 3 (rank 2): blocks 3, 3, 2, 2; the root's block is local")
    void scatterCompletes() {
        ScriptedCollectiveTransport transport = transport();
        CollectiveResult result = run(request(CollectiveOperation.SCATTER, 3, ALL_LIVE, 10), transport);

        double[] vector = PayloadGenerator.vector(SEED, 10);
        assertThat(result.rootRank()).isEqualTo(2);
        assertThat(result.transfers()).extracting(TransferRecord::offset).containsExactly(0, 3, 6, 8);
        assertThat(result.transfers()).extracting(TransferRecord::elementCount).containsExactly(3, 3, 2, 2);
        assertThat(transferTo(result, 2).status()).isEqualTo(TransferStatus.LOCAL);
        assertThat(transport.node(1).received(ID)).get().isEqualTo(java.util.Arrays.copyOfRange(vector, 0, 3));
        assertThat(transport.node(4).received(ID)).get().isEqualTo(java.util.Arrays.copyOfRange(vector, 8, 10));
        assertThat(transport.node(3).received(ID)).isEmpty();
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        assertThat(result.verification()).isEqualTo(new Verification(true, 3, 3, 0, 0, true));
    }

    @Test
    @DisplayName("gather: rank i contributes exactly block i's length; the assembled vector has n values and equals the expected one")
    void gatherCompletes() {
        ScriptedCollectiveTransport transport = transport();
        CollectiveResult result = run(request(CollectiveOperation.GATHER, 2, ALL_LIVE, 10), transport);

        double[] expected = new double[10];
        int[][] blocks = {{1, 0, 3}, {2, 3, 3}, {3, 6, 2}, {4, 8, 2}};   // node, offset, length
        for (int[] b : blocks) {
            System.arraycopy(PayloadGenerator.contribution(b[0], b[2], SEED), 0, expected, b[1], b[2]);
        }
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        assertThat(result.assembled()).hasSize(10).isEqualTo(expected);
        assertThat(result.inputChecksum()).isEqualTo(VectorChecksum.of(expected));
        assertThat(transport.sent()).extracting(ScriptedCollectiveTransport.Sent::length).containsExactly(3, 2, 2);
        assertThat(result.transfers()).extracting(TransferRecord::direction).containsOnly(TransferDirection.FROM_RANK);
        assertThat(result.verification()).isEqualTo(new Verification(true, 3, 3, 0, 0, true));
    }

    @Test
    @DisplayName("the root's own part never crosses the network: LOCAL, with no latency, size or Lamport time (null, not 0)")
    void rootPartIsLocal() {
        CollectiveResult result = run(request(CollectiveOperation.SCATTER, 1, ALL_LIVE, 8), transport());
        TransferRecord local = transferTo(result, 0);
        assertThat(local.status()).isEqualTo(TransferStatus.LOCAL);
        assertThat(local.fromNodeId()).isEqualTo(1);
        assertThat(local.toNodeId()).isEqualTo(1);
        assertThat(local.latencyNanos()).isNull();
        assertThat(local.requestBytes()).isNull();
        assertThat(local.replyBytes()).isNull();
        assertThat(local.senderLamportAtSend()).isNull();
        assertThat(local.verified()).isNull();
    }

    @Test
    @DisplayName("a collective of the root alone is COMPLETE with no transfer time and nothing to verify (null)")
    void rootAlone() {
        CollectiveResult result = run(new CollectiveRequest(ID, CollectiveOperation.BROADCAST, 1, List.of(1, 2), Set.of(1), 3, SEED),
                transport());
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        assertThat(result.skippedNodeIds()).containsExactly(2);
        assertThat(result.totalNanos()).isNull();
        assertThat(result.verification()).isEqualTo(new Verification(null, 0, 0, 0, 0, null));
    }

    @Test
    @DisplayName("a participant that is down at the start is skipped and listed, never dropped silently")
    void skippedNodesAreListed() {
        ScriptedCollectiveTransport transport = transport();
        CollectiveResult result = run(request(CollectiveOperation.BROADCAST, 1, Set.of(1, 2, 4), 5), transport);
        assertThat(result.rankNodeIds()).containsExactly(1, 2, 4);
        assertThat(result.skippedNodeIds()).containsExactly(3);
        assertThat(transport.sent()).extracting(ScriptedCollectiveTransport.Sent::targetNodeId).containsExactly(2, 4);
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
    }

    // ------------------------------------------------------------------ failures are data

    @Test
    @DisplayName("a failed lookup is recorded (LOOKUP, connection refused, nothing sent) and the next rank is still attempted: PARTIAL")
    void lookupFailureContinues() {
        ScriptedCollectiveTransport transport = transport()
                .failing(3, CallStage.LOOKUP, new java.rmi.ConnectException("Connection refused to host: 127.0.0.1"));
        CollectiveResult result = run(request(CollectiveOperation.BROADCAST, 1, ALL_LIVE, 5), transport);

        TransferRecord failed = transferTo(result, 2);
        assertThat(failed.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(failed.failure().stage()).isEqualTo(CallStage.LOOKUP);
        assertThat(failed.failure().kind()).isEqualTo(RemoteFailureKind.CONNECTION_REFUSED);
        assertThat(failed.failure().failureClass()).isEqualTo("java.rmi.ConnectException");
        assertThat(failed.requestBytes()).isNull();
        assertThat(failed.senderLamportAtSend()).isNull();
        assertThat(failed.latencyNanos()).isNull();
        assertThat(failed.verified()).isNull();
        assertThat(transferTo(result, 3).status()).isEqualTo(TransferStatus.DELIVERED);
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
        assertThat(result.verification()).isEqualTo(new Verification(null, 3, 2, 0, 1, null));
    }

    @Test
    @DisplayName("a failed call is classified as in Experiment 1 (read timeout: TIMED_OUT) and keeps what was sent")
    void callFailureIsClassified() {
        ScriptedCollectiveTransport transport = transport().failing(2, CallStage.CALL,
                new UnmarshalException("Error unmarshaling return header", new SocketTimeoutException("Read timed out")));
        CollectiveResult result = run(request(CollectiveOperation.BROADCAST, 1, ALL_LIVE, 5), transport);

        TransferRecord failed = transferTo(result, 1);
        assertThat(failed.failure().stage()).isEqualTo(CallStage.CALL);
        assertThat(failed.failure().kind()).isEqualTo(RemoteFailureKind.TIMED_OUT);
        assertThat(failed.failure().failureCauseClass()).isEqualTo("java.net.SocketTimeoutException");
        assertThat(failed.requestBytes()).isPositive();
        assertThat(failed.senderLamportAtSend()).isEqualTo(1L);
        assertThat(failed.replyBytes()).isNull();
    }

    @Test
    @DisplayName("an exception that is not a TransferException counts as a CALL failure")
    void otherExceptionIsCallStage() {
        ScriptedCollectiveTransport transport = transport().rewriting(2, reply -> {
            throw new IllegalStateException("endpoint broke");
        });
        CollectiveResult result = run(request(CollectiveOperation.BROADCAST, 1, ALL_LIVE, 5), transport);
        assertThat(transferTo(result, 1).failure().stage()).isEqualTo(CallStage.CALL);
        assertThat(transferTo(result, 1).failure().kind()).isEqualTo(RemoteFailureKind.METHOD_THREW);
        assertThat(transferTo(result, 1).failure().failureMessage()).isEqualTo("endpoint broke");
    }

    @Test
    @DisplayName("every remote transfer failing is FAILED, with every check unknown (passed null, never true)")
    void allFail() {
        ScriptedCollectiveTransport transport = transport();
        for (int node = 2; node <= 4; node++) {
            transport.failing(node, CallStage.LOOKUP, new java.rmi.ConnectException("refused"));
        }
        CollectiveResult result = run(request(CollectiveOperation.SCATTER, 1, ALL_LIVE, 4), transport);
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.FAILED);
        assertThat(result.verification()).isEqualTo(new Verification(null, 3, 0, 0, 3, null));
    }

    @Test
    @DisplayName("the failure message is single-line and cut to max-failure-message-length")
    void failureMessageIsCut() {
        MpiProperties shortMessages = new MpiProperties(1000, 10_000, 20, 8, 12);
        ScriptedCollectiveTransport transport = new ScriptedCollectiveTransport(shortMessages, 1, 2)
                .failing(2, CallStage.CALL, new java.rmi.RemoteException("first line\nsecond line, quite long"));
        CollectiveResult result = new CollectiveExecutor(shortMessages, stepping()).execute(
                new CollectiveRequest(ID, CollectiveOperation.BROADCAST, 1, List.of(1, 2), Set.of(1, 2), 3, SEED),
                new LamportClock(), transport);
        String message = transferTo(result, 1).failure().failureMessage();
        assertThat(message).hasSize(12).doesNotContain("\n");
    }

    // ------------------------------------------------------------------ replies that do not match (Q4)

    @Test
    @DisplayName("a reply from another node is a FAILED transfer (CALL, OTHER), never delivered or verified; later ranks are still attempted")
    void replyFromAnotherNode() {
        ScriptedCollectiveTransport transport = transport().rewriting(2, reply -> {
            ReceiveReceipt r = (ReceiveReceipt) reply;
            return new ReceiveReceipt(3, r.lamportTime(), r.collectiveId(), r.rank(), r.elementCount(), r.checksum());
        });
        LamportClock rootClock = new LamportClock();
        CollectiveResult result = new CollectiveExecutor(PROPERTIES, stepping())
                .execute(request(CollectiveOperation.BROADCAST, 1, ALL_LIVE, 5), rootClock, transport);

        TransferRecord failed = transferTo(result, 1);
        assertThat(failed.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(failed.failure().stage()).isEqualTo(CallStage.CALL);
        assertThat(failed.failure().kind()).isEqualTo(RemoteFailureKind.OTHER);
        assertThat(failed.failure().failureMessage())
                .isEqualTo("reply did not match the request: expected node 2, collective 7, rank 1; got node 3, collective 7, rank 1");
        assertThat(failed.failure().failureClass()).isNull();
        assertThat(failed.verified()).isNull();
        assertThat(failed.latencyNanos()).isNull();
        assertThat(failed.timeToFailureNanos()).isEqualTo(1000L);
        assertThat(failed.lamportAfterReply()).isNull();
        assertThat(failed.replyBytes()).isPositive();
        assertThat(transport.sent()).extracting(ScriptedCollectiveTransport.Sent::targetNodeId).containsExactly(2, 3, 4);
        assertThat(result.transfers().subList(2, 4)).extracting(TransferRecord::status).containsOnly(TransferStatus.DELIVERED);
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
    }

    @Test
    @DisplayName("a reply for another collective is a FAILED transfer; the next ranks go on")
    void replyForAnotherCollective() {
        ScriptedCollectiveTransport transport = transport().rewriting(3, reply -> {
            ReceiveReceipt r = (ReceiveReceipt) reply;
            return new ReceiveReceipt(r.nodeId(), r.lamportTime(), 8, r.rank(), r.elementCount(), r.checksum());
        });
        CollectiveResult result = run(request(CollectiveOperation.SCATTER, 1, ALL_LIVE, 8), transport);
        assertThat(transferTo(result, 2).failure().failureMessage()).contains("expected node 3, collective 7, rank 2; got node 3, collective 8");
        assertThat(transferTo(result, 3).status()).isEqualTo(TransferStatus.DELIVERED);
        assertThat(result.verification().wholeVectorMatches()).isNull();
    }

    @Test
    @DisplayName("a contribution for another rank, or of the wrong length, is a FAILED transfer and a hole; later ranks are still collected")
    void contributionForAnotherRankOrLength() {
        ScriptedCollectiveTransport transport = transport()
                .rewriting(2, reply -> {
                    Contribution c = (Contribution) reply;
                    return new Contribution(c.nodeId(), c.lamportTime(), c.collectiveId(), 3, c.values(), c.checksum());
                })
                .rewriting(3, reply -> {
                    Contribution c = (Contribution) reply;
                    double[] shorter = java.util.Arrays.copyOf(c.values(), c.length() - 1);
                    return new Contribution(c.nodeId(), c.lamportTime(), c.collectiveId(), c.rank(), shorter, VectorChecksum.of(shorter));
                });
        CollectiveResult result = run(request(CollectiveOperation.GATHER, 1, ALL_LIVE, 9), transport);

        assertThat(transferTo(result, 1).failure().failureMessage()).contains("expected node 2, collective 7, rank 1, 2 values; got node 2, collective 7, rank 3");
        assertThat(transferTo(result, 2).failure().failureMessage()).endsWith("got node 3, collective 7, rank 2, 1 values");
        assertThat(result.transfers().subList(1, 3)).extracting(TransferRecord::timeToFailureNanos).containsOnly(1000L);
        assertThat(result.transfers().subList(1, 3)).extracting(TransferRecord::latencyNanos).containsOnlyNulls();
        assertThat(transferTo(result, 3).status()).isEqualTo(TransferStatus.DELIVERED);
        assertThat(result.gathered()).extracting(GatheredBlock::hole).containsExactly(false, true, true, false);
        assertThat(result.assembled()).isNull();
    }

    @Test
    @DisplayName("no reply at all is a FAILED transfer with the reason 'no reply'")
    void noReply() {
        ScriptedCollectiveTransport transport = transport().rewriting(4, reply -> null);
        CollectiveResult result = run(request(CollectiveOperation.BROADCAST, 1, ALL_LIVE, 5), transport);
        assertThat(transferTo(result, 3).failure().failureMessage()).isEqualTo("reply did not match the request: no reply");
        assertThat(transferTo(result, 3).replyBytes()).isNull();
        assertThat(transferTo(result, 3).timeToFailureNanos()).isEqualTo(1000L);
        assertThat(transferTo(result, 3).latencyNanos()).isNull();
    }

    // ------------------------------------------------------------------ verification from what arrived

    @Test
    @DisplayName("broadcast: a receipt reporting another checksum is DELIVERED but not verified: passed is false")
    void broadcastChecksumMismatch() {
        String other = VectorChecksum.of(new double[] {1});
        ScriptedCollectiveTransport transport = transport().rewriting(2, reply -> {
            ReceiveReceipt r = (ReceiveReceipt) reply;
            return new ReceiveReceipt(r.nodeId(), r.lamportTime(), r.collectiveId(), r.rank(), r.elementCount(), other);
        });
        CollectiveResult result = run(request(CollectiveOperation.BROADCAST, 1, ALL_LIVE, 5), transport);
        assertThat(transferTo(result, 1).status()).isEqualTo(TransferStatus.DELIVERED);
        assertThat(transferTo(result, 1).verified()).isFalse();
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        assertThat(result.verification()).isEqualTo(new Verification(false, 3, 2, 1, 0, null));
    }

    @Test
    @DisplayName("scatter: a chunk reported with the wrong checksum makes the whole-vector check false")
    void scatterChunkMismatch() {
        String other = VectorChecksum.of(new double[] {1});
        ScriptedCollectiveTransport transport = transport().rewriting(4, reply -> {
            ReceiveReceipt r = (ReceiveReceipt) reply;
            return new ReceiveReceipt(r.nodeId(), r.lamportTime(), r.collectiveId(), r.rank(), r.elementCount(), other);
        });
        CollectiveResult result = run(request(CollectiveOperation.SCATTER, 1, ALL_LIVE, 8), transport);
        assertThat(result.verification().wholeVectorMatches()).isFalse();
        assertThat(result.verification().passed()).isFalse();
    }

    @Test
    @DisplayName("gather: a block whose values differ from what the node should have computed is placed but not verified")
    void gatherCorruptedBlock() {
        ScriptedCollectiveTransport transport = transport().rewriting(2, reply -> {
            Contribution c = (Contribution) reply;
            double[] values = c.values();
            values[0] += 1;
            return new Contribution(c.nodeId(), c.lamportTime(), c.collectiveId(), c.rank(), values, VectorChecksum.of(values));
        });
        CollectiveResult result = run(request(CollectiveOperation.GATHER, 1, ALL_LIVE, 8), transport);
        assertThat(transferTo(result, 1).status()).isEqualTo(TransferStatus.DELIVERED);
        assertThat(transferTo(result, 1).verified()).isFalse();
        assertThat(result.verification().wholeVectorMatches()).isFalse();
        assertThat(result.assembled()).hasSize(8);
    }

    @Test
    @DisplayName("gather with a crashed contributor: its block is a hole (null, never zeros) and nothing is assembled")
    void gatherHole() {
        ScriptedCollectiveTransport transport = transport().failing(3, CallStage.LOOKUP, new java.rmi.ConnectException("refused"));
        CollectiveResult result = run(request(CollectiveOperation.GATHER, 1, ALL_LIVE, 8), transport);
        GatheredBlock hole = result.gathered().get(2);
        assertThat(hole.hole()).isTrue();
        assertThat(hole.values()).isNull();
        assertThat(hole.checksum()).isNull();
        assertThat(result.gathered().get(3).values()).isEqualTo(PayloadGenerator.contribution(4, 2, SEED));
        assertThat(result.assembled()).isNull();
        assertThat(result.verification().wholeVectorMatches()).isNull();
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
    }

    // ------------------------------------------------------------------ time to failure (F1)

    @Test
    @DisplayName("a failed LOOKUP has exactly the scripted time to failure and no latency; the total still includes it")
    void failedLookupTimeToFailure() {
        // reads: start 0; check 10; call 100..350 (the lookup fails); end 400
        ScriptedNanoClock clock = new ScriptedNanoClock(0, 10, 100, 350, 400);
        ScriptedCollectiveTransport transport = new ScriptedCollectiveTransport(PROPERTIES, 1, 2)
                .failing(2, CallStage.LOOKUP, new java.rmi.ConnectException("refused"));
        CollectiveResult result = new CollectiveExecutor(PROPERTIES, clock).execute(
                new CollectiveRequest(ID, CollectiveOperation.BROADCAST, 1, List.of(1, 2), Set.of(1, 2), 3, SEED),
                new LamportClock(), transport);

        TransferRecord failed = transferTo(result, 1);
        assertThat(failed.timeToFailureNanos()).isEqualTo(250L);
        assertThat(failed.latencyNanos()).isNull();
        assertThat(result.totalNanos()).isEqualTo(390L);   // 400 - 10, measured separately
    }

    @Test
    @DisplayName("a failed CALL has exactly the scripted time to failure and no latency")
    void failedCallTimeToFailure() {
        ScriptedNanoClock clock = new ScriptedNanoClock(0, 10, 1000, 4000, 4100);
        ScriptedCollectiveTransport transport = new ScriptedCollectiveTransport(PROPERTIES, 1, 2).failing(2, CallStage.CALL,
                new UnmarshalException("Error unmarshaling return header", new SocketTimeoutException("Read timed out")));
        CollectiveResult result = new CollectiveExecutor(PROPERTIES, clock).execute(
                new CollectiveRequest(ID, CollectiveOperation.GATHER, 1, List.of(1, 2), Set.of(1, 2), 4, SEED),
                new LamportClock(), transport);

        TransferRecord failed = transferTo(result, 1);
        assertThat(failed.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(failed.timeToFailureNanos()).isEqualTo(3000L);
        assertThat(failed.latencyNanos()).isNull();
        assertThat(result.totalNanos()).isEqualTo(4090L);
    }

    @Test
    @DisplayName("DELIVERED has a latency and no time to failure; LOCAL and NOT_ATTEMPTED have neither")
    void eachStatusCarriesTheRightTime() {
        ScriptedNanoClock clock = new ScriptedNanoClock(0, 0, 0, 5_000_000_000L, 5_000_000_000L, 10_000_000_000L);
        CollectiveResult result = new CollectiveExecutor(PROPERTIES, clock)
                .execute(request(CollectiveOperation.BROADCAST, 1, ALL_LIVE, 5), new LamportClock(), transport());

        TransferRecord local = transferTo(result, 0);
        TransferRecord delivered = transferTo(result, 1);
        TransferRecord notAttempted = transferTo(result, 2);
        assertThat(local.status()).isEqualTo(TransferStatus.LOCAL);
        assertThat(local.latencyNanos()).isNull();
        assertThat(local.timeToFailureNanos()).isNull();
        assertThat(delivered.status()).isEqualTo(TransferStatus.DELIVERED);
        assertThat(delivered.latencyNanos()).isEqualTo(5_000_000_000L);
        assertThat(delivered.timeToFailureNanos()).isNull();
        assertThat(notAttempted.status()).isEqualTo(TransferStatus.NOT_ATTEMPTED);
        assertThat(notAttempted.latencyNanos()).isNull();
        assertThat(notAttempted.timeToFailureNanos()).isNull();
    }

    // ------------------------------------------------------------------ deadline, timing, sizes, Lamport

    @Test
    @DisplayName("after the deadline no transfer starts: the rest are NOT_ATTEMPTED (not FAILED), with a reason")
    void deadlineStopsNewTransfers() {
        // reads: start 0; rank 1: check 0, call 0..5e9, end 5e9; rank 2: check 1e10 (deadline 10000 ms reached)
        ScriptedNanoClock clock = new ScriptedNanoClock(0, 0, 0, 5_000_000_000L, 5_000_000_000L, 10_000_000_000L);
        ScriptedCollectiveTransport transport = transport();
        CollectiveResult result = new CollectiveExecutor(PROPERTIES, clock)
                .execute(request(CollectiveOperation.BROADCAST, 1, ALL_LIVE, 5), new LamportClock(), transport);

        assertThat(result.transfers()).extracting(TransferRecord::status).containsExactly(TransferStatus.LOCAL,
                TransferStatus.DELIVERED, TransferStatus.NOT_ATTEMPTED, TransferStatus.NOT_ATTEMPTED);
        assertThat(transferTo(result, 2).notAttemptedReason())
                .isEqualTo("The collective's deadline of 10000 ms had passed before this transfer.");
        assertThat(transferTo(result, 2).failure()).isNull();
        assertThat(transport.sent()).hasSize(1);
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
        assertThat(result.totalNanos()).isEqualTo(5_000_000_000L);
        assertThat(result.verification()).isEqualTo(new Verification(null, 3, 1, 0, 2, null));
    }

    @Test
    @DisplayName("a deadline already passed at the first transfer attempts nothing: FAILED, and no total time (null)")
    void deadlinePassedAtStart() {
        ScriptedNanoClock clock = new ScriptedNanoClock(0, 10_000_000_000L);
        ScriptedCollectiveTransport transport = transport();
        CollectiveResult result = new CollectiveExecutor(PROPERTIES, clock)
                .execute(request(CollectiveOperation.SCATTER, 1, ALL_LIVE, 4), new LamportClock(), transport);
        assertThat(transport.sent()).isEmpty();
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.FAILED);
        assertThat(result.totalNanos()).isNull();
    }

    @Test
    @DisplayName("latency is measured around each transport call; the total is measured separately and is not their sum")
    void totalIsMeasuredNotSummed() {
        CollectiveResult result = run(request(CollectiveOperation.BROADCAST, 1, ALL_LIVE, 5), transport());
        // Stepping clock (+1000 ns per read): start 0; each transfer reads check, call start, call end, end.
        assertThat(result.transfers().subList(1, 4)).extracting(TransferRecord::latencyNanos).containsOnly(1000L);
        assertThat(result.totalNanos()).isEqualTo(11_000L);   // 12000 (end of the last) - 1000 (check before the first)
        long sum = result.transfers().stream().filter(t -> t.latencyNanos() != null).mapToLong(TransferRecord::latencyNanos).sum();
        assertThat(sum).isEqualTo(3000L).isNotEqualTo(result.totalNanos());
    }

    @Test
    @DisplayName("sizes are PayloadSizer's of exactly the endpoint's arguments (double[] included) and of the reply")
    void sizesAreMeasured() {
        ScriptedCollectiveTransport transport = transport();
        CollectiveResult result = run(request(CollectiveOperation.BROADCAST, 1, ALL_LIVE, 50), transport);
        CallContext context = transport.sent().get(0).context();
        double[] vector = PayloadGenerator.vector(SEED, 50);
        TransferRecord first = transferTo(result, 1);

        assertThat(first.requestBytes()).isEqualTo(PayloadSizer.sizeOf(context, ID, 1, vector));
        assertThat(first.requestBytes()).isGreaterThan(50L * Double.BYTES);
        assertThat(first.replyBytes()).isPositive();

        CollectiveResult gather = run(request(CollectiveOperation.GATHER, 1, ALL_LIVE, 8), transport());
        assertThat(gather.transfers().get(1).requestBytes()).isEqualTo(
                PayloadSizer.sizeOf(new CallContext(1, 1), ID, 1, 2, SEED));
    }

    @Test
    @DisplayName("Lamport: the root ticks before each send and applies the receive rule to a matching reply; a failure merges nothing")
    void lamportRules() {
        ScriptedCollectiveTransport transport = new ScriptedCollectiveTransport(PROPERTIES, 1, 2, 3)
                .failing(3, CallStage.CALL, new java.rmi.RemoteException("gone"));
        LamportClock rootClock = new LamportClock();
        CollectiveResult result = new CollectiveExecutor(PROPERTIES, stepping()).execute(
                new CollectiveRequest(ID, CollectiveOperation.BROADCAST, 1, List.of(1, 2, 3), Set.of(1, 2, 3), 3, SEED),
                rootClock, transport);

        TransferRecord delivered = transferTo(result, 1);
        assertThat(delivered.senderLamportAtSend()).isEqualTo(1L);   // root tick
        assertThat(delivered.replyLamport()).isEqualTo(3L);          // node 2: update(1) -> 2, tick -> 3
        assertThat(delivered.lamportAfterReply()).isEqualTo(4L);     // root: max(1, 3) + 1
        assertThat(transport.clock(2).current()).isEqualTo(3L);
        assertThat(transferTo(result, 2).senderLamportAtSend()).isEqualTo(5L);
        assertThat(rootClock.current()).isEqualTo(5L);               // the failed transfer merged nothing
    }

    // ------------------------------------------------------------------ refusals

    @Test
    @DisplayName("a root that is down refuses the collective: nothing sent, no time, no verification")
    void refusedRootDown() {
        ScriptedCollectiveTransport transport = transport();
        CollectiveResult result = run(request(CollectiveOperation.BROADCAST, 3, Set.of(1, 2, 4), 5), transport);
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.REFUSED);
        assertThat(result.refusal().reason()).isEqualTo(RefusalReason.ROOT_DOWN);
        assertThat(result.transfers()).isEmpty();
        assertThat(result.totalNanos()).isNull();
        assertThat(result.verification()).isNull();
        assertThat(result.rootRank()).isNull();
        assertThat(result.skippedNodeIds()).containsExactly(3);
        assertThat(transport.sent()).isEmpty();
    }

    @Test
    @DisplayName("scatter or gather of 3 over 4 live ranks is refused; a broadcast of 1 value over 4 ranks is fine")
    void refusedCountBelowParticipants() {
        CollectiveResult scatter = run(request(CollectiveOperation.SCATTER, 1, ALL_LIVE, 3), transport());
        assertThat(scatter.refusal().reason()).isEqualTo(RefusalReason.COUNT_BELOW_PARTICIPANTS);
        assertThat(scatter.refusal().message())
                .isEqualTo("A scatter needs at least one element per live participant: count 3, 4 live participants.");
        assertThat(run(request(CollectiveOperation.GATHER, 1, ALL_LIVE, 3), transport()).outcome()).isEqualTo(CollectiveOutcome.REFUSED);
        assertThat(run(request(CollectiveOperation.BROADCAST, 1, ALL_LIVE, 1), transport()).outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
    }

    @Test
    @DisplayName("more values than max-payload-elements are refused; a root that is not a participant is refused")
    void refusedTooLargeOrForeignRoot() {
        CollectiveResult tooLarge = run(request(CollectiveOperation.BROADCAST, 1, ALL_LIVE, 1001), transport());
        assertThat(tooLarge.refusal().reason()).isEqualTo(RefusalReason.PAYLOAD_TOO_LARGE);
        assertThat(tooLarge.refusal().message()).isEqualTo("count 1001 is above max-payload-elements 1000.");
        assertThat(run(request(CollectiveOperation.BROADCAST, 9, ALL_LIVE, 5), transport()).refusal().reason())
                .isEqualTo(RefusalReason.ROOT_NOT_PARTICIPANT);
    }

    @Test
    @DisplayName("a null request, clock or transport is a programming error")
    void nullArguments() {
        CollectiveExecutor executor = new CollectiveExecutor(PROPERTIES, stepping());
        CollectiveRequest request = request(CollectiveOperation.BROADCAST, 1, ALL_LIVE, 5);
        assertThatThrownBy(() -> executor.execute(null, new LamportClock(), transport())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> executor.execute(request, null, transport())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> executor.execute(request, new LamportClock(), null)).isInstanceOf(NullPointerException.class);
    }
}
