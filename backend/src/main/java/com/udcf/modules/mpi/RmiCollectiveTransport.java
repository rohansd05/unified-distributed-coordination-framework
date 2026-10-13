package com.udcf.modules.mpi;

import com.udcf.modules.rmi.CallContext;
import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.RemoteEndpointLookup;
import com.udcf.modules.rmi.RmiModule;

import java.util.Objects;

/**
 * {@link CollectiveTransport} over the Experiment 1 RMI layer (step E9b): every transfer looks the
 * target's {@link CollectiveEndpoint} up afresh in the target node's registry (no stub caching),
 * then calls it.
 *
 * <p><b>Stages, as in Experiment 1.</b> Anything thrown by the lookup is a
 * {@code TransferException(LOOKUP, cause)}; anything thrown by the call (an RMI failure, or the
 * method's own exception, which RMI rethrows) is a {@code TransferException(CALL, cause)}. Nothing is
 * swallowed or retried. Timeouts and sockets are Experiment 1's: the lookup's client factory carries
 * the connect timeout and the call timeout.</p>
 *
 * <p><b>Root-agnostic.</b> The calling node is the {@link CallContext}'s sender, which the executor
 * sets from the collective's root, so one instance serves every root. Thread safe (no state of its
 * own beyond the lookup).</p>
 */
public final class RmiCollectiveTransport implements CollectiveTransport {

    /**
     * What a transfer's {@code latencyNanos} covers over this transport, to show next to the figures.
     * Unlike Experiment 1, where the lookup and the call are separate figures, they are one here.
     */
    public static final String LATENCY_BASIS = "Measured on the root around the whole transfer: a fresh lookup of "
            + "CollectiveEndpoint in the target node's RMI registry (including RMI's distributed garbage collection "
            + "\"dirty\" call when this process holds no other live stub for that endpoint), then the method call with "
            + "its arguments and reply over a real loopback socket. Experiment 1 shows the lookup and the call as "
            + "separate figures; here they are one.";

    private final RemoteEndpointLookup lookup;

    /** @param lookup the registry lookup to use (one client channel per node); {@link #of} uses the RMI module's */
    public RmiCollectiveTransport(RemoteEndpointLookup lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup must not be null");
    }

    /** A transport on the RMI module's own lookup (link L6). The caller must have started RMI ({@code ensureStarted}). */
    public static RmiCollectiveTransport of(RmiModule module) {
        Objects.requireNonNull(module, "module must not be null");
        return new RmiCollectiveTransport(module.remoteLookup());
    }

    @Override
    public ReceiveReceipt deliver(int targetNodeId, CallContext context, long collectiveId, int rank, double[] values)
            throws TransferException {
        CollectiveEndpoint endpoint = find(targetNodeId);
        try {
            return endpoint.receive(context, collectiveId, rank, values);
        } catch (Exception e) {
            throw new TransferException(CallStage.CALL, e);
        }
    }

    @Override
    public Contribution collect(int targetNodeId, CallContext context, long collectiveId, int rank, int count, long seed)
            throws TransferException {
        CollectiveEndpoint endpoint = find(targetNodeId);
        try {
            return endpoint.contribute(context, collectiveId, rank, count, seed);
        } catch (Exception e) {
            throw new TransferException(CallStage.CALL, e);
        }
    }

    private CollectiveEndpoint find(int targetNodeId) throws TransferException {
        try {
            return lookup.lookup(targetNodeId, CollectiveEndpoint.BOUND_NAME, CollectiveEndpoint.class);
        } catch (Exception e) {
            throw new TransferException(CallStage.LOOKUP, e);
        }
    }
}
