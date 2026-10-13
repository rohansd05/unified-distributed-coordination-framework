package com.udcf.modules.rmi;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.events.ClusterEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ObjectInputFilter;
import java.rmi.NotBoundException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The E9b extension seam of {@link RmiNodeService}: an extra export on the node's own registry,
 * port and socket factory, with a test-only {@link Probe} (so rmi tests never depend on mpi).
 *
 * <p>Test ports (Track C, E9b range 28500-28599): RMI base 28560 (28561-28563); the other five bases
 * 28505-28545 are never bound (only the rmi service starts).</p>
 */
class RmiNodeServiceExtrasTest {

    static final int BASE = 28560;

    /** A minimal remote interface for the seam's tests. */
    public interface Probe extends Remote {
        String BOUND_NAME = "Probe";

        int nodeId() throws RemoteException;
    }

    static final class ProbeImpl implements Probe {
        private final int id;

        ProbeImpl(int id) {
            this.id = id;
        }

        @Override
        public int nodeId() {
            return id;
        }
    }

    /** An extra export of a {@link Probe}; counts how many objects it made. */
    static final class ProbeExport implements RmiExtraExport {
        private final String name;
        private final Function<ClusterNode, Remote> factory;
        final AtomicInteger made = new AtomicInteger();

        ProbeExport() {
            this(Probe.BOUND_NAME, node -> new ProbeImpl(node.id()));
        }

        ProbeExport(String name, Function<ClusterNode, Remote> factory) {
            this.name = name;
            this.factory = factory;
        }

        @Override
        public String boundName() {
            return name;
        }

        @Override
        public Class<? extends Remote> remoteType() {
            return Probe.class;
        }

        @Override
        public Remote newEndpoint(ClusterNode node) {
            made.incrementAndGet();
            return factory.apply(node);
        }

        @Override
        public ObjectInputFilter filter() {
            return RmiSerialFilter.from(RmiTestSupport.TRANSPORT.serialFilter());
        }
    }

    private ClusterEventBus bus;
    private Cluster cluster;
    private RemoteEndpointLookup remote;

    @BeforeEach
    void setUp() {
        bus = RmiTestSupport.bus();
        cluster = new Cluster(new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
                new ClusterProperties.Ports(BASE, 28505, 28515, 28525, 28535, 28545)), bus);
        remote = RemoteEndpointLookup.of(cluster, RmiTestSupport.PROPERTIES, RmiTestSupport.TRANSPORT);
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    private RmiNodeService service(int nodeId, RmiExtraExport... extras) {
        return new RmiNodeService(cluster.node(nodeId), RmiTestSupport.PROPERTIES, RmiTestSupport.TRANSPORT,
                () -> NodeEndpointLogic.forNode(cluster.node(nodeId), RmiTestSupport.PROPERTIES, System::nanoTime),
                List.of(extras));
    }

    private static void awaitPortFree(int port) {
        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> !RmiTestSupport.acceptThreadAlive(port) && RmiTestSupport.canBind(port));
    }

    @Test
    @DisplayName("an extra export is bound beside NodeEndpoint in the same registry, on the same port, and answers")
    void boundBesideNodeEndpoint() throws Exception {
        RmiNodeService service = service(1, new ProbeExport());
        try {
            service.start();
            RegistrySnapshot snapshot = remote.snapshot(1);
            assertThat(snapshot.registryPort()).isEqualTo(BASE + 1);
            assertThat(snapshot.names()).extracting(RegistrySnapshot.BoundName::name)
                    .containsExactly(NodeEndpoint.BOUND_NAME, Probe.BOUND_NAME);
            assertThat(snapshot.find(Probe.BOUND_NAME).orElseThrow().stubClassName()).contains("$Proxy");
            assertThat(remote.lookup(1, Probe.BOUND_NAME, Probe.class).nodeId()).isEqualTo(1);
            assertThat(remote.lookup(1).ping(new CallContext(1, 0)).nodeId()).isEqualTo(1);
        } finally {
            service.stop();
        }
        awaitPortFree(BASE + 1);
    }

    @Test
    @DisplayName("every start and every recovery exports a fresh object, published only while running")
    void freshObjectEveryStart() {
        ProbeExport export = new ProbeExport();
        RmiNodeService service = service(1, export);
        try {
            service.start();
            Remote first = service.localExport(Probe.BOUND_NAME);
            service.start();   // already running: nothing changes
            assertThat(service.localExport(Probe.BOUND_NAME)).isSameAs(first);
            service.crash();
            assertThatThrownBy(() -> service.localExport(Probe.BOUND_NAME)).hasMessage("node 1 is not running");
            service.recover();
            Remote second = service.localExport(Probe.BOUND_NAME);
            service.stop();
            service.start();
            Remote third = service.localExport(Probe.BOUND_NAME);

            assertThat(export.made).hasValue(3);
            assertThat(second).isNotSameAs(first);
            assertThat(third).isNotSameAs(second);
        } finally {
            service.stop();
        }
    }

    @Test
    @DisplayName("a crash unexports the extra with everything else: the port is free and a lookup is refused")
    void crashUnexportsTheExtra() throws Exception {
        RmiNodeService service = service(1, new ProbeExport());
        service.start();
        assertThat(remote.lookup(1, Probe.BOUND_NAME, Probe.class).nodeId()).isEqualTo(1);

        service.crash();

        assertThat(service.isRunning()).isFalse();
        awaitPortFree(BASE + 1);
        List<RemoteFailureKind> seen = RmiTestSupport.drainUntilRefused(() -> remote.lookup(1, Probe.BOUND_NAME, Probe.class));
        assertThat(seen).last().isEqualTo(RemoteFailureKind.CONNECTION_REFUSED);
    }

    @Test
    @DisplayName("an extra that fails, or has the wrong type, rolls back the whole start: nothing running, the port free")
    void failedExtraRollsBack() {
        AtomicInteger attempts = new AtomicInteger();
        ProbeExport flaky = new ProbeExport(Probe.BOUND_NAME, node -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("cannot build the probe");
            }
            return new ProbeImpl(node.id());
        });
        RmiNodeService service = service(2, flaky);
        assertThatThrownBy(service::start).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Node 2 cannot start RMI on port " + (BASE + 2) + ": cannot build the probe");
        assertThat(service.isRunning()).isFalse();
        awaitPortFree(BASE + 2);

        service.start();   // the port was really released: the same service starts now
        assertThat(service.isRunning()).isTrue();
        service.stop();
        awaitPortFree(BASE + 2);

        RmiNodeService wrongType = service(3, new ProbeExport(Probe.BOUND_NAME, node -> new Remote() { }));
        assertThatThrownBy(wrongType::start).hasMessageContaining("not a " + Probe.class.getName());
        assertThat(wrongType.isRunning()).isFalse();
        awaitPortFree(BASE + 3);
    }

    @Test
    @DisplayName("localExport: the object while running; 'not running' while down; an unknown name is rejected")
    void localExportRules() {
        RmiNodeService service = service(1, new ProbeExport());
        assertThatThrownBy(() -> service.localExport(Probe.BOUND_NAME)).hasMessage("node 1 is not running");
        assertThatThrownBy(() -> service.localExport("Nothing")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("node 1 exports nothing under Nothing");
        try {
            service.start();
            assertThat(service.localExport(Probe.BOUND_NAME)).isInstanceOf(ProbeImpl.class);
        } finally {
            service.stop();
        }
        assertThatThrownBy(() -> service.localExport(Probe.BOUND_NAME)).hasMessage("node 1 is not running");
    }

    @Test
    @DisplayName("every extra needs its own bound name, never NodeEndpoint's, never blank")
    void boundNamesAreUnique() {
        assertThatThrownBy(() -> service(1, new ProbeExport(NodeEndpoint.BOUND_NAME, n -> new ProbeImpl(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(1, new ProbeExport(), new ProbeExport()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(1, new ProbeExport(" ", n -> new ProbeImpl(1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the named lookup returns a typed stub, rejects the wrong type and reports a missing name; lookup(id) is unchanged")
    void namedLookup() throws Exception {
        RmiNodeService service = service(1, new ProbeExport());
        try {
            service.start();
            assertThat(remote.lookup(1, Probe.BOUND_NAME, Probe.class)).isNotInstanceOf(ProbeImpl.class);
            assertThatThrownBy(() -> remote.lookup(1, Probe.BOUND_NAME, NodeEndpoint.class))
                    .isInstanceOf(IllegalStateException.class).hasMessageEndingWith("not a NodeEndpoint");
            assertThatThrownBy(() -> remote.lookup(1, "Missing", Probe.class)).isInstanceOf(NotBoundException.class);
            assertThat(remote.lookup(1)).isInstanceOf(NodeEndpoint.class);
        } finally {
            service.stop();
        }
    }
}
