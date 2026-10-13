package com.udcf.modules.mpi;

/**
 * The deterministic data of Experiment 9: every vector comes from a seed, so a run can be
 * repeated and its result checked; no value is ever typed in by hand.
 *
 * <p><b>Generator.</b> SplitMix64 (Steele, Lea and Flood, 2014), written out here because the
 * algorithms behind {@code java.util.Random} and {@code SplittableRandom} are not part of their
 * specification. For element i (from 0): {@code state = seed + (i + 1) * 0x9E3779B97F4A7C15}
 * (mod 2^64), then {@code z = mix(state)}, where {@code mix} xors and multiplies with
 * 0xBF58476D1CE4E5B9 and 0x94D049BB133111EB (see {@link #mix}).</p>
 *
 * <p><b>To a double.</b> {@code ((z >>> 11) * 2^-53) * 2000.0 - 1000.0}: the top 53 bits as a
 * fraction in [0, 1), scaled to [-1000, 1000), at full double precision.</p>
 *
 * <p><b>Contributions</b> (gather): node {@code nodeId}'s data is
 * {@code vector(seed ^ (nodeId * 0x9E3779B97F4A7C15), count)} (mod 2^64), so every node's block
 * differs and the root can recompute what each node should have sent.</p>
 */
public final class PayloadGenerator {

    /** SplitMix64's increment (the golden ratio as a 64-bit fraction). */
    static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private PayloadGenerator() {
    }

    /** {@code count} values in [-1000, 1000) from {@code seed}; the same arguments always give the same values. */
    public static double[] vector(long seed, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0, was " + count);
        }
        double[] values = new double[count];
        long state = seed;
        for (int i = 0; i < count; i++) {
            state += GOLDEN_GAMMA;
            values[i] = toDouble(mix(state));
        }
        return values;
    }

    /** The seed of node {@code nodeId}'s contribution. */
    public static long contributionSeed(long seed, int nodeId) {
        requireNode(nodeId);
        return seed ^ (nodeId * GOLDEN_GAMMA);
    }

    /** What node {@code nodeId} contributes to a gather: {@code count} values from its own seed. */
    public static double[] contribution(int nodeId, int count, long seed) {
        return vector(contributionSeed(seed, nodeId), count);
    }

    /** SplitMix64's output function. */
    static long mix(long state) {
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** The top 53 bits of {@code z} as a double in [-1000, 1000). */
    static double toDouble(long z) {
        return (z >>> 11) * 0x1.0p-53 * 2000.0 - 1000.0;
    }

    private static void requireNode(int nodeId) {
        if (nodeId < 1) {
            throw new IllegalArgumentException("nodeId must be >= 1, was " + nodeId);
        }
    }
}
