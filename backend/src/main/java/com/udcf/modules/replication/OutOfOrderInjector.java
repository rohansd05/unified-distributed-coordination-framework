package com.udcf.modules.replication;

import java.util.Objects;

/**
 * Builds the stale update that the "inject a stale out-of-order update" action delivers.
 *
 * <p>A real network can deliver an old update after a newer one. To show the store
 * refusing it on demand, this creates a version of a key that is strictly older than the
 * one currently held: the same key, epoch and origin, one Lamport tick earlier. It is a
 * demonstration device, not part of the normal replication flow.</p>
 *
 * <p>Ported from legacy-demos/exp05-replication, where the demo hand-built a fixed
 * {@code (L5, N1)} item; deriving it from the current version guarantees it is older
 * whatever the clock reads.</p>
 */
public final class OutOfOrderInjector {

    private OutOfOrderInjector() {
    }

    /**
     * @param current    the version currently held for the key; its Lamport time must be at
     *                   least 1, because nothing at the same epoch and origin is older than 0
     * @param staleValue the value the stale update carries
     * @return an item strictly older than {@code current}
     * @throws IllegalArgumentException if {@code current.lamportTime()} is 0, or the value is invalid
     */
    public static DataItem staleVersionOf(DataItem current, String staleValue) {
        Objects.requireNonNull(current, "current must not be null");
        if (current.lamportTime() < 1) {
            throw new IllegalArgumentException("no older version exists for Lamport time "
                    + current.lamportTime() + " at the same epoch and origin");
        }
        return new DataItem(current.key(), staleValue, current.lamportTime() - 1,
                current.originNode(), current.epoch());
    }
}
