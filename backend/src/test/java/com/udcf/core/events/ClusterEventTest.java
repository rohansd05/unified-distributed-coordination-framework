package com.udcf.core.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the invariants every consumer of the event log relies on, and that an event's
 * data cannot change after it is published.
 */
class ClusterEventTest {

    private static final Instant WALL = Instant.parse("2026-01-01T00:00:00Z");

    private static ClusterEvent event(long sequence, String module, int nodeId, String type,
                                      long lamportTime, Instant wallTime, Map<String, Object> data) {
        return new ClusterEvent(sequence, module, nodeId, type, lamportTime, wallTime, null, null, data);
    }

    @Test
    @DisplayName("a valid event keeps every field")
    void validEventKeepsFields() {
        ClusterEvent event = new ClusterEvent(7, "election", 3, "ACK", 12, WALL, 2, "ok", Map.of("k", 1));

        assertThat(event.sequence()).isEqualTo(7);
        assertThat(event.module()).isEqualTo("election");
        assertThat(event.nodeId()).isEqualTo(3);
        assertThat(event.type()).isEqualTo("ACK");
        assertThat(event.lamportTime()).isEqualTo(12);
        assertThat(event.wallTime()).isEqualTo(WALL);
        assertThat(event.peerId()).isEqualTo(2);
        assertThat(event.message()).isEqualTo("ok");
        assertThat(event.data()).containsEntry("k", 1);
    }

    @Test
    @DisplayName("rejects a sequence of zero or below")
    void rejectsNonPositiveSequence() {
        assertThatThrownBy(() -> event(0, "m", 1, "T", 0, WALL, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sequence");
        assertThatThrownBy(() -> event(-1, "m", 1, "T", 0, WALL, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sequence");
    }

    @Test
    @DisplayName("rejects a null or blank module")
    void rejectsBlankModule() {
        assertThatThrownBy(() -> event(1, null, 1, "T", 0, WALL, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("module");
        assertThatThrownBy(() -> event(1, "  ", 1, "T", 0, WALL, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("module");
    }

    @Test
    @DisplayName("rejects a null or blank type")
    void rejectsBlankType() {
        assertThatThrownBy(() -> event(1, "m", 1, null, 0, WALL, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("type");
        assertThatThrownBy(() -> event(1, "m", 1, "", 0, WALL, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("type");
    }

    @Test
    @DisplayName("rejects a negative node id")
    void rejectsNegativeNodeId() {
        assertThatThrownBy(() -> event(1, "m", -1, "T", 0, WALL, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nodeId");
    }

    @Test
    @DisplayName("accepts node id 0 for cluster-level events")
    void acceptsClusterLevelNodeId() {
        assertThat(event(1, "cluster", 0, "RESET", 0, WALL, null).nodeId()).isZero();
    }

    @Test
    @DisplayName("rejects a negative Lamport time")
    void rejectsNegativeLamportTime() {
        assertThatThrownBy(() -> event(1, "m", 1, "T", -1, WALL, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lamportTime");
    }

    @Test
    @DisplayName("rejects a null wall time")
    void rejectsNullWallTime() {
        assertThatNullPointerException()
                .isThrownBy(() -> event(1, "m", 1, "T", 0, null, null))
                .withMessageContaining("wallTime");
    }

    @Test
    @DisplayName("data is copied: later changes to the source map do not leak in")
    void dataIsCopiedDefensively() {
        Map<String, Object> source = new HashMap<>();
        source.put("a", 1);

        ClusterEvent event = event(1, "m", 1, "T", 0, WALL, source);
        source.put("b", 2);

        assertThat(event.data()).containsOnlyKeys("a");
    }

    @Test
    @DisplayName("data is unmodifiable")
    void dataIsUnmodifiable() {
        ClusterEvent event = event(1, "m", 1, "T", 0, WALL, Map.of("a", 1));

        assertThatThrownBy(() -> event.data().put("b", 2))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("data keeps insertion order")
    void dataKeepsInsertionOrder() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("zeta", 1);
        source.put("alpha", 2);
        source.put("mid", 3);

        ClusterEvent event = event(1, "m", 1, "T", 0, WALL, source);

        assertThat(event.data().keySet()).containsExactly("zeta", "alpha", "mid");
    }

    @Test
    @DisplayName("null data becomes an empty map")
    void nullDataBecomesEmpty() {
        assertThat(event(1, "m", 1, "T", 0, WALL, null).data()).isNotNull().isEmpty();
    }
}
