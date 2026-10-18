package com.udcf.scenario;

import com.udcf.core.events.ClusterEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventDataTest {

    private static ClusterEvent event(Map<String, Object> data) {
        return new ClusterEvent(1, "test", 1, "T", 1, Instant.EPOCH, null, null, data);
    }

    @Test
    @DisplayName("numbers are read as the asked type; a missing, null or non-numeric value is null, never 0")
    void typedReads() {
        Map<String, Object> data = new HashMap<>();
        data.put("epoch", 3);
        data.put("big", 5_000_000_000L);
        data.put("millis", 12.75);
        data.put("text", "7");
        data.put("none", null);
        ClusterEvent event = event(data);

        assertThat(EventData.integer(event, "epoch")).isEqualTo(3);
        assertThat(EventData.longValue(event, "epoch")).isEqualTo(3L);
        assertThat(EventData.longValue(event, "big")).isEqualTo(5_000_000_000L);
        assertThat(EventData.decimal(event, "millis")).isEqualTo(12.75);
        assertThat(EventData.integer(event, "text")).isNull();
        assertThat(EventData.integer(event, "none")).isNull();
        assertThat(EventData.decimal(event, "missing")).isNull();
    }

    @Test
    @DisplayName("is compares an Integer or a Long by value, and is false when absent")
    void is() {
        ClusterEvent event = event(Map.of("leaderId", 5, "epoch", 4L));

        assertThat(EventData.is(event, "leaderId", 5)).isTrue();
        assertThat(EventData.is(event, "epoch", 4)).isTrue();
        assertThat(EventData.is(event, "leaderId", 4)).isFalse();
        assertThat(EventData.is(event, "missing", 0)).isFalse();
    }
}
