package com.udcf.scenario;

import com.udcf.core.events.ClusterEvent;

/**
 * Typed reads of an event's {@code data} for scenario steps. A missing or non-numeric value is
 * null, never 0.
 */
public final class EventData {

    private EventData() {
    }

    /** The value as an Integer, or null if absent or not a number. */
    public static Integer integer(ClusterEvent event, String key) {
        return event.data().get(key) instanceof Number n ? n.intValue() : null;
    }

    /** The value as a Long, or null if absent or not a number. */
    public static Long longValue(ClusterEvent event, String key) {
        return event.data().get(key) instanceof Number n ? n.longValue() : null;
    }

    /** The value as a Double, or null if absent or not a number. */
    public static Double decimal(ClusterEvent event, String key) {
        return event.data().get(key) instanceof Number n ? n.doubleValue() : null;
    }

    /** Whether the value equals {@code expected} as a number (an Integer and a Long of one value are equal). */
    public static boolean is(ClusterEvent event, String key, long expected) {
        Long value = longValue(event, key);
        return value != null && value == expected;
    }
}
