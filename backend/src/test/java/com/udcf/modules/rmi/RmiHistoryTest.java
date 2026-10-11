package com.udcf.modules.rmi;

import com.udcf.modules.rmi.dto.HistoryEntryDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The bounded, newest-first history with one sequential id sequence and a reset generation. */
class RmiHistoryTest {

    private static final Instant AT = Instant.parse("2026-10-12T10:00:00Z");

    private static HistoryEntryDto entry(long id) {
        return HistoryEntryDto.of(RmiDtoMapper.call(id, AT, RmiResultFixtures.remoteSuccess(RmiCall.ping())));
    }

    private static long add(RmiHistory history) {
        RmiHistory.Slot slot = history.reserve();
        assertThat(history.record(slot, entry(slot.id()))).isTrue();
        return slot.id();
    }

    @Test
    @DisplayName("ids start at 1 and follow on; entries are kept newest first and latest is the newest")
    void sequentialNewestFirst() {
        RmiHistory history = new RmiHistory(10);
        assertThat(history.latest()).isEmpty();

        assertThat(List.of(add(history), add(history), add(history))).containsExactly(1L, 2L, 3L);

        assertThat(history.all()).extracting(HistoryEntryDto::id).containsExactly(3L, 2L, 1L);
        assertThat(history.latest()).map(HistoryEntryDto::id).contains(3L);
    }

    @Test
    @DisplayName("the history keeps at most its capacity, dropping the oldest")
    void bounded() {
        RmiHistory history = new RmiHistory(3);
        for (int i = 0; i < 5; i++) {
            add(history);
        }
        assertThat(history.all()).extracting(HistoryEntryDto::id).containsExactly(5L, 4L, 3L);
        assertThat(history.find(1)).isEmpty();
        assertThat(history.find(4)).map(HistoryEntryDto::id).contains(4L);
    }

    @Test
    @DisplayName("clear forgets everything, restarts the ids at 1, and a slot from before cannot record itself")
    void clearStartsANewGeneration() {
        RmiHistory history = new RmiHistory(10);
        add(history);
        RmiHistory.Slot stale = history.reserve();

        history.clear();

        assertThat(history.record(stale, entry(stale.id()))).isFalse();
        assertThat(history.all()).isEmpty();
        assertThat(add(history)).isEqualTo(1L);
    }

    @Test
    @DisplayName("an entry must carry its slot's id; the capacity must be at least 1")
    void rejectsMismatches() {
        RmiHistory history = new RmiHistory(2);
        RmiHistory.Slot slot = history.reserve();
        assertThatThrownBy(() -> history.record(slot, entry(slot.id() + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RmiHistory(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("concurrent reservations never hand out the same id twice")
    void concurrentReservationsAreUnique() throws Exception {
        RmiHistory history = new RmiHistory(5);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        try {
            for (int t = 0; t < 8; t++) {
                done.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < 100; i++) {
                        RmiHistory.Slot slot = history.reserve();
                        ids.add(slot.id());
                        history.record(slot, entry(slot.id()));
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> future : done) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(ids).hasSize(800).allMatch(id -> id >= 1 && id <= 800);
        assertThat(history.all()).hasSize(5);
    }
}
