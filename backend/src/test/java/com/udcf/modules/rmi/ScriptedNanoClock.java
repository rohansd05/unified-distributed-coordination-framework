package com.udcf.modules.rmi;

import java.util.function.LongSupplier;

/**
 * A nanosecond time source for tests: returns the given readings in order, then keeps
 * returning the last one. Every read is counted, so a test can prove when time was taken.
 */
final class ScriptedNanoClock implements LongSupplier {

    private final long[] readings;
    private int reads;

    ScriptedNanoClock(long... readings) {
        if (readings.length == 0) {
            throw new IllegalArgumentException("at least one reading");
        }
        this.readings = readings.clone();
    }

    @Override
    public long getAsLong() {
        long value = readings[Math.min(reads, readings.length - 1)];
        reads++;
        return value;
    }

    int reads() {
        return reads;
    }
}
