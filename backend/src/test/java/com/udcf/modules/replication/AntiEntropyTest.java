package com.udcf.modules.replication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AntiEntropyTest {

    private static DataItem item(String key, String value, long lamport, int origin, long epoch) {
        return new DataItem(key, value, lamport, origin, epoch);
    }

    private static DataStore storeWith(DataItem... items) {
        DataStore store = new DataStore();
        for (DataItem i : items) {
            store.apply(i);
        }
        return store;
    }

    @Test
    @DisplayName("plan is the whole store in key order, detached from later writes and unmodifiable")
    void planIsDetachedKeyOrderedUnmodifiable() {
        DataStore source = storeWith(item("b", "2", 2, 1, 1), item("a", "1", 1, 1, 1));
        List<DataItem> plan = AntiEntropy.plan(source);
        assertThat(plan).extracting(DataItem::key).containsExactly("a", "b");

        source.apply(item("c", "3", 3, 1, 1));
        assertThat(plan).hasSize(2);
        assertThatThrownBy(() -> plan.add(item("z", "z", 1, 1, 1))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("planning while other threads write the source never throws")
    void planWhileSourceIsWrittenNeverThrows() throws Exception {
        DataStore source = new DataStore();
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> {
                start.await(20, TimeUnit.SECONDS);
                for (int i = 0; i < 5000; i++) {
                    source.apply(item("k" + i, "v", i, 1, 1));
                }
                return null;
            });
            Future<?> planner = pool.submit(() -> {
                start.await(20, TimeUnit.SECONDS);
                for (int i = 0; i < 300; i++) {
                    DataStore target = new DataStore();
                    AntiEntropyResult r = AntiEntropy.merge(target, AntiEntropy.plan(source), 1);
                    assertThat(r.applied()).isEqualTo(r.pushed());
                }
                return null;
            });
            writer.get(20, TimeUnit.SECONDS);
            planner.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("merging into an empty replica applies everything")
    void mergeIntoEmptyAppliesAll() {
        DataStore source = storeWith(item("a", "1", 1, 1, 1), item("b", "2", 2, 1, 1));
        DataStore target = new DataStore();
        AntiEntropyResult r = AntiEntropy.merge(target, AntiEntropy.plan(source), 1);
        assertThat(r).isEqualTo(new AntiEntropyResult(2, 2, 0, 0, 0));
        assertThat(target.snapshot()).isEqualTo(source.snapshot());
    }

    @Test
    @DisplayName("items the replica already holds exactly count as alreadyCurrent, not stale")
    void duplicatesCountAsAlreadyCurrent() {
        DataItem a = item("a", "1", 1, 1, 1);
        DataStore source = storeWith(a);
        DataStore target = storeWith(a);
        AntiEntropyResult r = AntiEntropy.merge(target, AntiEntropy.plan(source), 1);
        assertThat(r).isEqualTo(new AntiEntropyResult(1, 0, 1, 0, 0));
        assertThat(target.staleCount()).isZero();
    }

    @Test
    @DisplayName("items the replica holds a newer version of count as stale and stay newer")
    void targetNewerCountsStale() {
        DataStore source = storeWith(item("a", "old", 1, 1, 1));
        DataItem newer = item("a", "new", 9, 2, 1);
        DataStore target = storeWith(newer);
        AntiEntropyResult r = AntiEntropy.merge(target, AntiEntropy.plan(source), 1);
        assertThat(r).isEqualTo(new AntiEntropyResult(1, 0, 0, 1, 0));
        assertThat(target.get("a")).contains(newer);
    }

    @Test
    @DisplayName("the sender's epoch decides the fence: the same batch is refused from epoch 1 and accepted from epoch 2")
    void senderEpochDecides() {
        List<DataItem> batch = List.of(item("a", "1", 1, 1, 1), item("b", "2", 2, 1, 1));
        DataStore target = new DataStore();
        target.observeEpoch(2);

        assertThat(AntiEntropy.merge(target, batch, 1)).isEqualTo(new AntiEntropyResult(2, 0, 0, 0, 2));
        assertThat(target.size()).isZero();

        assertThat(AntiEntropy.merge(target, batch, 2)).isEqualTo(new AntiEntropyResult(2, 2, 0, 0, 0));
        assertThat(target.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("a superseded sender is refused for the whole batch: nothing stored, nothing raised")
    void supersededSenderRefusedWholeBatch() {
        DataItem held = item("a", "current", 50, 3, 3);
        DataStore target = storeWith(held);
        List<DataItem> batch = List.of(item("a", "x", 99, 1, 2), item("b", "y", 1, 1, 1), item("c", "z", 1, 1, 2));

        AntiEntropyResult r = AntiEntropy.merge(target, batch, 2);

        assertThat(r).isEqualTo(new AntiEntropyResult(3, 0, 0, 0, 3));
        assertThat(target.snapshot()).containsOnlyKeys("a").containsEntry("a", held);
        assertThat(target.epoch()).isEqualTo(3);
    }

    @Test
    @DisplayName("a@epoch1 missed by a backup is repaired by anti-entropy from the new primary on epoch 2")
    void oldEpochItemFromCurrentSenderRepairsBackup() {
        DataItem a = item("a", "written-in-term-1", 10, 1, 1);
        // Node 2 received a in term 1, then was promoted: epoch 2, and wrote b.
        DataStore newPrimary = storeWith(a);
        newPrimary.observeEpoch(2);
        DataItem b = item("b", "written-in-term-2", 12, 2, 2);
        newPrimary.apply(b);
        // Node 3 missed a (async in-flight loss), then received b, which taught it epoch 2.
        DataStore backup = new DataStore();
        backup.apply(b, 2);
        assertThat(backup.epoch()).isEqualTo(2);
        assertThat(backup.get("a")).isEmpty();

        AntiEntropyResult r = AntiEntropy.merge(backup, AntiEntropy.plan(newPrimary), 2);

        assertThat(r).isEqualTo(new AntiEntropyResult(2, 1, 1, 0, 0));
        assertThat(backup.get("a")).contains(a);
        assertThat(ConsistencyCheck.compare(2, Map.of(2, newPrimary.snapshot(), 3, backup.snapshot())).consistent())
                .isTrue();
    }

    @Test
    @DisplayName("a sender on a newer epoch raises the backup's epoch with its push")
    void senderEpochRaisesBackupEpoch() {
        DataStore backup = new DataStore();
        AntiEntropy.merge(backup, List.of(item("a", "1", 1, 1, 1)), 2);
        assertThat(backup.epoch()).isEqualTo(2);
        assertThat(backup.apply(item("late", "from-old-primary", 99, 1, 1))).isEqualTo(ApplyResult.STALE_EPOCH);
    }

    @Test
    @DisplayName("an item whose epoch is above the sender's rejects the whole batch before anything is applied")
    void itemEpochAboveSenderRejected() {
        DataStore target = new DataStore();
        List<DataItem> batch = List.of(item("a", "1", 1, 1, 1), item("b", "2", 2, 1, 3));
        assertThatThrownBy(() -> AntiEntropy.merge(target, batch, 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nothing was applied");
        assertThat(target.size()).isZero();
        assertThat(target.epoch()).isEqualTo(1);
        assertThat(target.appliedCount() + target.staleEpochCount()).isZero();
    }

    @Test
    @DisplayName("if the target's epoch rises above the sender's mid-merge, later items are stale-epoch and not stored")
    void targetEpochRisingMidMergeFencesLaterItems() {
        DataStore[] holder = new DataStore[1];
        DataStore raising = new DataStore(new DataStore.Probe() {
            @Override
            public void beforeApply(DataItem item) {
                if (item.key().equals("c")) {
                    // A newer primary's epoch arrives between items b and c (no lock is held here).
                    holder[0].observeEpoch(3);
                }
            }

            @Override
            public void afterFenceCheck(DataItem item) {
            }
        });
        holder[0] = raising;

        List<DataItem> batch = List.of(item("a", "1", 1, 1, 2), item("b", "2", 2, 1, 1),
                item("c", "3", 3, 1, 2), item("d", "4", 4, 1, 1));
        AntiEntropyResult r = AntiEntropy.merge(raising, batch, 2);

        assertThat(r).isEqualTo(new AntiEntropyResult(4, 2, 0, 0, 2));
        assertThat(raising.snapshot()).containsOnlyKeys("a", "b");
        assertThat(raising.epoch()).isEqualTo(3);
    }

    @Test
    @DisplayName("applied + alreadyCurrent + stale + staleEpoch always equals pushed, with all four outcomes in one merge")
    void countsAlwaysSumToPushed() {
        DataStore[] holder = new DataStore[1];
        DataStore target = new DataStore(new DataStore.Probe() {
            @Override
            public void beforeApply(DataItem item) {
                if (item.key().equals("z")) {
                    holder[0].observeEpoch(5);
                }
            }

            @Override
            public void afterFenceCheck(DataItem item) {
            }
        });
        holder[0] = target;
        DataItem same = item("dup", "v", 3, 1, 1);
        target.apply(same);
        target.apply(item("newer", "kept", 9, 1, 1));

        List<DataItem> batch = new ArrayList<>(List.of(
                item("fresh", "v", 1, 1, 1),       // applied
                same,                               // already current
                item("newer", "older", 2, 1, 1),   // stale
                item("z", "v", 1, 1, 1)));          // stale epoch (target raised to 5 first)
        AntiEntropyResult r = AntiEntropy.merge(target, batch, 1);

        assertThat(r).isEqualTo(new AntiEntropyResult(4, 1, 1, 1, 1));
        assertThat(r.applied() + r.alreadyCurrent() + r.stale() + r.staleEpoch()).isEqualTo(r.pushed());
    }

    @Test
    @DisplayName("AntiEntropyResult rejects counts that do not add up, or are negative")
    void resultRejectsInconsistentCounts() {
        assertThatThrownBy(() -> new AntiEntropyResult(3, 1, 1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must equal pushed");
        assertThatThrownBy(() -> new AntiEntropyResult(0, -1, 1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AntiEntropy.merge(null, List.of(), 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AntiEntropy.merge(new DataStore(), null, 1)).isInstanceOf(NullPointerException.class);
    }
}
