package com.udcf.modules.replication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DataStoreTest {

    private static final long WAIT_SECONDS = 20;

    private static DataItem item(String key, String value, long lamport, int origin, long epoch) {
        return new DataItem(key, value, lamport, origin, epoch);
    }

    private static long attempts(DataStore s) {
        return s.appliedCount() + s.duplicateCount() + s.staleCount() + s.staleEpochCount();
    }

    // ------------------------------------------------------------------ outcomes

    @Test
    @DisplayName("the first write of a key is APPLIED")
    void firstWriteApplied() {
        DataStore store = new DataStore();
        DataItem a = item("k", "a", 1, 1, 1);
        assertThat(store.apply(a)).isEqualTo(ApplyResult.APPLIED);
        assertThat(store.get("k")).contains(a);
        assertThat(store.size()).isEqualTo(1);
        assertThat(ApplyResult.APPLIED.stored()).isTrue();
    }

    @Test
    @DisplayName("a newer version replaces the stored one")
    void newerReplaces() {
        DataStore store = new DataStore();
        store.apply(item("k", "a", 1, 1, 1));
        DataItem b = item("k", "b", 2, 1, 1);
        assertThat(store.apply(b)).isEqualTo(ApplyResult.APPLIED);
        assertThat(store.get("k")).contains(b);
    }

    @Test
    @DisplayName("an older version is STALE and the newer value is kept")
    void olderIsStaleAndKeepsNewer() {
        DataStore store = new DataStore();
        DataItem newer = item("k", "new", 5, 1, 1);
        store.apply(newer);
        assertThat(store.apply(item("k", "old", 4, 1, 1))).isEqualTo(ApplyResult.STALE);
        assertThat(ApplyResult.STALE.stored()).isFalse();
        assertThat(store.get("k")).contains(newer);
        assertThat(store.staleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an identical redelivery is DUPLICATE, not stale, and changes nothing")
    void identicalIsDuplicateNotStale() {
        DataStore store = new DataStore();
        DataItem a = item("k", "a", 3, 2, 1);
        store.apply(a);
        assertThat(store.apply(new DataItem("k", "a", 3, 2, 1))).isEqualTo(ApplyResult.DUPLICATE);
        assertThat(store.duplicateCount()).isEqualTo(1);
        assertThat(store.staleCount()).isZero();
        assertThat(store.get("k")).contains(a);
    }

    @Test
    @DisplayName("known limitation: the same version with a different value is STALE, never DUPLICATE, never overwrites")
    void sameVersionDifferentValueIsStaleNeverOverwrites() {
        DataStore store = new DataStore();
        DataItem a = item("k", "a", 3, 2, 1);
        store.apply(a);
        assertThat(store.apply(item("k", "forged", 3, 2, 1))).isEqualTo(ApplyResult.STALE);
        assertThat(store.get("k")).contains(a);
        assertThat(store.staleCount()).isEqualTo(1);
        assertThat(store.duplicateCount()).isZero();
    }

    @Test
    @DisplayName("two versions delivered in either order end in the same state")
    void outOfOrderPairConvergesEitherOrder() {
        DataItem v1 = item("k", "v1", 1, 1, 1);
        DataItem v2 = item("k", "v2", 2, 1, 1);
        DataStore inOrder = new DataStore();
        inOrder.apply(v1);
        inOrder.apply(v2);
        DataStore reversed = new DataStore();
        reversed.apply(v2);
        assertThat(reversed.apply(v1)).isEqualTo(ApplyResult.STALE);
        assertThat(reversed.snapshot()).isEqualTo(inOrder.snapshot());
    }

    @Test
    @DisplayName("ApplyResult.stored() is true for APPLIED only")
    void storedOnlyForApplied() {
        assertThat(ApplyResult.values()).filteredOn(ApplyResult::stored).containsExactly(ApplyResult.APPLIED);
        assertThat(ApplyResult.values()).containsExactly(
                ApplyResult.APPLIED, ApplyResult.DUPLICATE, ApplyResult.STALE, ApplyResult.STALE_EPOCH);
    }

    @Test
    @DisplayName("the four counters always add up to the attempts")
    void countersSumToAttempts() {
        DataStore store = new DataStore();
        store.apply(item("a", "1", 2, 1, 1));       // applied
        store.apply(item("a", "1", 2, 1, 1));       // duplicate
        store.apply(item("a", "0", 1, 1, 1));       // stale
        store.observeEpoch(2);
        store.apply(item("b", "1", 9, 1, 1));       // stale epoch
        store.apply(item("b", "2", 10, 1, 2));      // applied
        assertThat(store.appliedCount()).isEqualTo(2);
        assertThat(store.duplicateCount()).isEqualTo(1);
        assertThat(store.staleCount()).isEqualTo(1);
        assertThat(store.staleEpochCount()).isEqualTo(1);
        assertThat(attempts(store)).isEqualTo(5);
    }

    // ------------------------------------------------------------------ epochs

    @Test
    @DisplayName("an item on a higher epoch raises the store's epoch")
    void higherEpochItemRaisesEpoch() {
        DataStore store = new DataStore();
        assertThat(store.epoch()).isEqualTo(DataStore.INITIAL_EPOCH).isEqualTo(1);
        assertThat(store.apply(item("k", "v", 1, 2, 3))).isEqualTo(ApplyResult.APPLIED);
        assertThat(store.epoch()).isEqualTo(3);
    }

    @Test
    @DisplayName("a superseded sender is STALE_EPOCH: nothing stored, nothing raised, even for a new key")
    void supersededSenderIsStaleEpochNothingStoredNothingRaised() {
        DataStore store = new DataStore();
        store.observeEpoch(3);
        assertThat(store.apply(item("fresh", "v", 50, 1, 1), 2)).isEqualTo(ApplyResult.STALE_EPOCH);
        assertThat(ApplyResult.STALE_EPOCH.stored()).isFalse();
        assertThat(store.get("fresh")).isEmpty();
        assertThat(store.epoch()).isEqualTo(3);
        assertThat(store.staleEpochCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a sender on a higher epoch raises the store's epoch first, then the item is applied")
    void higherSenderRaisesThenApplies() {
        DataStore store = new DataStore();
        assertThat(store.apply(item("k", "v", 5, 2, 1), 2)).isEqualTo(ApplyResult.APPLIED);
        assertThat(store.epoch()).isEqualTo(2);
        assertThat(store.apply(item("other", "late", 99, 1, 1))).isEqualTo(ApplyResult.STALE_EPOCH);
    }

    @Test
    @DisplayName("an old-epoch item from a current sender is not refused; it competes by last-writer-wins")
    void oldEpochItemFromCurrentSenderCompetesByLww() {
        DataStore store = new DataStore();
        store.observeEpoch(2);
        DataItem oldTerm = item("a", "old-term", 10, 1, 1);
        assertThat(store.apply(oldTerm, 2)).isEqualTo(ApplyResult.APPLIED);
        DataItem newTerm = item("b", "new-term", 1, 2, 2);
        store.apply(newTerm, 2);
        assertThat(store.apply(item("b", "older", 50, 1, 1), 2)).isEqualTo(ApplyResult.STALE);
        assertThat(store.get("a")).contains(oldTerm);
        assertThat(store.get("b")).contains(newTerm);
    }

    @Test
    @DisplayName("an item whose epoch is above its sender's is rejected with IllegalArgumentException")
    void applyRejectsItemEpochAboveSender() {
        DataStore store = new DataStore();
        assertThatThrownBy(() -> store.apply(item("k", "v", 1, 1, 3), 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("above the sender's epoch");
        assertThat(store.size()).isZero();
        assertThat(store.epoch()).isEqualTo(1);
        assertThat(attempts(store)).isZero();
        assertThatThrownBy(() -> store.apply(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("observeEpoch only raises and returns the epoch after the call")
    void observeEpochOnlyRaises() {
        DataStore store = new DataStore();
        assertThat(store.observeEpoch(4)).isEqualTo(4);
        assertThat(store.observeEpoch(2)).isEqualTo(4);
        assertThat(store.observeEpoch(4)).isEqualTo(4);
        assertThat(store.epoch()).isEqualTo(4);
        assertThatThrownBy(() -> store.observeEpoch(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("nothing on the write path lowers the epoch")
    void writePathNeverLowersEpoch() {
        DataStore store = new DataStore();
        store.observeEpoch(5);
        store.observeEpoch(2);
        store.apply(item("a", "v", 1, 1, 1));
        store.apply(item("b", "v", 1, 1, 3), 3);
        store.apply(item("c", "v", 1, 1, 1), 4);
        AntiEntropy.merge(store, List.of(item("d", "v", 1, 1, 2)), 2);
        assertThat(store.epoch()).isEqualTo(5);
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("an apply that raised the epoch gives the same fence as observeEpoch")
    void applyRaisingEpochUsesFenceGuarantee() {
        DataStore store = new DataStore();
        store.apply(item("x", "v", 1, 3, 3));
        assertThat(store.apply(item("y", "v", 100, 1, 2))).isEqualTo(ApplyResult.STALE_EPOCH);
        assertThat(store.get("y")).isEmpty();
    }

    @Test
    @DisplayName("concurrent raises end on the maximum, and each call returns at least its own argument")
    void concurrentRaisesEndOnMax() throws Exception {
        DataStore store = new DataStore();
        int threads = 16;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 1; i <= threads; i++) {
                long requested = i;
                results.add(pool.submit(() -> {
                    start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                    long after = store.observeEpoch(requested);
                    assertThat(after).isGreaterThanOrEqualTo(requested);
                    return after;
                }));
            }
            for (Future<Long> f : results) {
                f.get(WAIT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(store.epoch()).isEqualTo(threads);
    }

    // ------------------------------------------------------------------ fence atomicity

    @Test
    @DisplayName("barrier: no epoch-1 item is stored by an apply that started after observeEpoch(2) returned")
    void fenceBarrierNoLowerEpochStoredAfterRaise() throws Exception {
        DataStore store = new DataStore();
        int writers = 8;
        int before = 400;
        int after = 50;
        AtomicLong tickets = new AtomicLong();
        AtomicLong raiseTicket = new AtomicLong(-1);
        Map<String, Long> startTicket = new ConcurrentHashMap<>();
        Map<String, ApplyResult> outcome = new ConcurrentHashMap<>();
        CyclicBarrier start = new CyclicBarrier(writers + 1);
        CountDownLatch raised = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int writer = w;
                futures.add(pool.submit(() -> {
                    start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                    for (int i = 0; i < before; i++) {
                        applyTicketed(store, "w" + writer + "-" + i, tickets, startTicket, outcome);
                    }
                    assertThat(raised.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                    for (int i = 0; i < after; i++) {
                        applyTicketed(store, "w" + writer + "-late-" + i, tickets, startTicket, outcome);
                    }
                    return null;
                }));
            }
            futures.add(pool.submit(() -> {
                start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                store.observeEpoch(2);
                raiseTicket.set(tickets.incrementAndGet());   // taken after observeEpoch returned
                raised.countDown();
                return null;
            }));
            for (Future<?> f : futures) {
                f.get(WAIT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        long raise = raiseTicket.get();
        int total = writers * (before + after);
        assertThat(outcome).hasSize(total);
        long startedAfterRaise = 0;
        for (Map.Entry<String, Long> e : startTicket.entrySet()) {
            if (e.getValue() > raise) {
                startedAfterRaise++;
                assertThat(outcome.get(e.getKey())).as(e.getKey()).isEqualTo(ApplyResult.STALE_EPOCH);
                assertThat(store.get(e.getKey())).as(e.getKey()).isEmpty();
            }
        }
        assertThat(startedAfterRaise).isGreaterThanOrEqualTo((long) writers * after);
        for (DataItem stored : store.snapshot().values()) {
            assertThat(stored.epoch()).isEqualTo(1);
            assertThat(startTicket.get(stored.key())).as(stored.key()).isLessThan(raise);
        }
        assertThat(attempts(store)).isEqualTo(total);
        assertThat(store.appliedCount()).isEqualTo(store.size());
        assertThat(store.epoch()).isEqualTo(2);
    }

    private static void applyTicketed(DataStore store, String key, AtomicLong tickets,
                                      Map<String, Long> startTicket, Map<String, ApplyResult> outcome) {
        startTicket.put(key, tickets.incrementAndGet());   // taken before apply is called
        outcome.put(key, store.apply(item(key, "v", 1, 1, 1)));
    }

    @Test
    @DisplayName("forced interleaving: observeEpoch(2) cannot return while an apply is past the fence check")
    void forcedInterleavingRaiseWaitsForApplyPastFence() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DataStore store = new DataStore(new DataStore.Probe() {
            @Override
            public void beforeApply(DataItem item) {
            }

            @Override
            public void afterFenceCheck(DataItem item) {
                if (item.key().equals("paused")) {
                    entered.countDown();
                    try {
                        if (!release.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("never released");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
            }
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch raiseReturned = new CountDownLatch(1);
        AtomicReference<Optional<DataItem>> seenWhenRaiseReturned = new AtomicReference<>();
        Thread raiser = new Thread(() -> {
            store.observeEpoch(2);
            seenWhenRaiseReturned.set(store.get("paused"));
            raiseReturned.countDown();
        }, "raiser");
        try {
            Future<ApplyResult> paused = pool.submit(() -> store.apply(item("paused", "v", 5, 1, 1)));
            assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();   // inside the read lock

            raiser.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (raiser.getState() != Thread.State.WAITING) {   // parked on the write lock
                if (System.nanoTime() > deadline) {
                    fail("raiser never blocked; state " + raiser.getState());
                }
                Thread.onSpinWait();
            }
            assertThat(raiseReturned.getCount()).isEqualTo(1);
            assertThat(store.epoch()).isEqualTo(1);

            release.countDown();
            assertThat(paused.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(ApplyResult.APPLIED);
            assertThat(raiseReturned.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        // The paused write finished before observeEpoch returned, so the raiser saw it.
        assertThat(seenWhenRaiseReturned.get()).hasValueSatisfying(i -> assertThat(i.epoch()).isEqualTo(1));
        assertThat(store.epoch()).isEqualTo(2);
        // An epoch-1 write after the raise returned is refused.
        assertThat(store.apply(item("after", "v", 6, 1, 1))).isEqualTo(ApplyResult.STALE_EPOCH);
        assertThat(store.snapshot().keySet()).containsExactly("paused");
    }

    @Test
    @DisplayName("lock safety: many threads mixing raising applies, plain applies and observeEpoch never deadlock")
    void mixedRaisingAndPlainAppliesNeverDeadlock() {
        DataStore store = new DataStore();
        int threads = 16;
        int ops = 3000;
        long expectedMaxEpoch = 1 + (ops - 1) / 40;
        long applies = assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            CyclicBarrier start = new CyclicBarrier(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<Long>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    int thread = t;
                    futures.add(pool.submit(() -> {
                        start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                        long done = 0;
                        for (int i = 0; i < ops; i++) {
                            long epoch = 1 + i / 40;
                            String key = "k" + (i % 32);
                            switch ((i + thread) % 3) {
                                case 0 -> store.observeEpoch(epoch);
                                case 1 -> {
                                    store.apply(item(key, "r", i, thread + 1, 1), epoch);   // may raise
                                    done++;
                                }
                                default -> {
                                    store.apply(item(key, "p", i, thread + 1, 1));            // plain
                                    done++;
                                }
                            }
                        }
                        return done;
                    }));
                }
                long sum = 0;
                for (Future<Long> f : futures) {
                    sum += f.get();
                }
                return sum;
            } finally {
                pool.shutdownNow();
            }
        });
        assertThat(store.epoch()).isEqualTo(expectedMaxEpoch);
        assertThat(attempts(store)).isEqualTo(applies);
    }

    // ------------------------------------------------------------------ snapshot, clear, concurrency

    @Test
    @DisplayName("snapshot is sorted by key, detached from later writes and unmodifiable")
    void snapshotSortedDetachedUnmodifiable() {
        DataStore store = new DataStore();
        store.apply(item("c", "3", 1, 1, 1));
        store.apply(item("a", "1", 1, 1, 1));
        store.apply(item("b", "2", 1, 1, 1));
        SortedMap<String, DataItem> snap = store.snapshot();
        assertThat(snap.keySet()).containsExactly("a", "b", "c");

        store.apply(item("d", "4", 1, 1, 1));
        assertThat(snap).hasSize(3);
        assertThatThrownBy(() -> snap.put("e", item("e", "5", 1, 1, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("taking snapshots while other threads write never throws")
    void snapshotDuringConcurrentWritesNeverThrows() throws Exception {
        DataStore store = new DataStore();
        int writers = 4;
        int readers = 4;
        CyclicBarrier start = new CyclicBarrier(writers + readers);
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int writer = w;
                futures.add(pool.submit(() -> {
                    start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                    for (int i = 0; i < 2000; i++) {
                        store.apply(item("w" + writer + "-" + i, "v", i, writer + 1, 1));
                    }
                    return null;
                }));
            }
            for (int r = 0; r < readers; r++) {
                futures.add(pool.submit(() -> {
                    start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                    for (int i = 0; i < 200; i++) {
                        for (DataItem item : store.snapshot().values()) {
                            assertThat(item.epoch()).isEqualTo(1);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(WAIT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(store.size()).isEqualTo(writers * 2000);
    }

    @Test
    @DisplayName("clear resets items, counters and the epoch, and writes on the initial epoch succeed again")
    void clearResetsEpochAndInitialEpochWritesSucceedAgain() {
        DataStore store = new DataStore();
        store.apply(item("a", "v", 1, 1, 1));
        store.observeEpoch(4);
        assertThat(store.apply(item("b", "v", 1, 1, 1))).isEqualTo(ApplyResult.STALE_EPOCH);

        store.clear();

        assertThat(store.size()).isZero();
        assertThat(store.epoch()).isEqualTo(DataStore.INITIAL_EPOCH);
        assertThat(attempts(store)).isZero();
        assertThat(store.apply(item("b", "v", 1, 1, 1))).isEqualTo(ApplyResult.APPLIED);
    }

    @Test
    @DisplayName("16 threads applying shuffled versions of one key always end on the highest version")
    void concurrentShuffledVersionsEndOnMax() throws Exception {
        DataStore store = new DataStore();
        int versions = 4000;
        int threads = 16;
        List<DataItem> all = new ArrayList<>();
        for (int v = 1; v <= versions; v++) {
            all.add(item("k", "v" + v, v, 1, 1));
        }
        Collections.shuffle(all, new Random(42));
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                futures.add(pool.submit(() -> {
                    start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                    for (int i = thread; i < versions; i += threads) {
                        store.apply(all.get(i));
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(WAIT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(store.get("k")).contains(item("k", "v" + versions, versions, 1, 1));
        assertThat(attempts(store)).isEqualTo(versions);
        assertThat(store.duplicateCount()).isZero();
        assertThat(store.staleEpochCount()).isZero();
    }
}
