package com.udcf.modules.rmi;

import com.udcf.core.cluster.Cluster;

import java.rmi.NotBoundException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/**
 * The {@link CallPath#REMOTE} lookup: the node's own RMI registry, over a real socket.
 *
 * <p><b>No caching.</b> Every {@link #lookup} asks the target's registry again
 * ({@code LocateRegistry.getRegistry} on its RMI port, then {@code lookup(BOUND_NAME)}), so the
 * measured lookup time is real and a crashed node fails at the lookup stage. The returned object
 * is a stub (a {@link java.lang.reflect.Proxy} whose handler is a
 * {@link java.rmi.server.RemoteObjectInvocationHandler}), never the node's object, so every call
 * crosses a socket even though the node is in this process (R1).</p>
 *
 * <p>Thread safe: the only state is the port mapping and the client socket factory.</p>
 */
public final class RemoteEndpointLookup implements EndpointLookup {

    private final IntUnaryOperator rmiPort;
    private final LoopbackSocketFactory clientFactory;

    /**
     * @param rmiPort       the RMI port of a node, by id
     * @param clientFactory makes the client sockets (loopback, connect and call timeouts)
     */
    public RemoteEndpointLookup(IntUnaryOperator rmiPort, LoopbackSocketFactory clientFactory) {
        this.rmiPort = Objects.requireNonNull(rmiPort, "rmiPort must not be null");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory must not be null");
    }

    /** A lookup on {@code cluster}'s RMI ports ({@code ports().rmi()}), with the configured timeouts. */
    public static RemoteEndpointLookup of(Cluster cluster, RmiProperties properties, RmiTransportProperties transport) {
        Objects.requireNonNull(cluster, "cluster must not be null");
        return new RemoteEndpointLookup(nodeId -> cluster.node(nodeId).ports().rmi(),
                LoopbackSocketFactory.from(properties, transport));
    }

    @Override
    public NodeEndpoint lookup(int nodeId) throws RemoteException, NotBoundException {
        Remote found = registry(nodeId).lookup(NodeEndpoint.BOUND_NAME);
        if (!(found instanceof NodeEndpoint stub)) {
            throw new IllegalStateException("node " + nodeId + " bound " + NodeEndpoint.BOUND_NAME + " to "
                    + (found == null ? "null" : found.getClass().getName()) + ", not a NodeEndpoint");
        }
        return stub;
    }

    @Override
    public CallPath path() {
        return CallPath.REMOTE;
    }

    /**
     * The contents of node {@code nodeId}'s registry, read over the network: {@code list()},
     * then a lookup of each name for the class of the stub bound under it.
     *
     * @throws RemoteException   if the node cannot be reached (for example it is down); never an
     *                           empty snapshot in its place
     * @throws NotBoundException if a listed name was unbound before its lookup
     */
    public RegistrySnapshot snapshot(int nodeId) throws RemoteException, NotBoundException {
        Registry registry = registry(nodeId);
        List<RegistrySnapshot.BoundName> names = new ArrayList<>();
        for (String name : registry.list()) {
            names.add(new RegistrySnapshot.BoundName(name, registry.lookup(name).getClass().getName()));
        }
        return new RegistrySnapshot(nodeId, rmiPort.applyAsInt(nodeId), names);
    }

    private Registry registry(int nodeId) throws RemoteException {
        // A stub for the registry; no network until it is used.
        return LocateRegistry.getRegistry(RmiHostName.LOOPBACK_HOST, rmiPort.applyAsInt(nodeId), clientFactory);
    }
}
