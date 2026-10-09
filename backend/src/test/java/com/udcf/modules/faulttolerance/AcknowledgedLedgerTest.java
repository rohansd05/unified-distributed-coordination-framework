package com.udcf.modules.faulttolerance;

import com.udcf.modules.replication.ApplyResult;
import com.udcf.modules.replication.ConsistencyModel;
import com.udcf.modules.replication.DataItem;
import com.udcf.modules.replication.WriteResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The ledger of updates the client was told had succeeded. */
class AcknowledgedLedgerTest {

    private final AcknowledgedLedger ledger = new AcknowledgedLedger();

    @Test
    @DisplayName("records synchronous and asynchronous confirmations, copying the reported simulated delay")
    void recordsBothModes() {
        AcknowledgedUpdate sync = ledger.record(1, write("setting-0001", 10, 2, ConsistencyModel.SYNCHRONOUS, 0), 5L);
        AcknowledgedUpdate async = ledger.record(2, write("setting-0002", 11, 2, ConsistencyModel.ASYNCHRONOUS, 450), 6L);

        assertThat(sync.simulated()).isFalse();
        assertThat(sync.simulatedDelayMillis()).isZero();
        assertThat(async.simulated()).isTrue();
        assertThat(async.simulatedDelayMillis()).isEqualTo(450);
        assertThat(async.acknowledgedAtNanos()).isEqualTo(6L);
        assertThat(async.item().epoch()).isEqualTo(2);
        assertThat(ledger.size()).isEqualTo(2);
        assertThat(ledger.contains("setting-0002")).isTrue();
    }

    @Test
    @DisplayName("the snapshot is detached and in sequence order")
    void snapshotInSequenceOrder() {
        ledger.record(3, write("c", 3, 2, ConsistencyModel.SYNCHRONOUS, 0), 1L);
        ledger.record(1, write("a", 1, 2, ConsistencyModel.SYNCHRONOUS, 0), 2L);
        ledger.record(2, write("b", 2, 2, ConsistencyModel.SYNCHRONOUS, 0), 3L);

        List<AcknowledgedUpdate> snapshot = ledger.snapshot();
        assertThat(snapshot).extracting(AcknowledgedUpdate::sequence).containsExactly(1, 2, 3);
        ledger.clear();
        assertThat(snapshot).hasSize(3);
        assertThat(ledger.size()).isZero();
        assertThat(ledger.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("a key acknowledged twice keeps the newer version")
    void sameKeyKeepsNewerVersion() {
        ledger.record(1, write("k", 5, 2, ConsistencyModel.SYNCHRONOUS, 0), 1L);
        AcknowledgedUpdate held = ledger.record(2, write("k", 9, 3, ConsistencyModel.SYNCHRONOUS, 0), 2L);
        assertThat(held.item().epoch()).isEqualTo(3);

        AcknowledgedUpdate stillHeld = ledger.record(3, write("k", 20, 2, ConsistencyModel.SYNCHRONOUS, 0), 3L);
        assertThat(stillHeld.item().epoch()).isEqualTo(3);
        assertThat(stillHeld.sequence()).isEqualTo(2);
        assertThat(ledger.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent recording loses nothing")
    void concurrentRecordingLosesNothing() throws Exception {
        int threads = 8;
        int perThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int base = t * perThread;
                done.add(pool.submit(() -> {
                    go.await();
                    for (int i = 1; i <= perThread; i++) {
                        int seq = base + i;
                        ledger.record(seq, write("k" + seq, seq, 2, ConsistencyModel.SYNCHRONOUS, 0), seq);
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : done) {
                f.get(10, TimeUnit.SECONDS);
            }
            assertThat(ledger.size()).isEqualTo(threads * perThread);
            assertThat(ledger.snapshot()).extracting(AcknowledgedUpdate::sequence)
                    .isSorted().doesNotHaveDuplicates().hasSize(threads * perThread);
        } finally {
            pool.shutdownNow();
        }
    }

    static WriteResult write(String key, long lamport, long epoch, ConsistencyModel model, long delay) {
        return new WriteResult(new DataItem(key, "v", lamport, 4, epoch), model, ApplyResult.APPLIED, 1.0, delay,
                List.of(1, 2), CompletableFuture.completedFuture(List.of()));
    }
}
