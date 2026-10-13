package com.udcf.modules.mpi;

import com.udcf.core.clock.LamportClock;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.modules.rmi.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The checksum constants come from the Python script quoted in the E9a section of docs/tracks/track-c-jai.md. */
class CollectiveEndpointLogicTest {

    private static final MpiProperties PROPERTIES = new MpiProperties(1000, 10_000, 20, 3, 300);
    private static final CallContext FROM_ROOT_AT_5 = new CallContext(1, 5);

    private final LamportClock clock = new LamportClock();
    private final CollectiveEndpointLogic node = new CollectiveEndpointLogic(2, clock, PROPERTIES);

    @Test
    @DisplayName("receive: updates the clock past the sender, keeps a copy, and reports the count and checksum it received")
    void receiveReportsWhatArrived() {
        double[] values = PayloadGenerator.vector(42, 3);
        ReceiveReceipt receipt = node.receive(FROM_ROOT_AT_5, 7, 1, values);

        assertThat(receipt.nodeId()).isEqualTo(2);
        assertThat(receipt.collectiveId()).isEqualTo(7);
        assertThat(receipt.rank()).isEqualTo(1);
        assertThat(receipt.elementCount()).isEqualTo(3);
        assertThat(receipt.checksum()).isEqualTo("5f1b64cf99f6cdb58eb25806ebba71ba679616a9a96c76d9b517872850d3e047");
        assertThat(receipt.lamportTime()).isEqualTo(7);   // update(5) -> 6, then tick -> 7
        values[0] = 0;
        assertThat(node.received(7)).get().isEqualTo(PayloadGenerator.vector(42, 3));
        assertThat(node.storedRanks()).containsEntry(7L, 1);
    }

    @Test
    @DisplayName("contribute: produces exactly the generator's block for this node, stamped after the receive rule")
    void contributeIsTheNodesOwnBlock() {
        Contribution contribution = node.contribute(FROM_ROOT_AT_5, 9, 3, 2, 42);

        assertThat(contribution.values()).isEqualTo(PayloadGenerator.contribution(2, 2, 42));
        assertThat(contribution.checksum()).isEqualTo("4056f0d181a7ebe6ce20dfe4981408784dee8ca4c94e3088d850afd82dba1419");
        assertThat(contribution.rank()).isEqualTo(3);
        assertThat(contribution.lamportTime()).isEqualTo(7);
        assertThat(node.storedIds()).isEmpty();
    }

    @Test
    @DisplayName("the store keeps at most receive-store-size payloads and drops the oldest")
    void storeIsBounded() {
        for (long id = 1; id <= 5; id++) {
            node.receive(FROM_ROOT_AT_5, id, 0, new double[] {id});
        }
        assertThat(node.storedIds()).containsExactly(3L, 4L, 5L);
        assertThat(node.received(1)).isEmpty();
    }

    @Test
    @DisplayName("a repeated collective id replaces its payload and moves to the newest position")
    void duplicateIdReplacesAndBecomesNewest() {
        node.receive(FROM_ROOT_AT_5, 1, 0, new double[] {1});
        node.receive(FROM_ROOT_AT_5, 2, 0, new double[] {2});
        node.receive(FROM_ROOT_AT_5, 3, 0, new double[] {3});
        node.receive(FROM_ROOT_AT_5, 1, 4, new double[] {9, 9});

        assertThat(node.storedIds()).containsExactly(2L, 3L, 1L);
        assertThat(node.received(1)).get().isEqualTo(new double[] {9, 9});
        assertThat(node.storedRanks()).containsEntry(1L, 4);

        node.receive(FROM_ROOT_AT_5, 4, 0, new double[] {4});   // the oldest is now 2, not 1
        assertThat(node.storedIds()).containsExactly(3L, 1L, 4L);
    }

    @Test
    @DisplayName("an invalid call changes nothing: not the clock, not the store")
    void invalidCallChangesNothing() {
        assertThatThrownBy(() -> node.receive(FROM_ROOT_AT_5, 0, 0, new double[] {1})).hasMessage("collectiveId must be >= 1, was 0");
        assertThatThrownBy(() -> node.receive(FROM_ROOT_AT_5, 1, -1, new double[] {1})).hasMessage("rank must be >= 0, was -1");
        assertThatThrownBy(() -> node.receive(FROM_ROOT_AT_5, 1, 0, new double[0]))
                .hasMessage("a payload must have 1 to 1000 elements, was 0");
        assertThatThrownBy(() -> node.receive(FROM_ROOT_AT_5, 1, 0, new double[1001]))
                .hasMessage("a payload must have 1 to 1000 elements, was 1001");
        assertThatThrownBy(() -> node.receive(null, 1, 0, new double[] {1})).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> node.contribute(FROM_ROOT_AT_5, 1, 0, 1001, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(clock.current()).isZero();
        assertThat(node.storedIds()).isEmpty();
    }

    @Test
    @DisplayName("forNode uses the node's id and its own shared clock")
    void forNodeUsesTheNodesClock() {
        LamportClock shared = new LamportClock();
        ClusterNode clusterNode = mock(ClusterNode.class);
        when(clusterNode.id()).thenReturn(4);
        when(clusterNode.clock()).thenReturn(shared);

        CollectiveEndpointLogic logic = CollectiveEndpointLogic.forNode(clusterNode, PROPERTIES);
        logic.receive(new CallContext(1, 10), 1, 0, new double[] {1});

        assertThat(logic.nodeId()).isEqualTo(4);
        assertThat(shared.current()).isEqualTo(12);
        assertThatThrownBy(() -> new CollectiveEndpointLogic(0, shared, PROPERTIES)).hasMessage("nodeId must be >= 1, was 0");
    }

    @Test
    @DisplayName("concurrent receives of distinct ids: every receipt is right, the store stays bounded, no clock step is lost")
    void concurrentDistinctIds() throws Exception {
        int threads = 8;
        int perThread = 40;
        List<Callable<List<ReceiveReceipt>>> tasks = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            int thread = t;
            tasks.add(() -> {
                ready.countDown();
                go.await();
                List<ReceiveReceipt> receipts = new ArrayList<>();
                for (int i = 0; i < perThread; i++) {
                    long id = 1L + thread * perThread + i;
                    receipts.add(node.receive(new CallContext(1, 0), id, thread, PayloadGenerator.vector(id, 5)));
                }
                return receipts;
            });
        }
        List<ReceiveReceipt> all = runTogether(tasks, ready, go);

        assertThat(all).hasSize(threads * perThread);
        for (ReceiveReceipt receipt : all) {
            assertThat(receipt.checksum()).isEqualTo(VectorChecksum.of(PayloadGenerator.vector(receipt.collectiveId(), 5)));
        }
        assertThat(node.storedIds()).hasSize(PROPERTIES.receiveStoreSize());
        assertThat(clock.current()).isEqualTo(2L * threads * perThread);   // one update and one tick each, all atomic
        assertThat(all.stream().map(ReceiveReceipt::lamportTime).distinct().count()).isEqualTo((long) threads * perThread);
    }

    @Test
    @DisplayName("concurrent receives of one id: the store holds one whole payload that some caller sent, never a mix")
    void concurrentSameId() throws Exception {
        int threads = 8;
        List<Callable<List<ReceiveReceipt>>> tasks = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            int thread = t;
            tasks.add(() -> {
                ready.countDown();
                go.await();
                List<ReceiveReceipt> receipts = new ArrayList<>();
                for (int i = 0; i < 25; i++) {
                    receipts.add(node.receive(new CallContext(1, 0), 99, thread, PayloadGenerator.vector(thread, 50)));
                }
                return receipts;
            });
        }
        runTogether(tasks, ready, go);

        assertThat(node.storedIds()).containsExactly(99L);
        double[] stored = node.received(99).orElseThrow();
        int rank = node.storedRanks().get(99L);
        assertThat(stored).isEqualTo(PayloadGenerator.vector(rank, 50));
    }

    private static List<ReceiveReceipt> runTogether(List<Callable<List<ReceiveReceipt>>> tasks, CountDownLatch ready,
                                                    CountDownLatch go) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<List<ReceiveReceipt>>> futures = new ArrayList<>();
            for (Callable<List<ReceiveReceipt>> task : tasks) {
                futures.add(pool.submit(task));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<ReceiveReceipt> all = new ArrayList<>();
            for (Future<List<ReceiveReceipt>> future : futures) {
                all.addAll(future.get(30, TimeUnit.SECONDS));
            }
            return all;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
