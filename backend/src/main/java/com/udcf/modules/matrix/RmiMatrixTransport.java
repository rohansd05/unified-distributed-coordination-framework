package com.udcf.modules.matrix;

import com.udcf.modules.mpi.TransferException;
import com.udcf.modules.rmi.CallContext;
import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.RemoteEndpointLookup;
import com.udcf.modules.rmi.RmiModule;

import java.util.Objects;

/**
 * {@link MatrixTransport} over the Experiment 1 RMI layer (step E10b, link L6), as Experiment 9's
 * RmiCollectiveTransport: every call looks the target's {@link MatrixEndpoint} up afresh (no stub caching),
 * then calls it. Anything the lookup throws is {@code TransferException(LOOKUP, cause)}; anything the call
 * throws, including the node's {@link MatrixComputeException}, is {@code TransferException(CALL, cause)}.
 * Nothing is swallowed or retried; the timeouts and sockets are Experiment 1's. Thread safe: no state of its
 * own beyond the lookup.
 *
 * <p>A compute's round trip as measured by the root therefore covers the lookup (with RMI's DGC dirty call
 * when no live stub is held), the call, the node's queue wait and multiply, and the reply.</p>
 */
public final class RmiMatrixTransport implements MatrixTransport {

    private final RemoteEndpointLookup lookup;

    public RmiMatrixTransport(RemoteEndpointLookup lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup must not be null");
    }

    /** A transport on the RMI module's own lookup. The caller must have started RMI ({@code ensureStarted}). */
    public static RmiMatrixTransport of(RmiModule module) {
        return new RmiMatrixTransport(Objects.requireNonNull(module, "module must not be null").remoteLookup());
    }

    @Override
    public ComputeAck compute(int targetNodeId, CallContext context, long runId, int rank, int n, long scatterId,
                              long broadcastId) throws TransferException {
        MatrixEndpoint endpoint = find(targetNodeId);
        try {
            return endpoint.computeBlock(context, runId, rank, n, scatterId, broadcastId);
        } catch (Exception e) {
            throw new TransferException(CallStage.CALL, e);
        }
    }

    @Override
    public BlockReply fetch(int targetNodeId, CallContext context, long runId, int rank) throws TransferException {
        MatrixEndpoint endpoint = find(targetNodeId);
        try {
            return endpoint.fetchBlock(context, runId, rank);
        } catch (Exception e) {
            throw new TransferException(CallStage.CALL, e);
        }
    }

    private MatrixEndpoint find(int targetNodeId) throws TransferException {
        try {
            return lookup.lookup(targetNodeId, MatrixEndpoint.BOUND_NAME, MatrixEndpoint.class);
        } catch (Exception e) {
            throw new TransferException(CallStage.LOOKUP, e);
        }
    }
}
