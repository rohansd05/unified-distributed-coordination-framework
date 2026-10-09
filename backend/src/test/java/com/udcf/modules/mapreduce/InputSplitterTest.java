package com.udcf.modules.mapreduce;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputSplitterTest {

    @Test
    void testEvenSplit() {
        List<String> input = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9");
        List<List<String>> splits = InputSplitter.split(input, 3);

        assertEquals(3, splits.size());
        assertEquals(List.of("1", "2", "3"), splits.get(0));
        assertEquals(List.of("4", "5", "6"), splits.get(1));
        assertEquals(List.of("7", "8", "9"), splits.get(2));
    }

    @Test
    void testUnevenSplit() {
        List<String> input = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        List<List<String>> splits = InputSplitter.split(input, 3);

        assertEquals(3, splits.size());
        assertEquals(List.of("1", "2", "3", "4"), splits.get(0));
        assertEquals(List.of("5", "6", "7", "8"), splits.get(1));
        assertEquals(List.of("9", "10"), splits.get(2));
    }

    @Test
    void testMorePartsThanLines() {
        List<String> input = List.of("line1", "line2");
        List<List<String>> splits = InputSplitter.split(input, 5);

        assertEquals(5, splits.size());
        assertEquals(List.of("line1"), splits.get(0));
        assertEquals(List.of("line2"), splits.get(1));
        assertTrue(splits.get(2).isEmpty());
        assertTrue(splits.get(3).isEmpty());
        assertTrue(splits.get(4).isEmpty());
    }

    @Test
    void testEmptyInput() {
        List<List<String>> splits = InputSplitter.split(List.of(), 3);
        assertEquals(3, splits.size());
        assertTrue(splits.get(0).isEmpty());
        assertTrue(splits.get(1).isEmpty());
        assertTrue(splits.get(2).isEmpty());
    }

    @Test
    void testInvalidPartsThrows() {
        assertThrows(IllegalArgumentException.class, () -> InputSplitter.split(List.of("a"), 0));
        assertThrows(IllegalArgumentException.class, () -> InputSplitter.split(List.of("a"), -1));
    }
}
