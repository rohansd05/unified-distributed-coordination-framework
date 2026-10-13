package com.udcf.modules.mpi;

import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.RmiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Experiment 9 end to end (step E9b): CollectiveExecutor over RmiCollectiveTransport between three
 * real nodes on 127.0.0.1, at the local maximum payload (100000 doubles), with every checksum
 * verified from what the nodes report; a node crashed after the start is reported (PARTIAL), not
 * waited on; a recovered node takes part again.
 *
 * <p>Test ports (Track C, E9b): RMI base 28590 (28591-28593).</p>
 */
class CollectiveEndToEndTest {

    private static final int BASE = 28590;
    private static final int MAX = CollectiveRmiFixture.LOCAL.maxPayloadElements();

    private CollectiveRmiFixture fixture;
    private CollectiveExecutor executor;

    @BeforeEach
    void setUp() {
        fixture = new CollectiveRmiFixture(BASE, CollectiveRmiFixture.LOCAL);
        fixture.startAll();
        executor = new CollectiveExecutor(CollectiveRmiFixture.LOCAL, System::nanoTime);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private CollectiveResult run(long id, CollectiveOperation operation, int count) {
        CollectiveRequest request = new CollectiveRequest(id, operation, 1, List.of(1, 2, 3), Set.of(1, 2, 3), count, 42);
        return executor.execute(request, fixture.cluster.node(1).clock(), fixture.transport);
    }

    @Test
    @DisplayName("broadcast of 100000 doubles completes on every node with measured times and every checksum verified")
    void broadcast() {
        CollectiveResult result = run(1, CollectiveOperation.BROADCAST, MAX);
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        assertThat(result.verification().passed()).isTrue();
        assertThat(result.totalNanos()).isPositive();
        assertThat(result.transfers().subList(1, 3)).allSatisfy(t -> {
            assertThat(t.latencyNanos()).isPositive();
            assertThat(t.requestBytes()).isGreaterThan(8L * MAX);
        });
        assertThat(fixture.logic(2).received(1)).get().isEqualTo(PayloadGenerator.vector(42, MAX));
        assertThat(fixture.logic(3).received(1)).get().isEqualTo(PayloadGenerator.vector(42, MAX));
    }

    @Test
    @DisplayName("scatter of 100000 doubles: each node holds exactly its block, and the blocks tile the vector")
    void scatter() {
        CollectiveResult result = run(2, CollectiveOperation.SCATTER, MAX);
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        assertThat(result.verification().wholeVectorMatches()).isTrue();
        double[] vector = PayloadGenerator.vector(42, MAX);
        List<BlockPartition.Block> blocks = BlockPartition.of(MAX, 3);
        assertThat(fixture.logic(2).received(2)).get().isEqualTo(BlockPartition.slice(vector, blocks.get(1)));
        assertThat(fixture.logic(3).received(2)).get().isEqualTo(BlockPartition.slice(vector, blocks.get(2)));
    }

    @Test
    @DisplayName("gather of 100000 doubles: every node computes its block, the root assembles n values equal to the expected ones")
    void gather() {
        CollectiveResult result = run(3, CollectiveOperation.GATHER, MAX);
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        assertThat(result.verification().passed()).isTrue();
        assertThat(result.verification().wholeVectorMatches()).isTrue();
        assertThat(result.assembled()).hasSize(MAX);
    }

    @Test
    @DisplayName("a node crashed after the start is reported (PARTIAL, stage, kind, time to failure); the later rank is still attempted")
    void crashedParticipant() {
        fixture.cluster.crash(2);   // after the live snapshot (all three), as if it died during the collective

        CollectiveResult result = run(4, CollectiveOperation.BROADCAST, 1000);

        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
        TransferRecord failed = result.transfers().get(1);
        assertThat(failed.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(failed.failure().stage()).isEqualTo(CallStage.LOOKUP);
        assertThat(failed.failure().kind()).isIn(RmiTestSupport.NODE_GONE);
        assertThat(failed.timeToFailureNanos()).isNotNull().isNotNegative();
        assertThat(failed.latencyNanos()).isNull();
        assertThat(result.transfers().get(2).status()).isEqualTo(TransferStatus.DELIVERED);
        System.out.println("E9b failure matrix | end to end, crashed participant | " + failed.failure().stage() + " | "
                + failed.failure().kind() + " | " + failed.failure().failureClass() + " <- " + failed.failure().failureCauseClass());
    }

    @Test
    @DisplayName("a recovered node takes part again, with a fresh endpoint that has lost what it held")
    void recoveredNodeTakesPartAgain() throws Exception {
        assertThat(run(5, CollectiveOperation.BROADCAST, 100).outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        fixture.cluster.crash(2);
        RmiTestSupport.drainUntilRefused(() -> fixture.lookup.lookup(2, CollectiveEndpoint.BOUND_NAME, CollectiveEndpoint.class));
        fixture.cluster.recover(2);

        CollectiveResult again = run(6, CollectiveOperation.BROADCAST, 100);

        assertThat(again.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        assertThat(fixture.logic(2).received(5)).isEmpty();
        assertThat(fixture.logic(2).received(6)).isPresent();
    }

    @Test
    @DisplayName("Lamport (L4) is visible: each send is before the node's reply, and the root's time after it is later still")
    void lamportOrder() {
        CollectiveResult result = run(7, CollectiveOperation.GATHER, 30);
        for (TransferRecord transfer : result.transfers().subList(1, 3)) {
            assertThat(transfer.senderLamportAtSend()).isLessThan(transfer.replyLamport());
            assertThat(transfer.replyLamport()).isLessThan(transfer.lamportAfterReply());
        }
        assertThat(fixture.cluster.node(3).clock().current()).isGreaterThanOrEqualTo(result.transfers().get(2).replyLamport());
        assertThat(fixture.cluster.node(1).clock().current()).isEqualTo(result.transfers().get(2).lamportAfterReply());
    }
}
