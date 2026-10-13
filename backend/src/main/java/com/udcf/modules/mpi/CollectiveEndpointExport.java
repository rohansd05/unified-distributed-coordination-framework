package com.udcf.modules.mpi;

import com.udcf.core.cluster.ClusterNode;
import com.udcf.modules.rmi.CallContext;
import com.udcf.modules.rmi.RmiExtraExport;
import com.udcf.modules.rmi.RmiNodeService;
import com.udcf.modules.rmi.RmiSerialFilter;
import com.udcf.modules.rmi.RmiTransportProperties;
import org.springframework.stereotype.Component;

import java.io.ObjectInputFilter;
import java.rmi.Remote;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Experiment 9's export on every node (step E9b): a {@link CollectiveEndpointLogic} bound under
 * {@link CollectiveEndpoint#BOUND_NAME} in the node's Experiment 1 registry, on the same port and
 * socket factory as its {@code NodeEndpoint}. Each node's {@link RmiNodeService} exports it (it is
 * an {@link RmiExtraExport} bean, picked up by {@code RmiModule}): a fresh logic, with an empty
 * store, on every start and every recovery, on the node's own Lamport clock.
 *
 * <p><b>Serial filter</b> ({@link #filterFor}): exactly {@link CallContext} and {@code double[]}
 * (at most {@code max-payload-elements} elements); every other class and every other array type is
 * rejected. Byte limit: 8 bytes per allowed element plus {@code udcf.rmi.serial-filter.max-bytes}
 * as the overhead of the rest of the call; depth and reference limits from
 * {@code udcf.rmi.serial-filter}. No setting of its own. The reply direction is not filtered (the
 * E1b known issue).</p>
 */
@Component
public class CollectiveEndpointExport implements RmiExtraExport {

    private final MpiProperties properties;
    private final RmiSerialFilter filter;

    public CollectiveEndpointExport(MpiProperties properties, RmiTransportProperties transport) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        Objects.requireNonNull(transport, "transport must not be null");
        this.filter = filterFor(properties, transport.serialFilter());
    }

    /** The filter of a node's CollectiveEndpoint for these settings. */
    public static RmiSerialFilter filterFor(MpiProperties properties, RmiTransportProperties.SerialFilter limits) {
        Objects.requireNonNull(properties, "properties must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        long maxArray = properties.maxPayloadElements();
        long maxBytes = Math.addExact(Math.multiplyExact(maxArray, (long) Double.BYTES), limits.maxBytes());
        return RmiSerialFilter.allowing(limits, maxBytes, maxArray, List.of(CallContext.class.getName()),
                Set.of(double[].class));
    }

    /**
     * The node's current {@link CollectiveEndpointLogic}, the exported object itself (no stub), to
     * read what the node received; empty while the node's RMI service is not running (never started,
     * stopped or crashed) or exports no CollectiveEndpoint.
     */
    public static Optional<CollectiveEndpointLogic> current(ClusterNode node) {
        Objects.requireNonNull(node, "node must not be null");
        return RmiNodeService.find(node).flatMap(service -> {
            try {
                return Optional.of((CollectiveEndpointLogic) service.localExport(CollectiveEndpoint.BOUND_NAME));
            } catch (IllegalStateException | IllegalArgumentException notThere) {
                return Optional.empty();
            }
        });
    }

    @Override
    public String boundName() {
        return CollectiveEndpoint.BOUND_NAME;
    }

    @Override
    public Class<? extends Remote> remoteType() {
        return CollectiveEndpoint.class;
    }

    @Override
    public Remote newEndpoint(ClusterNode node) {
        return CollectiveEndpointLogic.forNode(node, properties);
    }

    @Override
    public ObjectInputFilter filter() {
        return filter;
    }
}
