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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * <p><b>Extra exports (step E9b).</b> Objects from {@link RmiExtraExport}s (Experiment 9's
 * {@code CollectiveEndpoint}) are exported on the same port with the same factory instance and their
 * own filter, and bound in the same registry after the {@link NodeEndpoint}: a fresh object on every
 * start and recovery. A failure rolls them back with everything else; crash and stop unexport them
 * first. Without extras the service behaves exactly as in E1b.</p>
 *
 * <p><b>Threads.</b> This class creates none; RMI owns its accept, connection and reaper
 * threads. Lifecycle methods are synchronized. The fields read by {@link #localEndpoint()} and
 * {@link #localExport(String)} outside the lock are volatile and published in order: an open sets
 * the objects before {@code running}, a close clears {@code running} before the objects.</p>
 */
public class RmiNodeService implements NodeService {

    public static final String NAME = "rmi";

    private static final Duration LISTENER_EXIT_TIMEOUT = Duration.ofSeconds(5);

    private final ClusterNode node;
    private final Supplier<? extends NodeEndpoint> endpoints;
    private final LoopbackSocketFactory factory;
    private final RmiSerialFilter filter;
    private final List<RmiExtraExport> extras;

    private volatile boolean running;
    private volatile NodeEndpoint endpoint;   // the exported object; null while not running
    private volatile Map<String, Remote> exportedExtras = Map.of();   // by bound name, export order; empty while not running
    private Registry registry;                // guarded by this

    /**
     * @param endpoints called on every start and recovery for the object to export; in
     *                  production a new {@link NodeEndpointLogic} for this node
     */
    public RmiNodeService(ClusterNode node, RmiProperties properties, RmiTransportProperties transport,
                          Supplier<? extends NodeEndpoint> endpoints) {
        this(node, properties, transport, endpoints, List.of());
    }

    /**
     * @param endpoints called on every start and recovery for the {@link NodeEndpoint} to export
     * @param extras    further objects to export and bind on the same port (each bound name once, never
     *                  {@link NodeEndpoint#BOUND_NAME})
     */
    public RmiNodeService(ClusterNode node, RmiProperties properties, RmiTransportProperties transport,
                          Supplier<? extends NodeEndpoint> endpoints, List<? extends RmiExtraExport> extras) {
        this.node = Objects.requireNonNull(node, "node must not be null");
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints must not be null");
        this.factory = LoopbackSocketFactory.from(properties, transport);
        this.filter = RmiSerialFilter.from(transport.serialFilter());
        this.extras = List.copyOf(Objects.requireNonNull(extras, "extras must not be null"));
        Set<String> names = new HashSet<>(Set.of(NodeEndpoint.BOUND_NAME));
        for (RmiExtraExport extra : this.extras) {
            String name = extra.boundName();
            if (name == null || name.isBlank() || !names.add(name)) {
                throw new IllegalArgumentException("every extra export needs its own bound name, not " + name);
            }
        }
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

    /**
     * As {@link #on(ClusterNode, RmiProperties, RmiTransportProperties)}, with {@code extras} exported
     * beside the {@link NodeEndpoint}. The extras are fixed when the service is first created.
     */
    public static RmiNodeService on(ClusterNode node, RmiProperties properties, RmiTransportProperties transport,
                                    List<? extends RmiExtraExport> extras) {
        Objects.requireNonNull(properties, "properties must not be null");
        return node.ensureService(NAME, n -> new RmiNodeService(n, properties, transport,
                () -> NodeEndpointLogic.forNode(n, properties, System::nanoTime), extras));
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

    /**
     * The object exported under {@code boundName} by an extra export, itself (a direct call, no stub);
     * a new one after every start and recovery.
     *
     * @throws IllegalArgumentException if no extra export has that name
     * @throws IllegalStateException    "node N is not running" while the service is not running
     */
    public Remote localExport(String boundName) {
        Objects.requireNonNull(boundName, "boundName must not be null");
        if (extras.stream().noneMatch(extra -> extra.boundName().equals(boundName))) {
            throw new IllegalArgumentException("node " + node.id() + " exports nothing under " + boundName);
        }
        Remote current = running ? exportedExtras.get(boundName) : null;
        if (current == null) {
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
        Map<String, Remote> extrasExported = new LinkedHashMap<>();   // export order, for the rollback
        try {
            created = LocateRegistry.createRegistry(port(), factory, factory);
            Remote stub = UnicastRemoteObject.exportObject(fresh, port(), factory, factory, filter);
            exported = true;
            created.bind(NodeEndpoint.BOUND_NAME, stub);
            for (RmiExtraExport extra : extras) {
                Remote object = newExtra(extra);
                Remote extraStub = UnicastRemoteObject.exportObject(object, port(), factory, factory, extra.filter());
                extrasExported.put(extra.boundName(), object);
                created.bind(extra.boundName(), extraStub);
            }
        } catch (RemoteException | AlreadyBoundException | RuntimeException e) {
            IllegalStateException failure = new IllegalStateException("Node " + node.id()
                    + " cannot start RMI on port " + port() + ": " + e.getMessage(), e);
            unexportInReverse(extrasExported);
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
        exportedExtras = Collections.unmodifiableMap(extrasExported);
        running = true;   // last: the objects above are published first
    }

    private Remote newExtra(RmiExtraExport extra) {
        Remote object = Objects.requireNonNull(extra.newEndpoint(node), "extra export " + extra.boundName() + " returned null");
        if (!extra.remoteType().isInstance(object)) {
            throw new IllegalStateException("extra export " + extra.boundName() + " returned a "
                    + object.getClass().getName() + ", not a " + extra.remoteType().getName());
        }
        return object;
    }

    private static void unexportInReverse(Map<String, Remote> objects) {
        List<Remote> inOrder = new ArrayList<>(objects.values());
        for (int i = inOrder.size() - 1; i >= 0; i--) {
            unexport(inOrder.get(i));
        }
    }

    private void close() {
        running = false;
        NodeEndpoint exportedEndpoint = endpoint;
        Registry exportedRegistry = registry;
        Map<String, Remote> extrasToClose = exportedExtras;
        try {
            unexportInReverse(extrasToClose);   // exported last, so unexported first
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
            exportedExtras = Map.of();
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
