package com.udcf.modules.mpi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Block distribution of n elements over p ranks, as scatter and gather use it: each rank gets
 * {@code base = n / p} elements and the first {@code n % p} ranks one more, in rank order, so
 * the blocks tile [0, n) without gaps or overlaps. Block i belongs to rank i.
 *
 * <p>Example: n = 10 over p = 4 gives lengths 3, 3, 2, 2 at offsets 0, 3, 6, 8. Fewer elements
 * than ranks (for example n = 3 over p = 4) would leave a rank empty and is rejected.</p>
 */
public final class BlockPartition {

    /**
     * One rank's block.
     *
     * <p>No dedicated test of its own: a record with validation only, covered by BlockPartitionTest.</p>
     */
    public record Block(int rank, int offset, int length) {

        public Block {
            if (rank < 0 || offset < 0 || length < 1) {
                throw new IllegalArgumentException("a block needs rank >= 0, offset >= 0 and length >= 1, was "
                        + rank + ", " + offset + ", " + length);
            }
        }

        /** The index just after the block. */
        public int end() {
            return offset + length;
        }
    }

    private BlockPartition() {
    }

    /**
     * The p blocks of n elements, rank 0 first.
     *
     * @throws IllegalArgumentException if p < 1 or n < p
     */
    public static List<Block> of(int n, int p) {
        if (p < 1) {
            throw new IllegalArgumentException("p must be >= 1, was " + p);
        }
        if (n < p) {
            throw new IllegalArgumentException("n must be at least the number of ranks: n = " + n + ", p = " + p);
        }
        int base = n / p;
        int extra = n % p;
        List<Block> blocks = new ArrayList<>(p);
        int offset = 0;
        for (int rank = 0; rank < p; rank++) {
            int length = base + (rank < extra ? 1 : 0);
            blocks.add(new Block(rank, offset, length));
            offset += length;
        }
        return List.copyOf(blocks);
    }

    /** A copy of {@code block}'s part of {@code values}. */
    public static double[] slice(double[] values, Block block) {
        Objects.requireNonNull(values, "values must not be null");
        Objects.requireNonNull(block, "block must not be null");
        if (block.end() > values.length) {
            throw new IllegalArgumentException("block " + block + " is outside " + values.length + " values");
        }
        return Arrays.copyOfRange(values, block.offset(), block.end());
    }
}
