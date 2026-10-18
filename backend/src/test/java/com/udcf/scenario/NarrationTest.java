package com.udcf.scenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NarrationTest {

    @Test
    @DisplayName("ids and nodes read as plain lists, ascending")
    void lists() {
        assertThat(Narration.ids(List.of())).isEmpty();
        assertThat(Narration.ids(List.of(5))).isEqualTo("5");
        assertThat(Narration.ids(Set.of(2, 1))).isEqualTo("1 and 2");
        assertThat(Narration.ids(List.of(4, 1, 3, 2))).isEqualTo("1, 2, 3 and 4");
        assertThat(Narration.nodes(List.of())).isEqualTo("no node");
        assertThat(Narration.nodes(List.of(5))).isEqualTo("node 5");
        assertThat(Narration.nodes(List.of(1, 3))).isEqualTo("nodes 1 and 3");
    }

    @Test
    @DisplayName("millis rounds to whole milliseconds; null is unmeasured, never 0")
    void millis() {
        assertThat(Narration.millis(2504.6)).isEqualTo("2505 ms");
        assertThat(Narration.millis(12L)).isEqualTo("12 ms");
        assertThat(Narration.millis(null)).isEqualTo("an unmeasured time");
    }

    @Test
    @DisplayName("percent has one decimal; sentence capitalises the first letter")
    void percentAndSentence() {
        assertThat(Narration.percent(23.456)).isEqualTo("23.5 %");
        assertThat(Narration.sentence("nodes 1 and 2")).isEqualTo("Nodes 1 and 2");
        assertThat(Narration.sentence("")).isEmpty();
        assertThat(Narration.sentence(null)).isNull();
    }
}
