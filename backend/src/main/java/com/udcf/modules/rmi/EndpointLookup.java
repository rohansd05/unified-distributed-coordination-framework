package com.udcf.modules.rmi;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;

/**
 * Finds the {@link NodeEndpoint} of a node, the seam between {@link RmiInvoker} and the
 * transport. Step E1b provides the registry-backed {@link CallPath#REMOTE} lookup, and a
 * {@link CallPath#LOCAL} one that returns the endpoint object itself; tests use fakes.
 *
 * <p>No dedicated test: an interface without behaviour (R6).</p>
 */
public interface EndpointLookup {

    /** The endpoint of node {@code nodeId}: a stub for {@link CallPath#REMOTE}, the object itself for LOCAL. */
    NodeEndpoint lookup(int nodeId) throws RemoteException, NotBoundException;

    /** How calls through this lookup reach the endpoint. */
    CallPath path();
}
