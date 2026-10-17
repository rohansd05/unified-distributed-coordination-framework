package com.udcf.integration;

import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.RoleProvider;
import com.udcf.core.events.ClusterEvent;
import com.udcf.modules.clocksync.ClockNodeService;
import com.udcf.modules.clocksync.ClockSyncModule;
import com.udcf.modules.clocksync.dto.SendLamportResult;
import com.udcf.modules.clocksync.dto.SendMessageCommand;
import com.udcf.modules.election.ElectionNodeService;
import com.udcf.modules.mapreduce.MapReduceModule;
import com.udcf.modules.mapreduce.MapReduceNodeService;
import com.udcf.modules.mapreduce.InputType;
import com.udcf.modules.mapreduce.dto.RunCommand;
import com.udcf.modules.mapreduce.dto.RunDto;
import com.udcf.modules.mapreduce.dto.RunState;
import com.udcf.modules.multithreading.MultithreadingProperties;
import com.udcf.modules.multithreading.RequestsClient;
import com.udcf.modules.multithreading.RequestsNodeService;
import com.udcf.modules.multithreading.WorkReply;
import com.udcf.modules.multithreading.WorkloadType;
import com.udcf.modules.replication.ConsistencyModel;
import com.udcf.modules.replication.ReplicationModule;
import com.udcf.modules.replication.ReplicationNodeService;
import com.udcf.modules.replication.dto.WriteCommand;
import com.udcf.modules.replication.dto.WriteDto;
import com.udcf.modules.rmi.RmiMethod;
import com.udcf.modules.rmi.RmiModule;
import com.udcf.modules.rmi.dto.CallCommand;
import com.udcf.modules.rmi.dto.RmiCallDto;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Link L4: Lamport time travels on inter-node messages, and the receiver's clock moves past the sender's
 * timestamp (rule 3). Each test first pushes the sender's clock far ahead of every other node, so a receiver
 * can only pass that value by merging the timestamp the message carried.
 *
 * <p>Covered: RMI (Experiment 1; Experiments 9 and 10 in {@link L6MatrixOverCollectivesOverRmiIntegrationTest}),
 * TCP requests (Experiments 2 and 6), TCP replication (Experiment 5; Experiment 8 uses the same service),
 * TCP MapReduce (Experiment 7), UDP clock (Experiment 3) and UDP election (Experiment 4).</p>
 *
 * <p>Ports 27200-27255: RMI 27200, clock 27210, election 27220, replication 27230, requests 27240,
 * MapReduce 27250.</p>
 */
@TestPropertySource(properties = {
        "udcf.cluster.ports.rmi-base=27200", "udcf.cluster.ports.clock-base=27210",
        "udcf.cluster.ports.election-base=27220", "udcf.cluster.ports.replication-base=27230",
        "udcf.cluster.ports.requests-base=27240", "udcf.cluster.ports.mapreduce-base=27250"})
class L4LamportOnMessagesIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private RmiModule rmi;

    @Autowired
    private ReplicationModule replication;

    @Autowired
    private MapReduceModule mapReduce;

    @Autowired
    private ClockSyncModule clockSync;

    @Autowired
    private RoleProvider roles;

    @Autowired
    private MultithreadingProperties multithreadingProperties;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void cleanSlate() {
        resetCluster();
    }

    /** Pushes node {@code nodeId}'s clock to at least {@code value} (a local event, as the rule allows). */
    private long pushClock(int nodeId, long value) {
        return cluster.node(nodeId).clock().update(value);
    }

    @Test
    @DisplayName("RMI (Experiment 1): the target serves past the caller's stamp, the caller merges the reply")
    void rmiCallCarriesTheCallersLamportTime() {
        long pushed = pushClock(1, 1000);

        RmiCallDto call = rmi.call(new CallCommand(1, 3, RmiMethod.PING, null, null));
        assertThat(call.senderLamportAfterReply()).as(call.toString()).isNotNull();

        ClusterEvent served = awaitEvent("rmi", "CALL_SERVED", e -> e.nodeId() == 3 && Integer.valueOf(1).equals(e.peerId()));
        long sentAt = number(served, "senderLamportAtSend");
        assertThat(sentAt).as("node 1 ticked past its pushed clock to send").isGreaterThan(pushed);
        assertThat(served.lamportTime()).as("node 3 replied past the stamp it received").isGreaterThan(sentAt);
        ClusterEvent succeeded = awaitEvent("rmi", "CALL_SUCCEEDED", e -> e.nodeId() == 1);
        assertThat(succeeded.lamportTime()).as("node 1 merged the reply").isGreaterThan(served.lamportTime());
        assertThat(cluster.node(3).clock().current()).isGreaterThanOrEqualTo(served.lamportTime());
    }

    @Test
    @DisplayName("TCP requests (Experiments 2 and 6): the serving node receives past the sender's stamp")
    void tcpRequestCarriesTheSendersLamportTime() throws Exception {
        long pushed = pushClock(1, 2000);
        ClusterNode target = cluster.node(2);
        RequestsNodeService.on(target, multithreadingProperties, meterRegistry, bus);

        WorkReply reply = new RequestsClient(10_000).send(target.ports().requests(), 1, cluster.node(1).clock(),
                WorkloadType.CPU_HASH, 1);

        ClusterEvent completed = awaitEvent(RequestsNodeService.MODULE, "REQUEST_COMPLETED",
                e -> e.nodeId() == 2 && Integer.valueOf(1).equals(e.peerId()));
        long received = number(completed, "receiveLamport");
        assertThat(received).as("node 2's receive merged node 1's stamp").isGreaterThan(pushed + 1);
        assertThat(reply.lamportTime()).isEqualTo(completed.lamportTime()).isGreaterThan(received);
        assertThat(cluster.node(1).clock().current()).as("node 1 merged the reply").isGreaterThan(reply.lamportTime());
    }

    @Test
    @DisplayName("TCP replication (Experiment 5): every backup receives past the primary's stamp")
    void tcpReplicationCarriesThePrimarysLamportTime() {
        int primary = replication.write(new WriteCommand("l4-first", "a", ConsistencyModel.SYNCHRONOUS)).primaryNodeId();
        long pushed = pushClock(primary, 5000);

        WriteDto write = replication.write(new WriteCommand("l4-second", "b", ConsistencyModel.SYNCHRONOUS));

        assertThat(write.primaryNodeId()).isEqualTo(primary);
        assertThat(write.backupNodeIds()).isNotEmpty();
        for (int backup : write.backupNodeIds()) {
            ClusterEvent applied = awaitEvent(ReplicationNodeService.MODULE, "REPLICA_APPLIED",
                    e -> e.nodeId() == backup && "l4-second".equals(e.data().get("key")));
            assertThat(number(applied, "receiveLamport")).as("backup %d", backup).isGreaterThan(pushed);
            assertThat(applied.lamportTime()).isGreaterThan(number(applied, "receiveLamport"));
        }
    }

    @Test
    @DisplayName("TCP MapReduce (Experiment 7): every task a worker receives is past the coordinator's stamp")
    void tcpMapReduceCarriesTheCoordinatorsLamportTime() {
        int coordinator = roles.ensureLeader().orElseThrow();
        long pushed = pushClock(coordinator, 20_000);

        String runId = mapReduce.startRun(new RunCommand("word-count", InputType.SAMPLE, null, null)).runId();
        RunDto run = await().atMost(DEADLINE).until(() -> mapReduce.run(runId), r -> r.state() != RunState.RUNNING);

        assertThat(run.state()).as(run.error()).isEqualTo(RunState.COMPLETED);
        assertThat(run.coordinatorId()).isEqualTo(coordinator);
        List<ClusterEvent> received = events(MapReduceNodeService.MODULE, "TASK_RECEIVED").stream()
                .filter(e -> e.nodeId() != coordinator).toList();
        assertThat(received).isNotEmpty();
        assertThat(received).allSatisfy(e -> assertThat(e.lamportTime())
                .as("TASK_RECEIVED on worker %d", e.nodeId()).isGreaterThan(pushed));
    }

    @Test
    @DisplayName("UDP clock (Experiment 3): the receiver's Lamport clock moves past the datagram's stamp")
    void udpClockMessageCarriesTheSendersLamportTime() {
        long pushed = pushClock(2, 3000);

        SendLamportResult sent = clockSync.sendLamportMessage(new SendMessageCommand(2, 4, "L4 probe"));

        assertThat(sent.lamportTime()).isGreaterThan(pushed);
        ClusterEvent received = awaitEvent(ClockNodeService.MODULE, "CLOCK_MESSAGE_RECEIVED",
                e -> e.nodeId() == 4 && number(e, "messageId") == sent.messageId());
        assertThat(number(received, "causedByTime")).isEqualTo(sent.lamportTime());
        assertThat(received.lamportTime()).isGreaterThan(sent.lamportTime());
    }

    @Test
    @DisplayName("UDP election (Experiment 4): every received election message is past its sender's stamp")
    void udpElectionMessagesCarryTheSendersLamportTime() {
        long pushed = pushClock(5, 7000);

        assertThat(roles.ensureLeader()).as("the reset cleared the leader; one election follows").contains(5);

        await().atMost(DEADLINE).until(() -> events(ElectionNodeService.MODULE, ElectionNodeService.MESSAGE_RECEIVED)
                .stream().anyMatch(e -> Integer.valueOf(5).equals(e.peerId())));
        List<ClusterEvent> received = events(ElectionNodeService.MODULE, ElectionNodeService.MESSAGE_RECEIVED);
        assertThat(received).allSatisfy(e -> assertThat(e.lamportTime()).isGreaterThan(number(e, "causedByTime")));
        assertThat(received.stream().filter(e -> Integer.valueOf(5).equals(e.peerId())))
                .as("messages from node 5 carried its pushed clock").isNotEmpty()
                .allSatisfy(e -> assertThat(e.lamportTime()).isGreaterThan(pushed));
    }
}
