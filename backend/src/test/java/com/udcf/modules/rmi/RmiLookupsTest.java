package com.udcf.modules.rmi;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.UnknownNodeException;
import com.udcf.core.events.ClusterEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Proxy;
import java.rmi.RemoteException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The REMOTE and LOCAL lookups and the registry snapshot, on real 127.0.0.1 sockets on a
 * standalone three-node cluster.
 *
 * <p>Test-only ports 28841 to 28843 (RMI base 28840; see RmiTestSupport).</p>
 */
@Timeout(60)
class RmiLookupsTest {

    private static final int BASE = 28840;
    private ClusterEventBus bus;
    private Cluster cluster;

    @BeforeEach
    void setUp() {
        bus = RmiTestSupport.bus();
        cluster = RmiTestSupport.cluster(BASE, 3, bus);
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    @Test
    @DisplayName("the lookups report their paths")
    void paths() {
        assertThat(RmiTestSupport.remote(cluster).path()).isEqualTo(CallPath.REMOTE);
        assertThat(new LocalEndpointLookup(cluster).path()).isEqualTo(CallPath.LOCAL);
    }

    @Test
    @DisplayName("the remote lookup caches nothing: each lookup asks the registry, so a crash and a recovery show at once")
    void remoteLookupIsNotCached() throws Exception {
        RmiTestSupport.start(cluster, 1);
        RemoteEndpointLookup remote = RmiTestSupport.remote(cluster);

        NodeEndpoint first = remote.lookup(1);
        NodeEndpoint second = remote.lookup(1);
        assertThat(second).isNotSameAs(first);

        cluster.crash(1);
        Throwable down = catchThrowable(() -> remote.lookup(1));
        assertThat(down).isInstanceOf(RemoteException.class);
        // Refused, or lost if RMI reused the pooled registry connection inside its ~5 ms window.
        assertThat(RemoteFailureClassifier.classify(down, CallStage.LOOKUP)).isIn(RmiTestSupport.NODE_GONE);
        // Empty both channels' pools (registry, and endpoint: the stubs' DGC calls used it).
        RmiTestSupport.drainUntilRefused(() -> remote.lookup(1));
        RmiTestSupport.drainUntilRefused(() -> first.ping(new CallContext(2, 0)));

        cluster.recover(1);
        assertThat(remote.lookup(1).ping(new CallContext(2, 0)).nodeId()).isEqualTo(1);
    }

    @Test
    @DisplayName("a crashed node fails at the lookup stage: refused, timed to failure, no lookup time")
    void crashShowsAtLookupStage() {
        RmiTestSupport.start(cluster, 2);
        cluster.crash(2);

        RmiCallResult result = RmiTestSupport.invoker(cluster, 1).invoke(RmiTestSupport.remote(cluster), 2, RmiCall.ping());

        assertThat(result.outcome()).isEqualTo(CallOutcome.REMOTE_FAILURE);
        assertThat(result.failureStage()).isEqualTo(CallStage.LOOKUP);
        assertThat(result.failureKind()).isEqualTo(RemoteFailureKind.CONNECTION_REFUSED);
        assertThat(result.failureClass()).isEqualTo("java.rmi.ConnectException");
        assertThat(result.failureMessage()).startsWith("Connection refused to host: 127.0.0.1;");
        assertThat(result.lookupNanos()).isNull();
        assertThat(result.timeToFailureNanos()).isNotNull();
    }

    @Test
    @DisplayName("the snapshot lists the registry over the network: the name and the stub's proxy class")
    void snapshot() throws Exception {
        RmiTestSupport.start(cluster, 1);
        RemoteEndpointLookup remote = RmiTestSupport.remote(cluster);

        RegistrySnapshot snapshot = remote.snapshot(1);

        assertThat(snapshot.nodeId()).isEqualTo(1);
        assertThat(snapshot.registryPort()).isEqualTo(BASE + 1);
        assertThat(snapshot.names()).hasSize(1);
        RegistrySnapshot.BoundName entry = snapshot.names().get(0);
        assertThat(entry.name()).isEqualTo(NodeEndpoint.BOUND_NAME);
        assertThat(entry.stubClassName()).isEqualTo(remote.lookup(1).getClass().getName());
        assertThat(Proxy.isProxyClass(remote.lookup(1).getClass())).isTrue();
    }

    @Test
    @DisplayName("the snapshot of a node that is down throws the RemoteException instead of an empty list")
    void snapshotOfADownNode() {
        assertThatThrownBy(() -> RmiTestSupport.remote(cluster).snapshot(3))
                .isInstanceOf(java.rmi.ConnectException.class)
                .hasMessageStartingWith("Connection refused to host: 127.0.0.1;");
    }

    @Test
    @DisplayName("the local lookup returns the exported object itself, not a stub")
    void localLookupReturnsTheObject() {
        RmiNodeService service = RmiTestSupport.start(cluster, 1);

        NodeEndpoint local = new LocalEndpointLookup(cluster).lookup(1);

        assertThat(local).isSameAs(service.localEndpoint()).isInstanceOf(NodeEndpointLogic.class);
        assertThat(Proxy.isProxyClass(local.getClass())).isFalse();
    }

    @Test
    @DisplayName("the local lookup refuses a node whose service is not running, and an unknown node")
    void localLookupRefusesNodesNotRunning() {
        LocalEndpointLookup local = new LocalEndpointLookup(cluster);
        RmiTestSupport.start(cluster, 1);
        RmiNodeService two = RmiTestSupport.start(cluster, 2);
        cluster.crash(1);
        two.stop();

        assertThatThrownBy(() -> local.lookup(1)).isInstanceOf(IllegalStateException.class).hasMessage("node 1 is not running");
        assertThatThrownBy(() -> local.lookup(2)).isInstanceOf(IllegalStateException.class).hasMessage("node 2 is not running");
        assertThatThrownBy(() -> local.lookup(3)).isInstanceOf(IllegalStateException.class).hasMessage("node 3 is not running");
        assertThatThrownBy(() -> local.lookup(9)).isInstanceOf(UnknownNodeException.class);
    }
}
