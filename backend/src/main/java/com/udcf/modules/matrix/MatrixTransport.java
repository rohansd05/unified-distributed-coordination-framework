package com.udcf.modules.matrix;

import com.udcf.modules.mpi.TransferException;
import com.udcf.modules.rmi.CallContext;

/**
 * How the root reaches one node's {@link MatrixEndpoint}: the seam between {@link ParallelMatrixExecutor}
 * (no sockets) and the transport. {@link RmiMatrixTransport} implements it over RMI; tests use an in-memory
 * one. As in Experiment 9, every failure is a {@link TransferException} with its stage (LOOKUP or CALL) and
 * the original exception, which the runner classifies; each call must be bounded by the transport's own
 * timeouts. Must be thread safe: the root calls several nodes at once.
 *
 * <p>No dedicated test: an interface (R6).</p>
 */
public interface MatrixTransport {

    /** Asks node {@code targetNodeId} to compute its block of run {@code runId}. */
    ComputeAck compute(int targetNodeId, CallContext context, long runId, int rank, int n, long scatterId, long broadcastId)
            throws TransferException;

    /** Fetches the block node {@code targetNodeId} computed for run {@code runId}. */
    BlockReply fetch(int targetNodeId, CallContext context, long runId, int rank) throws TransferException;
}
