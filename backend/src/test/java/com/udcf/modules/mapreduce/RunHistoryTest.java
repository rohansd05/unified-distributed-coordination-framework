package com.udcf.modules.mapreduce;

import com.udcf.modules.mapreduce.dto.RunDto;
import com.udcf.modules.mapreduce.dto.RunState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** The bounded run history and its generation rule for reset. */
class RunHistoryTest {

    private static RunDto run(String id, RunState state) {
        return new RunDto(id, state, "word-count", "Word count", InputType.SAMPLE, "Bundled sample text", 10L, 1,
                List.of(1, 2), null, Instant.EPOCH, null, null, null, null);
    }

    @Test
    @DisplayName("newest first, bounded to the capacity, and a finished run replaces its RUNNING entry in place")
    void boundedNewestFirst() {
        RunHistory history = new RunHistory(2);
        long g = history.generation();

        history.record(g, run("a", RunState.RUNNING));
        history.record(g, run("a", RunState.COMPLETED));
        history.record(g, run("b", RunState.COMPLETED));
        history.record(g, run("c", RunState.RUNNING));

        assertThat(history.all()).extracting(RunDto::runId).containsExactly("c", "b");
        assertThat(history.latest()).get().extracting(RunDto::runId).isEqualTo("c");
        assertThat(history.find("a")).isEmpty();
        assertThat(history.find("b")).get().extracting(RunDto::state).isEqualTo(RunState.COMPLETED);
    }

    @Test
    @DisplayName("after clear, a run from the old generation cannot record itself")
    void generation() {
        RunHistory history = new RunHistory(5);
        long old = history.generation();
        history.record(old, run("a", RunState.RUNNING));

        history.clear();

        assertThat(history.all()).isEmpty();
        assertThat(history.record(old, run("a", RunState.COMPLETED))).isFalse();
        assertThat(history.latest()).isEmpty();
        assertThat(history.record(history.generation(), run("b", RunState.RUNNING))).isTrue();
    }

    @Test
    @DisplayName("remove drops one run; the capacity must be at least 1")
    void removeAndCapacity() {
        RunHistory history = new RunHistory(3);
        history.record(0, run("a", RunState.RUNNING));
        history.remove("a");
        assertThat(history.all()).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> new RunHistory(0));
    }
}
