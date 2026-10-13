package com.udcf.modules.mpi;

import com.udcf.modules.rmi.CallContext;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * The remote object each node exports for Experiment 9 (docs/HANDOFF.md 7). Step E9b exports a
 * {@link CollectiveEndpointLogic} under {@link #BOUND_NAME} in the node's Experiment 1 registry,
 * so a local and a remote call run the same code.
 *
 * <p>Only {@link CallContext}, {@code long}, {@code int}, {@code double[]} and the two reply
 * records cross the wire; E9b's serial filter must allow {@code double[]} up to
 * {@code max-payload-elements}.</p>
 *
 * <p>Lamport (link L4): the caller ticks and sends its time in {@code context}; the node updates
 * its clock on receipt, does the work and stamps the reply with a tick.</p>
 *
 * <p>No dedicated test: an interface (R6); CollectiveEndpointLogicTest covers its implementation.</p>
 */
public interface CollectiveEndpoint extends Remote {

    /** The name in each node's RMI registry. */
    String BOUND_NAME = "CollectiveEndpoint";

    /**
     * Receives a payload for collective {@code collectiveId}: the whole vector (broadcast) or
     * this rank's block (scatter). The node keeps it and reports what it actually received.
     */
    ReceiveReceipt receive(CallContext context, long collectiveId, int rank, double[] values) throws RemoteException;

    /**
     * Produces this node's contribution to gather {@code collectiveId}: {@code count} values,
     * computed on this node from its own id and {@code seed}.
     */
    Contribution contribute(CallContext context, long collectiveId, int rank, int count, long seed) throws RemoteException;
}
