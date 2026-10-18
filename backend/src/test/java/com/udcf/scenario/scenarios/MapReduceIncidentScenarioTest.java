package com.udcf.scenario.scenarios;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure helpers of the MapReduce incident scenario: trimming the export, keys, top counts and phrases. */
class MapReduceIncidentScenarioTest {

    private static final String A = "2026-10-19 10:00:00.000 | node=0 | category=LEADER_CHANGED | seq=1 | lamport=1 | module=cluster";
    private static final String B = "2026-10-19 10:00:00.001 | node=4 | category=TASK_SENT | seq=2 | lamport=2 | module=mapreduce";

    @Test
    @DisplayName("newestThatFit keeps the newest lines (one line feed each) within the byte limit, in their order")
    void newestThatFit() {
        int one = B.length() + 1;
        assertThat(MapReduceIncidentScenario.newestThatFit(List.of(A, B), 10_000)).containsExactly(A, B);
        assertThat(MapReduceIncidentScenario.newestThatFit(List.of(A, B), one)).containsExactly(B);
        assertThat(MapReduceIncidentScenario.newestThatFit(List.of(A, B), one - 1)).isEmpty();
        assertThat(MapReduceIncidentScenario.text(List.of(A, B))).isEqualTo(A + "\n" + B + "\n");
    }

    @Test
    @DisplayName("counts by type and by node key each exported line once")
    void counts() {
        assertThat(MapReduceIncidentScenario.nodeKey(B)).isEqualTo("node 4");
        assertThat(MapReduceIncidentScenario.count(List.of(A, B, B), MapReduceIncidentScenario::nodeKey))
                .containsExactly(Map.entry("node 0", 1L), Map.entry("node 4", 2L));
    }

    @Test
    @DisplayName("top keeps the three largest counts, largest first, ties by key; phrase joins them")
    void topAndPhrase() {
        Map<String, Long> counts = new TreeMap<>(Map.of("B", 5L, "A", 5L, "C", 9L, "D", 1L));
        Map<String, Long> top = MapReduceIncidentScenario.top(counts);
        assertThat(top).containsExactly(Map.entry("C", 9L), Map.entry("A", 5L), Map.entry("B", 5L));
        assertThat(MapReduceIncidentScenario.phrase(top)).isEqualTo("C 9, A 5 and B 5");
        assertThat(MapReduceIncidentScenario.phrase(Map.of("X", 1L))).isEqualTo("X 1");
        assertThat(MapReduceIncidentScenario.phrase(Map.of())).isEqualTo("none");
    }
}
