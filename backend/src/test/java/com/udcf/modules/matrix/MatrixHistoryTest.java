package com.udcf.modules.matrix;

import com.udcf.modules.matrix.dto.MatrixRunDto;
import com.udcf.modules.mpi.dto.RootSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The bounded history: run ids, order, bound, reset generations and cancelled reservations. No socket. */
class MatrixHistoryTest {

    private static MatrixRunDto entry(long id) {
        return MatrixDtoMapper.run(Instant.EPOCH, RootSource.LEADER, MatrixTestResults.refused(id),
                MatrixTestResults.BASIS);
    }

    private static void add(MatrixHistory history) {
        MatrixHistory.Slot slot = history.reserve();
        assertThat(history.record(slot, entry(slot.id()))).isTrue();
    }

    @Test
    @DisplayName("ids start at 1 and run in sequence; newest first; find and latest")
    void idsAndOrder() {
        MatrixHistory history = new MatrixHistory(5);
        add(history);
        add(history);
        add(history);

        assertThat(history.all()).extracting(MatrixRunDto::runId).containsExactly(3L, 2L, 1L);
        assertThat(history.latest()).map(MatrixRunDto::runId).contains(3L);
        assertThat(history.find(2)).map(MatrixRunDto::runId).contains(2L);
        assertThat(history.find(9)).isEmpty();
    }

    @Test
    @DisplayName("bounded: the oldest entry is dropped beyond the capacity")
    void bounded() {
        MatrixHistory history = new MatrixHistory(2);
        add(history);
        add(history);
        add(history);

        assertThat(history.all()).extracting(MatrixRunDto::runId).containsExactly(3L, 2L);
        assertThat(history.find(1)).isEmpty();
    }

    @Test
    @DisplayName("clear forgets everything and restarts ids at 1; a slot reserved before cannot record itself")
    void clearStartsANewGeneration() {
        MatrixHistory history = new MatrixHistory(5);
        add(history);
        MatrixHistory.Slot stale = history.reserve();

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
        MatrixHistory history = new MatrixHistory(5);
        MatrixHistory.Slot first = history.reserve();
        history.cancel(first);
        MatrixHistory.Slot again = history.reserve();
        assertThat(again.id()).isEqualTo(1);

        MatrixHistory.Slot second = history.reserve();
        history.cancel(again);   // not the newest
        assertThat(history.reserve().id()).isEqualTo(second.id() + 1);

        history.clear();
        history.cancel(second);   // an older generation
        assertThat(history.reserve().id()).isEqualTo(1);
    }

    @Test
    @DisplayName("an entry must carry its slot's id; the capacity must be at least 1")
    void rules() {
        MatrixHistory history = new MatrixHistory(1);
        MatrixHistory.Slot slot = history.reserve();
        assertThatThrownBy(() -> history.record(slot, entry(7))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatrixHistory(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
