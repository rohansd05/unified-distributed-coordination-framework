package com.udcf.modules.mpi;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.LeaderElector;
import com.udcf.core.cluster.RoleProvider;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.modules.mpi.dto.CollectiveCommand;
import com.udcf.modules.mpi.dto.CollectiveDto;
import com.udcf.modules.mpi.dto.TransferDto;
import com.udcf.modules.rmi.CallContext;
import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.RmiModule;
import com.udcf.modules.rmi.RmiTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A node crashing during a collective, over real RMI on 127.0.0.1: three nodes, root 1. The
 * transport is the real {@link RmiCollectiveTransport}, wrapped so that node 3 is crashed (through
 * the cluster, closing its real sockets) at the moment the root starts its transfer to node 2. Node
 * 3 was live at the start, so it is a rank; its transfer fails and the collective is PARTIAL, never
 * a hang.
 *
 * <p>Test ports TCP 28691 to 28693 (RMI base 28690). The other bases (production values) are never
 * bound: no Spring context, so only the rmi service starts.</p>
 */
@Timeout(60)
class MpiCrashDuringCollectiveTest {

    private static final int RMI_BASE = 28690;

    private ClusterEventBus bus;
    private Cluster cluster;
    private RmiModule rmi;
    private MpiModule module;
    private RoleProvider roles;

    @BeforeEach
    void setUp() {
        bus = RmiTestSupport.bus();
        cluster = new Cluster(new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
                new ClusterProperties.Ports(RMI_BASE, 6000, 7000, 7100, 7200, 7300)), bus);
        rmi = new RmiModule(cluster, bus, RmiTestSupport.PROPERTIES, RmiTestSupport.TRANSPORT, new SimpleMeterRegistry(),
                Clock.systemUTC(), List.of(new CollectiveEndpointExport(MpiTestResults.PROPERTIES, RmiTestSupport.TRANSPORT)));
        CollectiveTransport real = RmiCollectiveTransport.of(rmi);
        CollectiveTransport crashingNode3 = new CollectiveTransport() {
            @Override
            public ReceiveReceipt deliver(int target, CallContext context, long id, int rank, double[] values)
                    throws TransferException {
                crashNode3When(target);
                return real.deliver(target, context, id, rank, values);
            }

            @Override
            public Contribution collect(int target, CallContext context, long id, int rank, int count, long seed)
                    throws TransferException {
                crashNode3When(target);
                return real.collect(target, context, id, rank, count, seed);
            }
        };
        roles = new RoleProvider(cluster, (LeaderElector) null);
        cluster.assignLeader(1);   // the root: node 1, the leader
        module = new MpiModule(cluster, bus, MpiTestResults.PROPERTIES, rmi, RmiTestSupport.PROPERTIES,
                RmiTestSupport.TRANSPORT, new SimpleMeterRegistry(), Clock.systemUTC(), crashingNode3, System::nanoTime,
                roles);
    }

    private void crashNode3When(int target) {
        if (target == 2 && cluster.node(3).isUp()) {
            cluster.crash(3);
        }
    }

    @AfterEach
    void tearDown() {
        module.close();
        roles.close();
        rmi.close();
        cluster.close();
        bus.close();
        for (int k = 1; k <= 3; k++) {
            int port = RMI_BASE + k;
            await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> !RmiTestSupport.acceptThreadAlive(port) && RmiTestSupport.canBind(port));
        }
    }

    @ParameterizedTest
    @EnumSource(CollectiveOperation.class)
    @DisplayName("node 3 crashes mid-run: PARTIAL, node 2 delivered and verified, node 3 FAILED (refused or lost), not skipped")
    void crashMidRun(CollectiveOperation operation) {
        CollectiveDto dto = module.run(new CollectiveCommand(operation, 1000, 42L, null));

        TransferDto two = dto.transfers().get(1);
        TransferDto three = dto.transfers().get(2);
        System.out.println("E9c outcome matrix | node 3 crashed mid-run, " + operation + " | " + dto.outcome() + " | "
                + three.failureStage() + " | " + three.failureKind() + " | " + three.failureClass() + " <- "
                + three.failureCauseClass() + " | " + three.timeToFailureNanos() + " ns");
        assertThat(cluster.node(3).isUp()).isFalse();
        assertThat(dto.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
        assertThat(dto.skippedNodeIds()).isEmpty();
        assertThat(dto.rankNodeIds()).containsExactly(1, 2, 3);
        assertThat(two.status()).isEqualTo(TransferStatus.DELIVERED);
        assertThat(two.verified()).isTrue();
        assertThat(three.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(three.failureStage()).isEqualTo(CallStage.LOOKUP);
        assertThat(three.failureKind()).isIn(RmiTestSupport.NODE_GONE);
        assertThat(three.timeToFailureNanos()).isPositive();
        assertThat(three.latencyNanos()).isNull();
        assertThat(dto.totalNanos()).isPositive();
        assertThat(dto.verification().passed()).isNull();
    }
}
