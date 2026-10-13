package com.udcf.modules.mpi;

import com.udcf.modules.rmi.CallContext;

/**
 * How the root reaches one node: the seam between {@link CollectiveExecutor} (pure) and the
 * transport. Step E9b implements it over RMI: look up {@link CollectiveEndpoint#BOUND_NAME} in the
 * target node's registry, then call it. Tests use a scripted fake.
 *
 * <p>Every failure is a {@link TransferException} that says whether the endpoint could not be
 * reached ({@code LOOKUP}) or the call itself failed ({@code CALL}), wrapping the original
 * exception. The executor classifies the original as Experiment 1 does. Each call must be bounded
 * by the transport's own timeouts: the executor cannot interrupt a transfer.</p>
 *
 * <p>No dedicated test: an interface (R6).</p>
 */
public interface CollectiveTransport {

    /** Sends {@code values} for {@code rank} of collective {@code collectiveId} to node {@code targetNodeId}. */
    ReceiveReceipt deliver(int targetNodeId, CallContext context, long collectiveId, int rank, double[] values)
            throws TransferException;

    /** Asks node {@code targetNodeId} for its {@code count}-element contribution to gather {@code collectiveId}. */
    Contribution collect(int targetNodeId, CallContext context, long collectiveId, int rank, int count, long seed)
            throws TransferException;
}
