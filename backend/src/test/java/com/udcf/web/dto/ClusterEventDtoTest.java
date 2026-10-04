package com.udcf.web.dto;

import com.udcf.core.events.ClusterEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the wire form the frontend parses: every field, ISO-8601 time, ordered data. */
class ClusterEventDtoTest {

    private static final Instant WALL = Instant.parse("2026-03-04T05:06:07.123Z");

    @Test
    @DisplayName("from copies every field")
    void copiesEveryField() {
        ClusterEvent event = new ClusterEvent(42, "election", 3, "ACK", 17, WALL, 2, "ok", Map.of("k", 1));

        ClusterEventDto dto = ClusterEventDto.from(event);

        assertThat(dto).isEqualTo(new ClusterEventDto(42, "election", 3, "ACK", 17,
                "2026-03-04T05:06:07.123Z", 2, "ok", Map.of("k", 1)));
    }

    @Test
    @DisplayName("wallTime is ISO-8601 and round-trips to the same instant")
    void wallTimeIsIso8601() {
        ClusterEvent event = new ClusterEvent(1, "m", 1, "T", 0, WALL, null, null, null);

        String wallTime = ClusterEventDto.from(event).wallTime();

        assertThat(wallTime).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");
        assertThat(Instant.parse(wallTime)).isEqualTo(WALL);
    }

    @Test
    @DisplayName("data keeps its insertion order")
    void dataKeepsOrder() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("zeta", 1);
        data.put("alpha", 2);
        data.put("mid", 3);
        ClusterEvent event = new ClusterEvent(1, "m", 1, "T", 0, WALL, null, null, data);

        assertThat(ClusterEventDto.from(event).data().keySet()).containsExactly("zeta", "alpha", "mid");
    }
}
