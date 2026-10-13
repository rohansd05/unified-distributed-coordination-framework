package com.udcf.modules.matrix;

import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.modules.mpi.CollectiveEndpointExport;
import com.udcf.modules.multithreading.MultithreadingProperties;
import com.udcf.modules.rmi.RmiExtraExport;
import com.udcf.modules.rmi.RmiProperties;
import com.udcf.modules.rmi.RmiSerialFilter;
import com.udcf.modules.rmi.RmiTransportProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ObjectInputFilter;
import java.rmi.Remote;
import java.util.Objects;
import java.util.function.Function;

/**
 * Experiment 10's export on every node (step E10b): a {@link MatrixEndpointLogic} bound under
 * {@link MatrixEndpoint#BOUND_NAME} in the node's Experiment 1 registry, on the same port and socket
 * factory as its other objects (an {@link RmiExtraExport} bean, picked up by {@code RmiModule}). A fresh
 * logic, with no kept blocks, on every start and every recovery, on the node's own Lamport clock and its
 * own Experiment 2 executor; it reads A and B from the node's current collective endpoint.
 *
 * <p><b>Serial filter:</b> E1b's {@link RmiSerialFilter#from}: only {@code CallContext}, every array
 * rejected, limits from {@code udcf.rmi.serial-filter}. The arguments are a {@code CallContext} and
 * primitives, so nothing else is needed.</p>
 *
 * <p><b>Startup check:</b> {@code udcf.matrix.compute-timeout-millis} must be below
 * {@code udcf.rmi.call-timeout-millis}, so a node always answers (with its block or its reason) before the
 * caller's read timeout.</p>
 */
@Component
public class MatrixEndpointExport implements RmiExtraExport {

    private final MatrixRunProperties properties;
    private final Function<ClusterNode, NodeExecutor> executors;
    private final RmiSerialFilter filter;

    /** The Spring constructor: each node computes on its own Experiment 2 executor. */
    @Autowired
    public MatrixEndpointExport(MatrixRunProperties properties, RmiProperties rmi, RmiTransportProperties transport,
                                MultithreadingProperties multithreading, MeterRegistry meterRegistry, ClusterEventBus bus) {
        this(properties, rmi, transport, node -> NodeExecutor.experimentTwo(node, multithreading, meterRegistry, bus));
    }

    /** @param executors the executor of each node (tests pass their own) */
    public MatrixEndpointExport(MatrixRunProperties properties, RmiProperties rmi, RmiTransportProperties transport,
                                Function<ClusterNode, NodeExecutor> executors) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        requireComputeBelowCall(properties, rmi);
        this.executors = Objects.requireNonNull(executors, "executors must not be null");
        this.filter = RmiSerialFilter.from(Objects.requireNonNull(transport, "transport must not be null").serialFilter());
    }

    /**
     * @throws IllegalArgumentException if compute-timeout-millis is not below call-timeout-millis
     */
    public static void requireComputeBelowCall(MatrixRunProperties properties, RmiProperties rmi) {
        Objects.requireNonNull(properties, "properties must not be null");
        Objects.requireNonNull(rmi, "rmi must not be null");
        if (properties.computeTimeoutMillis() >= rmi.callTimeoutMillis()) {
            throw new IllegalArgumentException("udcf.matrix.compute-timeout-millis (" + properties.computeTimeoutMillis()
                    + ") must be below udcf.rmi.call-timeout-millis (" + rmi.callTimeoutMillis()
                    + "), so a node answers before the caller's read timeout");
        }
    }

    @Override
    public String boundName() {
        return MatrixEndpoint.BOUND_NAME;
    }

    @Override
    public Class<? extends Remote> remoteType() {
        return MatrixEndpoint.class;
    }

    @Override
    public Remote newEndpoint(ClusterNode node) {
        Objects.requireNonNull(node, "node must not be null");
        return new MatrixEndpointLogic(node.id(), node.clock(), () -> CollectiveEndpointExport.current(node),
                executors.apply(node), properties, System::nanoTime);
    }

    @Override
    public ObjectInputFilter filter() {
        return filter;
    }
}
