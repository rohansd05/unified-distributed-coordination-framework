package com.udcf.modules.mpi;

import com.udcf.core.clock.LamportClock;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.modules.rmi.CallContext;
import com.udcf.modules.rmi.RmiNodeService;
import com.udcf.modules.rmi.RmiSerialFilter;
import com.udcf.modules.rmi.RmiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ObjectInputFilter;
import java.io.ObjectInputFilter.Status;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The export bean alone: no socket. Its filter is checked with fake FilterInfo values, at both payload limits. */
class CollectiveEndpointExportTest {

    private record Info(Class<?> serialClass, long arrayLength, long depth, long references, long streamBytes)
            implements ObjectInputFilter.FilterInfo {
    }

    private static Status check(RmiSerialFilter filter, Class<?> type, long arrayLength) {
        return filter.checkInput(new Info(type, arrayLength, 1, 1, 10));
    }

    @Test
    @DisplayName("binds CollectiveEndpoint, a CollectiveEndpoint, under the one name constant")
    void nameAndType() {
        CollectiveEndpointExport export = new CollectiveEndpointExport(CollectiveRmiFixture.LOCAL, RmiTestSupport.TRANSPORT);
        assertThat(export.boundName()).isEqualTo(CollectiveEndpoint.BOUND_NAME).isEqualTo("CollectiveEndpoint");
        assertThat(export.remoteType()).isEqualTo(CollectiveEndpoint.class);
    }

    @Test
    @DisplayName("every call gives a fresh logic, with an empty store, on the node's own clock")
    void freshLogicOnTheNodesClock() {
        LamportClock shared = new LamportClock();
        ClusterNode node = mock(ClusterNode.class);
        when(node.id()).thenReturn(2);
        when(node.clock()).thenReturn(shared);
        CollectiveEndpointExport export = new CollectiveEndpointExport(CollectiveRmiFixture.LOCAL, RmiTestSupport.TRANSPORT);

        CollectiveEndpointLogic first = (CollectiveEndpointLogic) export.newEndpoint(node);
        first.receive(new CallContext(1, 5), 1, 0, new double[] {1});
        CollectiveEndpointLogic second = (CollectiveEndpointLogic) export.newEndpoint(node);

        assertThat(second).isNotSameAs(first);
        assertThat(second.nodeId()).isEqualTo(2);
        assertThat(second.storedIds()).isEmpty();
        assertThat(shared.current()).isEqualTo(7);   // the receive above ran on the node's clock
    }

    @Test
    @DisplayName("the filter pattern follows max-payload-elements: local 100000 and public 10000, bytes 8 per element plus E1b's max-bytes")
    void patternAtBothLimits() {
        assertThat(CollectiveEndpointExport.filterFor(CollectiveRmiFixture.LOCAL, RmiTestSupport.TRANSPORT.serialFilter()).pattern())
                .isEqualTo("maxdepth=5;maxrefs=20;maxbytes=816384;maxarray=100000;com.udcf.modules.rmi.CallContext;!*");
        assertThat(CollectiveEndpointExport.filterFor(CollectiveRmiFixture.PUBLIC, RmiTestSupport.TRANSPORT.serialFilter()).pattern())
                .isEqualTo("maxdepth=5;maxrefs=20;maxbytes=96384;maxarray=10000;com.udcf.modules.rmi.CallContext;!*");
    }

    @Test
    @DisplayName("the filter allows double[] up to the limit and CallContext, at both limits; everything else is rejected")
    void allowsOnlyWhatTheMethodsNeed() {
        for (MpiProperties properties : new MpiProperties[] {CollectiveRmiFixture.LOCAL, CollectiveRmiFixture.PUBLIC}) {
            RmiSerialFilter filter = CollectiveEndpointExport.filterFor(properties, RmiTestSupport.TRANSPORT.serialFilter());
            int max = properties.maxPayloadElements();
            assertThat(check(filter, double[].class, max)).as("%d doubles", max).isEqualTo(Status.ALLOWED);
            assertThat(check(filter, double[].class, max + 1L)).as("%d doubles", max + 1).isEqualTo(Status.REJECTED);
            assertThat(check(filter, CallContext.class, -1)).isEqualTo(Status.ALLOWED);
            for (Class<?> type : new Class<?>[] {long[].class, Object[].class, CallContext[].class, int[].class, String.class}) {
                assertThat(check(filter, type, type.isArray() ? 1 : -1)).as(type.getName()).isEqualTo(Status.REJECTED);
            }
        }
    }

    @Test
    @DisplayName("current(node) is empty when the node has no RMI service")
    void currentWithoutAService() {
        ClusterNode node = mock(ClusterNode.class);
        when(node.service(RmiNodeService.NAME)).thenReturn(Optional.empty());
        assertThat(CollectiveEndpointExport.current(node)).isEmpty();
    }
}
