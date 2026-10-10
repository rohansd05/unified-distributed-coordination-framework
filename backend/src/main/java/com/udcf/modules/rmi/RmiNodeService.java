package com.udcf.modules.rmi;

import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeService;

import java.rmi.AlreadyBoundException;
import java.rmi.NoSuchObjectException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Experiment 1 transport: a node's "rmi" service, an RMI registry on 127.0.0.1:{@code ports().rmi()}
 * (110k) with the node's {@link NodeEndpoint} exported on the same port and bound under
 * {@link NodeEndpoint#BOUND_NAME}.
 *
 * <p><b>One listener per node.</b> The registry and the endpoint use the same port and the same
 * {@link LoopbackSocketFactory} instance as client and server factory, so RMI serves both from
 * one listener. The service creates that factory once and uses it for every start and recovery
 * (RMI keeps the first equal factory per port for the life of the JVM; see the factory).</p>
 *
 * <p><b>Lifecycle.</b> {@link #start()} and {@link #recover()} take a new endpoint from the
 * supplier (so a recovered node is a new "process": fresh uptime, new object id), create a new
 * registry, export the endpoint with {@link RmiSerialFilter} and bind it; {@code running} becomes
 * true only after all of that succeeded. On a failure everything already exported is unexported,
 * and an {@link IllegalStateException} names the node and port. Starting a running service does
 * nothing. {@link #crash()} and {@link #stop()} behave like a process death: everything is
 * unexported with {@code force=true}, every accepted connection is closed (so a peer's pooled
 * connection fails instead of being answered), and they return only once the port is free. The
 * node's Lamport clock is never touched here.</p>
 *
 * <p><b>Threads.</b> This class creates none; RMI owns its accept, connection and reaper
 * threads. Lifecycle methods are synchronized.</p>
 */
public class RmiNodeService implements NodeService {

    public static final String NAME = "rmi";

    private static final Duration LISTENER_EXIT_TIMEOUT = Duration.ofSeconds(5);

    private final ClusterNode node;
    private final Supplier<? extends NodeEndpoint> endpoints;
    private final LoopbackSocketFactory factory;
    private final RmiSerialFilter filter;

    private volatile boolean running;
    private volatile NodeEndpoint endpoint;   // the exported object; null while not running
    private Registry registry;                // guarded by this

    /**
     * @param endpoints called on every start and recovery for the object to export; in
     *                  production a new {@link NodeEndpointLogic} for this node
     */
    public RmiNodeService(ClusterNode node, RmiProperties properties, RmiTransportProperties transport,
                          Supplier<? extends NodeEndpoint> endpoints) {
        this.node = Objects.requireNonNull(node, "node must not be null");
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints must not be null");
        this.factory = LoopbackSocketFactory.from(properties, transport);
        this.filter = RmiSerialFilter.from(transport.serialFilter());
    }

    /**
     * The node's RMI service, created and started on first use through
     * {@link ClusterNode#ensureService} (so a crashed node throws {@code NodeDownException}).
     * Every start and recovery exports a new {@link NodeEndpointLogic} built on the node's shared
     * clock.
     */
    public static RmiNodeService on(ClusterNode node, RmiProperties properties, RmiTransportProperties transport) {
        Objects.requireNonNull(properties, "properties must not be null");
        return node.ensureService(NAME, n -> new RmiNodeService(n, properties, transport,
                () -> NodeEndpointLogic.forNode(n, properties, System::nanoTime)));
    }

    /** The node's RMI service if it has ever been started; never starts one. */
    public static Optional<RmiNodeService> find(ClusterNode node) {
        return node.service(NAME).map(RmiNodeService.class::cast);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public synchronized void start() {
        open();
    }

    @Override
    public synchronized void crash() {
        close();
    }

    @Override
    public synchronized void recover() {
        open();
    }

    @Override
    public synchronized void stop() {
        close();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public int nodeId() {
        return node.id();
    }

    public int port() {
        return node.ports().rmi();
    }

    /**
     * The exported endpoint object itself (for the LOCAL path: a direct call, no stub).
     *
     * @throws IllegalStateException "node N is not running" while the service is not running
     */
    public NodeEndpoint localEndpoint() {
        NodeEndpoint current = endpoint;
        if (!running || current == null) {
            throw new IllegalStateException("node " + node.id() + " is not running");
        }
        return current;
    }

    LoopbackSocketFactory socketFactory() {
        return factory;
    }

    RmiSerialFilter serialFilter() {
        return filter;
    }

    /** The running registry, for tests; null while not running. */
    synchronized Registry registry() {
        return registry;
    }

    // ------------------------------------------------------------------ lifecycle

    private void open() {
        if (running) {
            return;
        }
        RmiHostName.ensureLoopback();
        NodeEndpoint fresh = Objects.requireNonNull(endpoints.get(), "the endpoint supplier returned null");
        Registry created = null;
        boolean exported = false;
        try {
            created = LocateRegistry.createRegistry(port(), factory, factory);
            Remote stub = UnicastRemoteObject.exportObject(fresh, port(), factory, factory, filter);
            exported = true;
            created.bind(NodeEndpoint.BOUND_NAME, stub);
        } catch (RemoteException | AlreadyBoundException | RuntimeException e) {
            IllegalStateException failure = new IllegalStateException("Node " + node.id()
                    + " cannot start RMI on port " + port() + ": " + e.getMessage(), e);
            if (exported) {
                unexport(fresh);
            }
            if (created != null) {
                unexport(created);
            }
            try {
                releaseListener();
            } catch (IllegalStateException releaseFailure) {
                failure.addSuppressed(releaseFailure);
            } finally {
                factory.closeAcceptedConnections();
            }
            throw failure;
        }
        registry = created;
        endpoint = fresh;
        running = true;
    }

    private void close() {
        running = false;
        NodeEndpoint exportedEndpoint = endpoint;
        Registry exportedRegistry = registry;
        try {
            if (exportedEndpoint != null) {
                unexport(exportedEndpoint);
            }
            if (exportedRegistry != null) {
                unexport(exportedRegistry);   // the last export on the port: RMI closes the listener
            }
            releaseListener();
        } finally {
            // Always, even if the wait above timed out: the service is never left half-closed.
            factory.closeAcceptedConnections();
            endpoint = null;
            registry = null;
            running = false;
        }
    }

    /**
     * Waits until the closed listener has really released the port.
     *
     * <p>Do not remove this wait. Measured in E1b on Linux (JDK 21): right after RMI closes its
     * listener (when the last object on the port is unexported), the port can still be bound,
     * because RMI's accept thread is still inside {@code accept()} and the JDK closes the
     * descriptor only when that thread leaves. An immediate recovery would then fail to bind.
     * Windows released the port at once in every run.</p>
     */
    private void releaseListener() {
        boolean idle;
        try {
            idle = factory.awaitClosedListenersIdle(LISTENER_EXIT_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Node " + node.id() + ": interrupted waiting for the RMI listener on port "
                    + port() + " to close", e);
        }
        if (!idle) {
            throw new IllegalStateException("Node " + node.id() + ": the RMI accept thread did not leave accept() within "
                    + LISTENER_EXIT_TIMEOUT.toMillis() + " ms; port " + port() + " may still be bound");
        }
    }

    private static void unexport(Remote object) {
        try {
            UnicastRemoteObject.unexportObject(object, true);
        } catch (NoSuchObjectException alreadyGone) {
            // not exported (any more): nothing to undo
        }
    }
}
