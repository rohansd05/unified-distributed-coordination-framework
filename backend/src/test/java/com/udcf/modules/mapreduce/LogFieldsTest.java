package com.udcf.modules.mapreduce;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LogFieldsTest {

    @Test
    void testFieldExtraction() {
        String line = "2026-09-18 14:33:28.151 | node=2 | category=RECV | latency=10.48 | message received";
        assertEquals("2", LogFields.field(line, "node"));
        assertEquals("RECV", LogFields.field(line, "category"));
        assertEquals("10.48", LogFields.field(line, "latency"));
    }

    @Test
    void testMissingFieldReturnsNull() {
        String line = "node=2 | category=RECV";
        assertNull(LogFields.field(line, "latency"));
        assertNull(LogFields.field(line, "unknown"));
    }

    @Test
    void testNullOrEmptyInput() {
        assertNull(LogFields.field(null, "node"));
        assertNull(LogFields.field("", "node"));
        assertNull(LogFields.field("node=2", null));
    }

    @Test
    void testTrimmingAndPrefixMatching() {
        String line = "  node = not_this | node=3 | category=SEND ";
        assertEquals("3", LogFields.field(line, "node"));
        assertEquals("SEND", LogFields.field(line, "category"));
    }
}
