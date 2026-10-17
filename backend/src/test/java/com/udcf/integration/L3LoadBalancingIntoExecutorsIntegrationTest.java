package com.udcf.integration;

import com.udcf.core.events.ClusterEvent;
import com.udcf.modules.loadbalancing.LoadBalancingModule;
import com.udcf.modules.loadbalancing.Strategy;
import com.udcf.modules.loadbalancing.dto.ActionState;
import com.udcf.modules.loadbalancing.dto.NodeResultDto;
import com.udcf.modules.loadbalancing.dto.PhaseReportDto;
import com.udcf.modules.loadbalancing.dto.RunCommand;
import com.udcf.modules.loadbalancing.dto.RunDto;
import com.udcf.modules.multithreading.RequestsNodeService;
import com.udcf.modules.multithreading.dto.ThreadPoolStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Link L3: Experiment 6's balancer dispatches over TCP into each target node's Experiment 2 executor.
 *
 * <p>Ports 27100-27155: RMI 27100, clock 27110, election 27120, replication 27130, requests 27140 (the
 * listeners this link uses, TCP 27141-27145), MapReduce 27150.</p>
 */
@TestPropertySource(properties = {
        "udcf.cluster.ports.rmi-base=27100", "udcf.cluster.ports.clock-base=27110",
        "udcf.cluster.ports.election-base=27120", "udcf.cluster.ports.replication-base=27130",
        "udcf.cluster.ports.requests-base=27140", "udcf.cluster.ports.mapreduce-base=27150"})
class L3LoadBalancingIntoExecutorsIntegrationTest extends IntegrationTestSupport {

    private static final int REQUESTS = 25;

    @Autowired
    private LoadBalancingModule loadBalancing;

    @Test
    @DisplayName("every request the balancer served ran on the target node's Experiment 2 executor, and nowhere else")
    void balancerDispatchesIntoExperimentTwoExecutors() {
        resetCluster();
        long[] completedBefore = new long[cluster.size() + 1];
        cluster.nodes().forEach(node -> completedBefore[node.id()] = completedTasks(node.id()));

        String runId = loadBalancing.startRun(new RunCommand(Strategy.ROUND_ROBIN, REQUESTS, 5, 5, null)).runId();
        RunDto run = await().atMost(DEADLINE).until(() -> loadBalancing.overview().latestRun(),
                latest -> latest.runId().equals(runId) && latest.state() != ActionState.RUNNING);

        assertThat(run.state()).as(run.error()).isEqualTo(ActionState.FINISHED);
        PhaseReportDto report = run.report();
        assertThat(report.served()).isEqualTo(REQUESTS);
        assertThat(report.failures()).isZero();
        assertThat(report.nodes().stream().mapToInt(NodeResultDto::requests).sum()).isEqualTo(REQUESTS);

        // Experiment 2 publishes REQUEST_COMPLETED once its reply is written, so it can trail the balancer.
        await().atMost(DEADLINE).until(() -> balancerRequests().size() == REQUESTS);
        for (NodeResultDto node : report.nodes()) {
            int k = node.nodeId();
            List<ClusterEvent> served = balancerRequests().stream().filter(e -> e.nodeId() == k).toList();
            assertThat(served).as("REQUEST_COMPLETED on node %d", k).hasSize(node.requests());
            assertThat(served).allSatisfy(e -> assertThat((String) e.data().get("threadName"))
                    .as("Experiment 2 worker thread of node %d", k).startsWith("udcf-worker-n" + k + "-"));
            // The node's ThreadPoolExecutor counted exactly the requests the balancer sent there.
            await().atMost(DEADLINE).until(() -> completedTasks(k) == completedBefore[k] + node.requests());
        }
        assertThat(report.nodes()).as("round robin over five live nodes reaches every node")
                .allSatisfy(node -> assertThat(node.requests()).isPositive());
    }

    /** Experiment 2's per-request events whose sender is the cluster-level balancer (sender id 0). */
    private List<ClusterEvent> balancerRequests() {
        return events(RequestsNodeService.MODULE, "REQUEST_COMPLETED").stream()
                .filter(e -> Integer.valueOf(0).equals(e.peerId())).toList();
    }

    /** Tasks the node's Experiment 2 executor has completed; 0 while its requests service has not started. */
    private long completedTasks(int nodeId) {
        return RequestsNodeService.find(cluster.node(nodeId)).flatMap(RequestsNodeService::snapshot)
                .map(ThreadPoolStats::completedTasks).orElse(0L);
    }
}
