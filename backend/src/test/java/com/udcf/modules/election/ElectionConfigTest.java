package com.udcf.modules.election;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ElectionConfigTest {

    @Test
    void testNonPositiveValuesRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ElectionConfig(-1, 500, 50, 1000));
        assertThrows(IllegalArgumentException.class, () -> new ElectionConfig(100, 0, 50, 1000));
        assertThrows(IllegalArgumentException.class, () -> new ElectionConfig(100, 500, -10, 1000));
        assertThrows(IllegalArgumentException.class, () -> new ElectionConfig(100, 500, 50, -1000));
    }

    @Test
    void testProbeTimeoutNotShorterThanOkTimeoutRejected() {
        // probe (500) must be strictly less than OK (100) -> invalid
        assertThrows(IllegalArgumentException.class, () -> new ElectionConfig(100, 500, 500, 1000));
        assertThrows(IllegalArgumentException.class, () -> new ElectionConfig(100, 500, 101, 1000));
    }

    @Test
    void testValidConfig() {
        ElectionConfig config = new ElectionConfig(100, 500, 50, 1000);
        assertEquals(100, config.okTimeoutMs());
        assertEquals(500, config.coordinatorTimeoutMs());
        assertEquals(50, config.probeTimeoutMs());
        assertEquals(1000, config.ringCompletionTimeoutMs());
    }
}
