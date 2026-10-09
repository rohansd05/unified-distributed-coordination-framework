package com.udcf.modules.election;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.core.failure.FailureDetector;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Bully, Ring and the shared failure detector across a five-node cluster on real 127.0.0.1
 * UDP sockets, with nodes crashed and recovered through the cluster (R10).
 *
 * <p>Test ports (Track B block, below 32768, outside the Linux and Windows ephemeral ranges):
 * cluster bases 26200 to 26250, so the election ports are 26221 to 26225. Nothing else binds.</p>
 */
class ElectionOverUdpTest {

    private static final ClusterProperties CLUSTER = new ClusterProperties(5,
            List.of(FAST, MEDIUM, SLOW, MEDIUM, FAST),
            new ClusterProperties.Ports(26200, 26210, 26220, 26230, 26240, 26250));
    private static final ElectionProperties PROPERTIES = new ElectionProperties(400, 1500, 150, 3000, 100, 1500, 10000);
    private static final Set<String> ELECTION_TYPES =
            Set.of("ELECTION", "OK", "COORDINATOR", "RING_ELECTION", "RING_COORDINATOR");

    private ClusterEventBus bus;
    private Cluster cluster;
    private List<ElectionNodeService> services;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), Clock.systemUTC());
        cluster = new Cluster(CLUSTER, bus);
        services = cluster.nodes().stream()
                .map(node -> ElectionNodeService.on(node, cluster, PROPERTIES, bus))
                .toList();
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    private ElectionNodeService service(int nodeId) {
        return services.get(nodeId - 1);
    }

    private static ConditionFactory within() {
        return await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50));
    }

    /** Every live node agrees on {@code expected} (crashed nodes are left out, as ConsensusChecker does). */
    private boolean consensusOn(int expected) {
        ConsensusResult result = ConsensusChecker.check(services);
        return result.reached() && Objects.equals(result.coordinatorId(), expected);
    }

    private List<ClusterEvent> events(String type) {
        return bus.query(ElectionNodeService.MODULE, null, 5000).stream()
                .filter(event -> event.type().equals(type))
                .toList();
    }

    private boolean everyNodeReported(String type, List<Integer> reporters, int peer) {
        List<ClusterEvent> found = events(type);
        return reporters.stream().allMatch(reporter -> found.stream()
                .anyMatch(e -> e.nodeId() == reporter && Objects.equals(e.peerId(), peer)));
    }

    @Test
    @DisplayName("Bully started from node 1 elects node 5, and every node agrees")
    void bullyElectsHighestLiveNodeAndConsensusHolds() {
        service(1).startBully();
        within().until(() -> consensusOn(5));
        assertThat(services).extracting(ElectionNodeService::coordinatorId).containsOnly(5);
    }

    @Test
    @DisplayName("with node 5 crashed, Bully elects node 4")
    void bullyWithHighestCrashedElectsNextHighest() {
        cluster.crash(5);
        service(1).startBully();
        within().until(() -> consensusOn(4));
        assertThat(service(5).coordinatorId()).isNull();
    }

    @Test
    @DisplayName("a recovered node 5 starts its own Bully election and takes leadership back")
    void recoveredHighestNodeReclaimsLeadership() {
        cluster.crash(5);
        service(1).startBully();
        within().until(() -> consensusOn(4));
        cluster.recover(5);
        within().until(() -> consensusOn(5));
        assertThat(services).extracting(ElectionNodeService::coordinatorId).containsOnly(5);
    }

    @Test
    @DisplayName("Ring from node 2 skips crashed node 3 and elects node 5")
    void ringElectsHighestAndSkipsCrashedSuccessor() {
        cluster.crash(3);
        service(2).startRing();
        within().until(() -> consensusOn(5));
        assertThat(events("DEAD_NODE_SKIPPED"))
                .anySatisfy(e -> {
                    assertThat(e.nodeId()).isEqualTo(2);
                    assertThat(e.peerId()).isEqualTo(3);
                    assertThat(e.data()).containsEntry("algorithm", "RING");
                });
    }

    @Test
    @DisplayName("every live node suspects a crashed node, then hears it again after recovery; no live node is suspected")
    void detectorOnEveryLiveNodeSuspectsCrashedPeerThenSeesItAlive() {
        List<Integer> live = List.of(1, 2, 3, 4);
        cluster.crash(5);
        within().until(() -> everyNodeReported(FailureDetector.PEER_SUSPECTED, live, 5));
        assertThat(live).allSatisfy(id -> assertThat(service(id).failureDetector().isSuspected(5)).isTrue());

        cluster.recover(5);
        within().until(() -> everyNodeReported(FailureDetector.PEER_ALIVE, live, 5));
        assertThat(live).allSatisfy(id -> assertThat(service(id).failureDetector().isSuspected(5)).isFalse());
        assertThat(events(FailureDetector.PEER_SUSPECTED)).extracting(ClusterEvent::peerId).containsOnly(5);
        assertThat(service(5).failureDetector().suspectedPeers()).isEmpty();
    }

    @Test
    @DisplayName("each election message type yields MESSAGE_SENT and MESSAGE_RECEIVED; heartbeats and probes yield none")
    void everyElectionMessageEmitsSentAndReceivedEvents() {
        cluster.crash(3);
        service(2).startRing();   // RING_ELECTION, RING_COORDINATOR and PROBE/PROBE_ACK (node 3 skipped)
        within().until(() -> consensusOn(5) && !events("DEAD_NODE_SKIPPED").isEmpty());
        service(1).startBully();  // ELECTION, OK, COORDINATOR
        within().until(() -> events(ElectionNodeService.MESSAGE_RECEIVED).stream()
                .anyMatch(e -> "COORDINATOR".equals(e.data().get("messageType"))));
        // Heartbeats really flowed between live nodes, and probes really ran (a token was forwarded).
        assertThat(service(1).failureDetector().peers())
                .filteredOn(p -> p.peerId() == 2).singleElement()
                .satisfies(p -> assertThat(p.millisSinceLastHeartbeat()).isNotNull());
        assertThat(events("TOKEN_FORWARDED")).isNotEmpty();

        List<ClusterEvent> sent = events(ElectionNodeService.MESSAGE_SENT);
        List<ClusterEvent> received = events(ElectionNodeService.MESSAGE_RECEIVED);
        assertThat(sent).extracting(e -> (String) e.data().get("messageType"))
                .containsAll(ELECTION_TYPES)
                .allMatch(ELECTION_TYPES::contains)
                .doesNotContain("HEARTBEAT", "PROBE", "PROBE_ACK");
        assertThat(received).extracting(e -> (String) e.data().get("messageType"))
                .containsAll(ELECTION_TYPES)
                .allMatch(ELECTION_TYPES::contains)
                .doesNotContain("HEARTBEAT", "PROBE", "PROBE_ACK");
        assertThat(received).allSatisfy(e ->
                assertThat(e.lamportTime()).isGreaterThan((Long) e.data().get("causedByTime")));
        assertThat(List.of(1, 2, 4, 5)).allSatisfy(id -> assertThat(sent).anyMatch(e -> e.nodeId() == id));
    }
}
