package com.udcf.core.cluster;

import com.udcf.core.clock.LamportClock;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * One node of the shared cluster: co-hosted in this process, but owning its own clock,
 * status and protocol endpoints (R1).
 *
 * <p>Every module running on this node registers its endpoint here through
 * {@link #ensureService}, so {@link #crash()} takes the node down on every protocol at once
 * and every module sees the same failure (R10). A service starts lazily, the first time a
 * module asks for it.</p>
 *
 * <p><b>The clock is never reset</b> by {@link #crash()}, {@link #recover()} or
 * {@link #stop()}: it is modelled as stable storage, so post-recovery events stay ordered
 * after pre-crash events. See the crash-and-recovery note on {@link LamportClock}.</p>
 *
 * <p>Every lifecycle event is published with module {@code "cluster"}, this node's id and
 * {@code lamportTime = clock().tick()}.</p>
 *
 * <p><b>Thread safety.</b> {@code crash}, {@code recover}, {@code ensureService} and
 * {@code stop} are mutually exclusive on one node. {@link #status()} and {@link #isUp()}
 * read a volatile field and never wait.</p>
 */
public class ClusterNode {

    static final String MODULE = "cluster";
    private static final Logger log = LoggerFactory.getLogger(ClusterNode.class);

    private final int id;
    private final NodeCapacity capacity;
    private final NodePorts ports;
    private final ClusterEventBus bus;
    private final LamportClock clock = new LamportClock();
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, NodeService> services = new LinkedHashMap<>();   // guarded by lock

    private volatile NodeStatus status = NodeStatus.UP;
    private List<String> runningAtCrash = List.of();                           // guarded by lock

    public ClusterNode(int id, NodeCapacity capacity, NodePorts ports, ClusterEventBus bus) {
        if (id < 1) {
            throw new IllegalArgumentException("id must be >= 1, was " + id);
        }
        this.id = id;
        this.capacity = Objects.requireNonNull(capacity, "capacity must not be null");
        this.ports = Objects.requireNonNull(ports, "ports must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
    }

    public int id() {
        return id;
    }

    public NodeCapacity capacity() {
        return capacity;
    }

    public NodePorts ports() {
        return ports;
    }

    /** This node's Lamport clock, shared by every service on the node. */
    public LamportClock clock() {
        return clock;
    }

    public NodeStatus status() {
        return status;
    }

    public boolean isUp() {
        return status == NodeStatus.UP;
    }

    /**
     * Returns the service registered under {@code name}, creating and starting it on first
     * use.
     *
     * <ul>
     *   <li>On a crashed node, throws {@link NodeDownException} without calling the
     *       factory.</li>
     *   <li>If absent: calls the factory, rejects a result whose {@code name()} differs
     *       from {@code name} ({@link IllegalArgumentException}), starts it, registers it
     *       and publishes {@code SERVICE_STARTED}. If {@code start()} throws, nothing is
     *       registered, {@code SERVICE_START_FAILED} is published and the failure is
     *       rethrown wrapped in {@link IllegalStateException}.</li>
     *   <li>If present but not running (an earlier recovery failed): calls
     *       {@code recover()} on it and publishes {@code SERVICE_RECOVERED}. If that throws,
     *       the service stays registered (so the next call retries),
     *       {@code SERVICE_RECOVER_FAILED} is published and the failure is rethrown wrapped
     *       in {@link IllegalStateException}.</li>
     *   <li>Otherwise returns the existing service.</li>
     * </ul>
     *
     * <p><b>Type.</b> The stored service is cast to {@code S} unchecked. Every caller must
     * use the same service type for a given name; asking for an existing name with a
     * different type fails with {@link ClassCastException} at the call site.</p>
     */
    @SuppressWarnings("unchecked")
    public <S extends NodeService> S ensureService(String name, Function<ClusterNode, S> factory) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("service name must not be blank");
        }
        Objects.requireNonNull(factory, "factory must not be null");
        lock.lock();
        try {
            if (status == NodeStatus.CRASHED) {
                throw new NodeDownException(id);
            }
            NodeService existing = services.get(name);
            if (existing != null) {
                if (!existing.isRunning()) {
                    recoverRegistered(existing);
                }
                return (S) existing;
            }
            S created = Objects.requireNonNull(factory.apply(this), "factory returned null");
            if (!name.equals(created.name())) {
                throw new IllegalArgumentException("factory for '" + name
                        + "' returned a service named '" + created.name() + "'");
            }
            try {
                created.start();
            } catch (RuntimeException e) {
                publish("SERVICE_START_FAILED", "Service " + name + " failed to start",
                        Map.of("service", name, "error", describe(e)));
                throw new IllegalStateException(
                        "Service '" + name + "' failed to start on node " + id, e);
            }
            services.put(name, created);
            publish("SERVICE_STARTED", "Service " + name + " started", Map.of("service", name));
            return created;
        } finally {
            lock.unlock();
        }
    }

    private void recoverRegistered(NodeService service) {
        String name = service.name();
        try {
            service.recover();
        } catch (RuntimeException e) {
            publish("SERVICE_RECOVER_FAILED", "Service " + name + " failed to recover",
                    Map.of("service", name, "error", describe(e)));
            throw new IllegalStateException(
                    "Service '" + name + "' failed to recover on node " + id, e);
        }
        publish("SERVICE_RECOVERED", "Service " + name + " recovered", Map.of("service", name));
    }

    public Optional<NodeService> service(String name) {
        lock.lock();
        try {
            return Optional.ofNullable(services.get(name));
        } finally {
            lock.unlock();
        }
    }

    /** Names of the registered services that are running, in registration order. */
    public List<String> runningServices() {
        lock.lock();
        try {
            return services.values().stream()
                    .filter(NodeService::isRunning)
                    .map(NodeService::name)
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Crashes the node on every protocol: calls {@code crash()} on each running service
     * (a throwing service does not stop the others), sets CRASHED and publishes
     * {@code NODE_CRASHED} with data {@code {services, failures}}.
     *
     * @return {@code false}, with no event, if the node was already crashed
     */
    public boolean crash() {
        lock.lock();
        try {
            if (status == NodeStatus.CRASHED) {
                return false;
            }
            List<String> running = new ArrayList<>();
            List<Map<String, String>> failures = new ArrayList<>();
            for (NodeService service : services.values()) {
                if (!service.isRunning()) {
                    continue;
                }
                running.add(service.name());
                try {
                    service.crash();
                } catch (RuntimeException e) {
                    failures.add(failure(service.name(), e));
                }
            }
            status = NodeStatus.CRASHED;
            runningAtCrash = List.copyOf(running);
            publish("NODE_CRASHED", "Node " + id + " crashed",
                    Map.of("services", runningAtCrash, "failures", List.copyOf(failures)));
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Recovers the node: sets UP, calls {@code recover()} on each service that was running
     * at the last crash, and publishes {@code NODE_RECOVERED} with data
     * {@code {services, failures}}. Failures are reported, never thrown; a service that
     * failed to recover is retried by the next {@link #ensureService} for it.
     *
     * @return {@code false}, with no event, if the node was already up
     */
    public boolean recover() {
        lock.lock();
        try {
            if (status == NodeStatus.UP) {
                return false;
            }
            status = NodeStatus.UP;
            List<Map<String, String>> failures = new ArrayList<>();
            for (String name : runningAtCrash) {
                try {
                    services.get(name).recover();
                } catch (RuntimeException e) {
                    failures.add(failure(name, e));
                }
            }
            List<String> recovered = runningAtCrash;
            runningAtCrash = List.of();
            publish("NODE_RECOVERED", "Node " + id + " recovered",
                    Map.of("services", recovered, "failures", List.copyOf(failures)));
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Stops every registered service, logging failures. Used at shutdown; no event. */
    public void stop() {
        lock.lock();
        try {
            for (NodeService service : services.values()) {
                try {
                    service.stop();
                } catch (RuntimeException e) {
                    log.warn("Node {}: service {} failed to stop", id, service.name(), e);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void publish(String type, String message, Map<String, Object> data) {
        bus.publish(EventDraft.of(MODULE, id, type, clock.tick()).withMessage(message).withData(data));
    }

    private static Map<String, String> failure(String service, RuntimeException e) {
        return Map.of("service", service, "error", describe(e));
    }

    private static String describe(RuntimeException e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
