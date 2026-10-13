package com.udcf.modules.matrix;

import com.udcf.modules.rmi.CallContext;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * The remote object each node exports for Experiment 10 (step E10b), bound under {@link #BOUND_NAME}
 * in the node's Experiment 1 registry beside {@code NodeEndpoint} and {@code CollectiveEndpoint}.
 *
 * <p>Two methods, so the compute and the transfer of its result are measured separately: the root
 * first asks every node to multiply (concurrently), then fetches the blocks one by one. The node reads
 * its rows of A and the whole of B from what the Experiment 9 scatter and broadcast stored on it.</p>
 *
 * <p>Only {@link CallContext} and primitives are arguments, so the node's serial filter is E1b's
 * ({@code RmiSerialFilter.from}), which rejects every array.</p>
 *
 * <p>Lamport (link L4): the caller ticks and sends its time in {@code context}; the node updates its
 * clock on receipt and stamps the reply with a tick. A refusal ({@link MatrixComputeException}) carries
 * no time, so the caller merges nothing.</p>
 *
 * <p>No dedicated test: an interface (R6); MatrixEndpointLogicTest covers its implementation.</p>
 */
public interface MatrixEndpoint extends Remote {

    /** The name in each node's RMI registry. */
    String BOUND_NAME = "MatrixEndpoint";

    /**
     * Multiplies this node's stored rows of A (scatter {@code scatterId}) by the stored B (broadcast
     * {@code broadcastId}) on the node's Experiment 2 executor, keeps the rows of C under {@code runId}
     * and reports how long the multiply took and the checksum of the result.
     */
    ComputeAck computeBlock(CallContext context, long runId, int rank, int n, long scatterId, long broadcastId)
            throws RemoteException, MatrixComputeException;

    /** The rows of C kept for {@code runId} and {@code rank}. */
    BlockReply fetchBlock(CallContext context, long runId, int rank) throws RemoteException, MatrixComputeException;
}
