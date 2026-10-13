package com.udcf.modules.mpi;

import com.udcf.modules.mpi.dto.CollectiveDto;
import com.udcf.modules.mpi.dto.RootSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The bounded history: ids, order, bound, reset generations and cancelled reservations. No socket. */
class MpiHistoryTest {

    private static CollectiveDto entry(long id) {
        return MpiDtoMapper.collective(Instant.EPOCH, RootSource.LOWEST_LIVE_ID, MpiTestResults.refused(id));
    }

    private static void add(MpiHistory history) {
        MpiHistory.Slot slot = history.reserve();
        assertThat(history.record(slot, entry(slot.id()))).isTrue();
    }

    @Test
    @DisplayName("ids start at 1 and run in sequence; newest first; find and latest")
    void idsAndOrder() {
        MpiHistory history = new MpiHistory(5);
        add(history);
        add(history);
        add(history);

        assertThat(history.all()).extracting(CollectiveDto::id).containsExactly(3L, 2L, 1L);
        assertThat(history.latest()).map(CollectiveDto::id).contains(3L);
        assertThat(history.find(2)).map(CollectiveDto::id).contains(2L);
        assertThat(history.find(9)).isEmpty();
    }

    @Test
    @DisplayName("bounded: the oldest entry is dropped beyond the capacity")
    void bounded() {
        MpiHistory history = new MpiHistory(2);
        add(history);
        add(history);
        add(history);

        assertThat(history.all()).extracting(CollectiveDto::id).containsExactly(3L, 2L);
        assertThat(history.find(1)).isEmpty();
    }

    @Test
    @DisplayName("clear forgets everything and restarts ids at 1; a slot reserved before cannot record itself")
    void clearStartsANewGeneration() {
        MpiHistory history = new MpiHistory(5);
        add(history);
        MpiHistory.Slot stale = history.reserve();

        history.clear();

        assertThat(history.all()).isEmpty();
        assertThat(history.latest()).isEmpty();
        assertThat(history.record(stale, entry(stale.id()))).isFalse();
        assertThat(history.all()).isEmpty();
        assertThat(history.reserve().id()).isEqualTo(1);
    }

    @Test
    @DisplayName("cancel gives back the newest reservation only; an older slot or one from before a reset changes nothing")
    void cancel() {
        MpiHistory history = new MpiHistory(5);
        MpiHistory.Slot first = history.reserve();
        history.cancel(first);
        MpiHistory.Slot again = history.reserve();
        assertThat(again.id()).isEqualTo(1);

        MpiHistory.Slot second = history.reserve();
        history.cancel(again);   // not the newest
        assertThat(history.reserve().id()).isEqualTo(second.id() + 1);

        history.clear();
        history.cancel(second);   // an older generation
        assertThat(history.reserve().id()).isEqualTo(1);
    }

    @Test
    @DisplayName("an entry must carry its slot's id; the capacity must be at least 1")
    void rules() {
        MpiHistory history = new MpiHistory(1);
        MpiHistory.Slot slot = history.reserve();
        assertThatThrownBy(() -> history.record(slot, entry(7))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MpiHistory(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
