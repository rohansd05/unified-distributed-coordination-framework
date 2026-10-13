package com.udcf.modules.matrix;

import com.udcf.core.clock.LamportClock;
import com.udcf.modules.mpi.CollectiveEndpointLogic;
import com.udcf.modules.mpi.VectorChecksum;
import com.udcf.modules.rmi.CallContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The work behind a node's {@link MatrixEndpoint} (step E10b): no registry, export or socket.
 *
 * <p><b>Inputs.</b> The node's rows of A and the whole of B are what the Experiment 9 scatter and broadcast
 * stored on this node ({@link CollectiveEndpointLogic#received}, read through {@code inputs}, which in
 * production is {@code CollectiveEndpointExport.current(node)}: the node's current collective logic, empty
 * while its RMI is down). Missing or dropped input is a refusal ({@link MatrixComputeException}), never an
 * exception of another kind.</p>
 *
 * <p><b>Order of every call</b> (link L4, as in Experiments 1 and 9): the arguments are checked first, so an
 * invalid call changes nothing; then {@code clock.update(senderLamport)}; then the work; then the reply is
 * stamped with {@code clock.tick()}. A refusal after the update carries no time.</p>
 *
 * <p><b>Kept blocks.</b> A computed block of C is kept by run id (at most {@code result-store-size}; the
 * oldest is dropped, a repeated run id replaces its entry and becomes the newest) until the root fetches
 * it. A new logic is created on every start and recovery, so a crash drops them all. Thread-safe: RMI
 * delivers calls on several threads, so the store is accessed under one lock; the clock is atomic.</p>
 */
public final class MatrixEndpointLogic implements MatrixEndpoint {

    /** One kept block of C. */
    private record Kept(int rank, double[] values) {
    }

    private final int nodeId;
    private final LamportClock clock;
    private final Supplier<Optional<CollectiveEndpointLogic>> inputs;
    private final NodeExecutor executor;
    private final MatrixRunProperties properties;
    private final LongSupplier nanoClock;
    private final LinkedHashMap<Long, Kept> kept = new LinkedHashMap<>();   // guarded by itself; oldest first

    public MatrixEndpointLogic(int nodeId, LamportClock clock, Supplier<Optional<CollectiveEndpointLogic>> inputs,
                               NodeExecutor executor, MatrixRunProperties properties, LongSupplier nanoClock) {
        if (nodeId < 1) {
            throw new IllegalArgumentException("nodeId must be >= 1, was " + nodeId);
        }
        this.nodeId = nodeId;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.inputs = Objects.requireNonNull(inputs, "inputs must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
    }

    public int nodeId() {
        return nodeId;
    }

    @Override
    public ComputeAck computeBlock(CallContext context, long runId, int rank, int n, long scatterId, long broadcastId)
            throws MatrixComputeException {
        Objects.requireNonNull(context, "context must not be null");
        requireIds(runId, rank);
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1, was " + n);
        }
        if (scatterId < 1 || broadcastId < 1) {
            throw new IllegalArgumentException("collective ids must be >= 1, were " + scatterId + " and " + broadcastId);
        }

        clock.update(context.senderLamport());
        CollectiveEndpointLogic store = inputs.get().orElseThrow(() -> new MatrixComputeException(
                MatrixComputeException.Reason.INPUT_MISSING,
                "Node " + nodeId + " has no collective endpoint running, so it holds no scattered rows."));
        Integer storedRank = store.storedRanks().get(scatterId);
        double[] aRows = store.received(scatterId).orElse(null);
        double[] b = store.received(broadcastId).orElse(null);
        if (aRows == null || storedRank == null) {
            throw missing(scatterId, "its rows of A");
        }
        if (b == null) {
            throw missing(broadcastId, "B");
        }
        if (storedRank != rank) {
            throw mismatch("Collective " + scatterId + " was sent to rank " + storedRank + ", not rank " + rank + ".");
        }
        if ((long) n * n != b.length) {
            throw mismatch("B has " + b.length + " values, not " + n + " x " + n + ".");
        }
        if (aRows.length % n != 0) {
            throw mismatch("The rows of A have " + aRows.length + " values, not whole rows of " + n + ".");
        }

        MatrixMultiply.Timed timed = executor.multiply(aRows, b, n, properties.computeTimeoutMillis(), nanoClock);
        double[] values = timed.values();
        keep(runId, rank, values);
        return new ComputeAck(nodeId, clock.tick(), runId, rank, aRows.length / n, timed.nanos(), VectorChecksum.of(values));
    }

    @Override
    public BlockReply fetchBlock(CallContext context, long runId, int rank) throws MatrixComputeException {
        Objects.requireNonNull(context, "context must not be null");
        requireIds(runId, rank);

        clock.update(context.senderLamport());
        Kept block;
        synchronized (kept) {
            block = kept.get(runId);
        }
        if (block == null || block.rank() != rank) {
            throw new MatrixComputeException(MatrixComputeException.Reason.BLOCK_MISSING, "Node " + nodeId
                    + " keeps no block of C for run " + runId + " and rank " + rank
                    + ": it was never computed here, or the result store's limit dropped it.");
        }
        return new BlockReply(nodeId, clock.tick(), runId, rank, block.values());
    }

    /** The run ids whose blocks are kept, oldest first (the order in which they would be dropped). */
    public List<Long> keptRunIds() {
        synchronized (kept) {
            return List.copyOf(new ArrayList<>(kept.keySet()));
        }
    }

    private void keep(long runId, int rank, double[] values) {
        synchronized (kept) {
            kept.remove(runId);   // a repeated run id becomes the newest entry
            kept.put(runId, new Kept(rank, values));
            Iterator<Long> oldest = kept.keySet().iterator();
            while (kept.size() > properties.resultStoreSize()) {
                oldest.next();
                oldest.remove();
            }
        }
    }

    private MatrixComputeException missing(long collectiveId, String what) {
        return new MatrixComputeException(MatrixComputeException.Reason.INPUT_MISSING, "Collective " + collectiveId
                + " (" + what + ") is not stored on node " + nodeId + ": it never arrived, or the store's limit dropped it.");
    }

    private static MatrixComputeException mismatch(String why) {
        return new MatrixComputeException(MatrixComputeException.Reason.INPUT_MISMATCH, why);
    }

    private static void requireIds(long runId, int rank) {
        if (runId < 1) {
            throw new IllegalArgumentException("runId must be >= 1, was " + runId);
        }
        if (rank < 0) {
            throw new IllegalArgumentException("rank must be >= 0, was " + rank);
        }
    }
}
