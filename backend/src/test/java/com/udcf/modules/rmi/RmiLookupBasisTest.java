package com.udcf.modules.rmi;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.events.ClusterEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.ref.Reference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code lookupNanos} contains (E1c, A4): RMI's distributed-GC "dirty" call for the stub a
 * lookup returns happens INSIDE {@code registry.lookup}, before any method is called.
 *
 * <p>JDK 21 source: {@code RegistryImpl_Stub.lookup} ends with {@code ref.done(call)}, which runs
 * {@code StreamRemoteCall.releaseInputStream}, then {@code ConnectionInputStream.registerRefs},
 * then {@code DGCClient.EndpointEntry.registerRefs}, which calls {@code makeDirtyCall} (so
 * {@code dgc.dirty}) synchronously on the calling thread when the returned object is not yet
 * referenced in this JVM. This test shows the effect on the wire: after a bare lookup, and before
 * any method call, the node has accepted a second connection (the dirty call travels on the stub's
 * own channel, which uses the node's socket factory, not the lookup's), and RMI's client-side DGC
 * thread for that endpoint exists.</p>
 *
 * <p>Test-only port 28791 (RMI base 28790; Jai's sub-range 28750-28799).</p>
 */
@Timeout(60)
class RmiLookupBasisTest {

    private ClusterEventBus bus;
    private Cluster cluster;

    @BeforeEach
    void setUp() {
        bus = RmiTestSupport.bus();
        cluster = RmiTestSupport.cluster(28790, 2, bus);
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    @Test
    @DisplayName("a bare lookup makes two connections to the node, the registry request and the DGC dirty call, before any method call")
    void dirtyCallHappensInsideLookup() throws Exception {
        RmiNodeService service = RmiTestSupport.start(cluster, 1);
        RemoteEndpointLookup lookup = RmiTestSupport.remote(cluster);
        assertThat(service.socketFactory().openAcceptedConnections()).isZero();
        assertThat(RmiTestSupport.renewCleanThreads(service)).isZero();

        NodeEndpoint stub = lookup.lookup(1);

        // No method has been called on the stub yet.
        assertThat(service.socketFactory().openAcceptedConnections())
                .as("connections the node accepted during lookup(): registry + DGC dirty")
                .isEqualTo(2);
        assertThat(RmiTestSupport.renewCleanThreads(service))
                .as("RMI's client-side DGC thread for the stub's endpoint, created by registerRefs inside lookup()")
                .isEqualTo(1);
        Reference.reachabilityFence(stub);
    }

    @Test
    @DisplayName("while an earlier stub for the same exported endpoint is alive, a second lookup makes no new dirty call")
    void noDirtyCallWhileAStubIsAlive() throws Exception {
        RmiNodeService service = RmiTestSupport.start(cluster, 1);
        RemoteEndpointLookup lookup = RmiTestSupport.remote(cluster);
        NodeEndpoint first = lookup.lookup(1);
        int afterFirst = service.socketFactory().openAcceptedConnections();

        NodeEndpoint second = lookup.lookup(1);

        assertThat(service.socketFactory().openAcceptedConnections())
                .as("the registry connection is reused from the pool and no dirty call is needed")
                .isEqualTo(afterFirst);
        Reference.reachabilityFence(first);
        Reference.reachabilityFence(second);
    }
}
