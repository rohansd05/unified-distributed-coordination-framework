package com.udcf.integration;

import com.udcf.core.cluster.ClusterNode;
import com.udcf.modules.matrix.MatrixEndpoint;
import com.udcf.modules.matrix.MatrixModule;
import com.udcf.modules.matrix.dto.MatrixPhaseDto;
import com.udcf.modules.matrix.dto.MatrixRunCommand;
import com.udcf.modules.matrix.dto.MatrixRunDto;
import com.udcf.modules.mpi.CollectiveEndpoint;
import com.udcf.modules.mpi.CollectiveOperation;
import com.udcf.modules.mpi.CollectiveOutcome;
import com.udcf.modules.mpi.TransferStatus;
import com.udcf.modules.mpi.dto.TransferDto;
import com.udcf.modules.rmi.RmiModule;
import com.udcf.modules.rmi.RmiNodeService;
import com.udcf.modules.rmi.dto.BoundNameDto;
import com.udcf.modules.rmi.dto.RegistryDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Link L6: an Experiment 10 run moves A and B with Experiment 9's scatter and broadcast collectives, which
 * travel as Experiment 1 RMI calls to each node's registry; the blocks of C come back as RMI fetches from each
 * node's matrix endpoint (a separate bound name, decided at the E10a review; not Experiment 9's gather).
 *
 * <p>Ports 27400-27455: RMI 27400 (TCP 27401-27405), clock 27410, election 27420, replication 27430,
 * requests 27440, MapReduce 27450.</p>
 */
@TestPropertySource(properties = {
        "udcf.cluster.ports.rmi-base=27400", "udcf.cluster.ports.clock-base=27410",
        "udcf.cluster.ports.election-base=27420", "udcf.cluster.ports.replication-base=27430",
        "udcf.cluster.ports.requests-base=27440", "udcf.cluster.ports.mapreduce-base=27450"})
class L6MatrixOverCollectivesOverRmiIntegrationTest extends IntegrationTestSupport {

    private static final int ROOT = 1;

    @Autowired
    private MatrixModule matrix;

    @Autowired
    private RmiModule rmi;

    @Test
    @DisplayName("a matrix run scatters and broadcasts with Experiment 9 collectives over Experiment 1 RMI, Lamport on every call")
    void matrixRunUsesCollectivesOverRmi() {
        resetCluster();

        MatrixRunDto run = matrix.run(new MatrixRunCommand(40, 42L, ROOT));

        assertThat(run.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        assertThat(run.verification().passed()).isTrue();
        assertThat(run.rankNodeIds()).containsExactlyInAnyOrder(1, 2, 3, 4, 5);
        assertThat(run.scatter().operation()).isEqualTo(CollectiveOperation.SCATTER);
        assertThat(run.broadcast().operation()).isEqualTo(CollectiveOperation.BROADCAST);

        List<TransferDto> remote = Stream.of(run.scatter(), run.broadcast()).map(MatrixPhaseDto::transfers)
                .flatMap(List::stream).filter(t -> t.status() != TransferStatus.LOCAL).toList();
        List<TransferDto> fetches = run.gather().stream().filter(t -> t.status() != TransferStatus.LOCAL).toList();
        assertThat(remote).as("4 remote ranks x (scatter + broadcast)").hasSize(8);
        assertThat(fetches).as("one fetch of C per remote rank").hasSize(4);
        assertThat(Stream.concat(remote.stream(), fetches.stream())).allSatisfy(t -> {
            assertThat(t.status()).isEqualTo(TransferStatus.DELIVERED);
            assertThat(t.requestBytes()).as("serialized for RMI").isPositive();
            assertThat(t.replyLamport()).as("rank %d received past the root's stamp", t.rank())
                    .isGreaterThan(t.senderLamportAtSend());
            assertThat(t.lamportAfterReply()).as("the root merged rank %d's reply", t.rank())
                    .isGreaterThan(t.replyLamport());
        });

        // Every node's Experiment 1 registry holds the Experiment 9 and Experiment 10 endpoints the run called.
        for (ClusterNode node : cluster.nodes()) {
            assertThat(node.runningServices()).as("node %d", node.id()).contains(RmiNodeService.NAME);
            RegistryDto registry = rmi.registry(node.id());
            assertThat(registry.reachable()).isTrue();
            assertThat(registry.names()).extracting(BoundNameDto::name)
                    .contains(CollectiveEndpoint.BOUND_NAME, MatrixEndpoint.BOUND_NAME);
        }
        assertThat(events("matrix", "MATRIX_RUN_FINISHED")).hasSize(1);
    }
}
