package com.udcf.modules.matrix;

import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * The honest basis of every speedup figure (HANDOFF Section 7: be honest about speedup). All nodes
 * are co-hosted in one process (R1), so they share one machine's processors: the speedup is bounded
 * by the processors the JVM reports, and communication dominates small matrices.
 *
 * <p>The processor count comes from an injected supplier ({@code Runtime.getRuntime()::availableProcessors}
 * in production, a fixed value in tests). It is what the JVM reports as available, not the number of
 * physical cores, and it can be lower inside a container.</p>
 */
public final class SpeedupBasis {

    private final IntSupplier availableProcessors;

    public SpeedupBasis(IntSupplier availableProcessors) {
        this.availableProcessors = Objects.requireNonNull(availableProcessors, "availableProcessors must not be null");
    }

    /** The available processors reported by the JVM now. */
    public int processors() {
        int processors = availableProcessors.getAsInt();
        if (processors < 1) {
            throw new IllegalStateException("the JVM must report at least 1 available processor, reported " + processors);
        }
        return processors;
    }

    /** The highest speedup {@code ranks} co-hosted ranks can reach: the smaller of the rank and processor counts. */
    public int ceiling(int ranks) {
        if (ranks < 1) {
            throw new IllegalArgumentException("ranks must be >= 1, was " + ranks);
        }
        return Math.min(ranks, processors());
    }

    /**
     * The text to show next to the speedup figures of a run with {@code ranks} ranks after
     * {@code warmupProducts} untimed products.
     */
    public String text(int ranks, int warmupProducts) {
        if (warmupProducts < 0) {
            throw new IllegalArgumentException("warmupProducts must be >= 0, was " + warmupProducts);
        }
        int processors = processors();
        String warmup = warmupProducts == 0
                ? "No warm-up products were run, so the figures include JVM compile effects (the first runs of the multiply are "
                        + "slower until the JIT has compiled it)."
                : warmupProducts + " untimed warm-up product" + (warmupProducts == 1 ? " was" : "s were")
                        + " run first, so the JIT had compiled the multiply before the timed runs.";
        return "All " + ranks + " node" + (ranks == 1 ? "" : "s") + " run inside one Java process on one machine, so they share its "
                + processors + " available processor" + (processors == 1 ? "" : "s") + " (as reported by the JVM; inside a "
                + "container this can be lower than the machine's). The speedup cannot exceed " + ceiling(ranks)
                + ", the smaller of the node count and that processor count, and other work on the machine lowers it further. "
                + "For small matrices the scatter of A, the broadcast of B and the gather of C take longer than the multiply they "
                + "split up, so the parallel run can be slower than the sequential one (a speedup below 1). Each node's compute "
                + "time is measured on the node, around its multiply only; every other time is measured on the root. " + warmup;
    }
}
