package com.udcf.core.failure;

import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeCapacity;
import com.udcf.core.cluster.NodePorts;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The shared failure detector (link L2) as a pure class: a fake nano clock and direct
 * {@code tick()} calls, no threads and no sockets.
 *
 * <p>The node's ports (26801 to 26851, Track B block, below 32768) are never bound.</p>
 */
class FailureDetectorTest {

    private static final NodePorts PORTS = new NodePorts(26801, 26811, 26821, 26831, 26841, 26851);
    private static final FailureDetectorConfig CONFIG = new FailureDetectorConfig(700, 2500);
    private static final long MS = 1_000_000L;

    private ClusterEventBus bus;
    private ClusterNode node;
    private long nanos;
    private final List<Integer> sent = new ArrayList<>();
    private final List<String> heard = new ArrayList<>();
    private FailureDetector detector;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), Clock.systemUTC());
        node = new ClusterNode(1, NodeCapacity.FAST, PORTS, bus);
        nanos = 1_000 * MS;
        detector = new FailureDetector(node, List.of(1, 2, 3), CONFIG, "election", sent::add, bus, () -> nanos);
        detector.addListener(recorder(heard));
    }

    @AfterEach
    void tearDown() {
        bus.close();
    }

    private static FailureListener recorder(List<String> into) {
        return new FailureListener() {
            @Override
            public void onSuspected(int peerId, long silentMillis) {
                into.add("suspected " + peerId + " " + silentMillis);
            }

            @Override
            public void onAlive(int peerId, long silentMillis) {
                into.add("alive " + peerId + " " + silentMillis);
            }
        };
    }

    private void advance(long millis) {
        nanos += millis * MS;
    }

    private List<ClusterEvent> events() {
        return bus.query("election", 1, 100);
    }

    @Test
    @DisplayName("every tick sends one heartbeat to each peer, never to itself")
    void sendsHeartbeatToEveryPeerButNotSelf() {
        detector.start();
        detector.tick();
        detector.tick();
        assertThat(sent).containsExactly(2, 3, 2, 3);
        assertThat(detector.peerIds()).containsExactly(2, 3);
    }

    @Test
    @DisplayName("a peer silent for longer than the timeout is suspected exactly once")
    void suspectsSilentPeerOnceAfterTimeout() {
        detector.start();
        advance(1000);
        detector.onHeartbeat(3);
        advance(1501);
        detector.tick();
        assertThat(detector.isSuspected(2)).isTrue();
        assertThat(detector.isSuspected(3)).isFalse();
        advance(700);
        detector.tick();
        detector.tick();
        assertThat(heard).containsExactly("suspected 2 2501");
        assertThat(events()).extracting(ClusterEvent::type).containsExactly(FailureDetector.PEER_SUSPECTED);
        assertThat(detector.suspectedPeers()).containsExactly(2);
    }

    @Test
    @DisplayName("silence of exactly the timeout is not yet a suspicion")
    void doesNotSuspectAtExactlyTimeout() {
        detector.start();
        advance(2500);
        detector.tick();
        assertThat(detector.suspectedPeers()).isEmpty();
        advance(1);
        detector.tick();
        assertThat(detector.suspectedPeers()).containsExactlyInAnyOrder(2, 3);
    }

    @Test
    @DisplayName("a heartbeat from a suspected peer makes it alive and publishes PEER_ALIVE")
    void heartbeatFromSuspectedPeerPublishesAlive() {
        detector.start();
        advance(3000);
        detector.tick();
        advance(400);
        detector.onHeartbeat(2);
        detector.onHeartbeat(2);
        assertThat(detector.isSuspected(2)).isFalse();
        assertThat(heard).containsExactly("suspected 2 3000", "suspected 3 3000", "alive 2 3400");
        assertThat(events()).extracting(ClusterEvent::type).containsExactly(
                FailureDetector.PEER_SUSPECTED, FailureDetector.PEER_SUSPECTED, FailureDetector.PEER_ALIVE);
    }

    @Test
    @DisplayName("a peer never heard is timed from start() and reports null, not 0, as its last heartbeat")
    void neverHeardPeerUsesStartAsBaselineAndReportsNullLastHeard() {
        detector.start();
        advance(100);
        detector.onHeartbeat(3);
        advance(50);
        assertThat(detector.peers()).containsExactly(new PeerHealth(2, false, null), new PeerHealth(3, false, 50L));
    }

    @Test
    @DisplayName("a heartbeat from an unknown node or from itself changes nothing")
    void unknownPeerHeartbeatIgnored() {
        detector.start();
        detector.onHeartbeat(9);
        detector.onHeartbeat(1);
        assertThat(detector.peers()).extracting(PeerHealth::peerId).containsExactly(2, 3);
        assertThat(detector.peers()).extracting(PeerHealth::millisSinceLastHeartbeat).containsOnlyNulls();
    }

    @Test
    @DisplayName("stopped, it neither sends nor suspects; a restart gives every peer a fresh grace period")
    void stopIgnoresTicksAndRestartGivesFreshGracePeriod() {
        detector.tick();
        assertThat(sent).isEmpty();
        detector.start();
        advance(3000);
        detector.tick();
        assertThat(detector.suspectedPeers()).hasSize(2);
        detector.stop();
        sent.clear();
        detector.tick();
        detector.onHeartbeat(2);
        assertThat(sent).isEmpty();
        assertThat(detector.isActive()).isFalse();

        detector.start();
        assertThat(detector.suspectedPeers()).isEmpty();
        advance(2000);
        detector.tick();
        assertThat(detector.suspectedPeers()).isEmpty();
        assertThat(events()).hasSize(2);   // the restart itself published nothing
    }

    @Test
    @DisplayName("a heartbeat that fails to send does not stop the other peers or the timeout check")
    void senderFailureForOnePeerDoesNotStopOthersOrTimeoutCheck() {
        List<Integer> reached = new ArrayList<>();
        FailureDetector failing = new FailureDetector(node, List.of(1, 2, 3), CONFIG, "election", peer -> {
            if (peer == 2) {
                throw new IllegalStateException("socket closed");
            }
            reached.add(peer);
        }, bus, () -> nanos);
        failing.start();
        advance(2600);
        failing.tick();
        assertThat(reached).containsExactly(3);
        assertThat(failing.suspectedPeers()).containsExactlyInAnyOrder(2, 3);
    }

    @Test
    @DisplayName("a throwing listener does not stop the others")
    void throwingListenerDoesNotBlockOthers() {
        List<String> second = new ArrayList<>();
        FailureDetector fresh = new FailureDetector(node, List.of(2), CONFIG, "election", peer -> { }, bus, () -> nanos);
        fresh.addListener(new FailureListener() {
            @Override
            public void onSuspected(int peerId, long silentMillis) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void onAlive(int peerId, long silentMillis) {
                throw new IllegalStateException("boom");
            }
        });
        fresh.addListener(recorder(second));
        fresh.start();
        advance(2501);
        fresh.tick();
        fresh.onHeartbeat(2);
        assertThat(second).containsExactly("suspected 2 2501", "alive 2 2501");
    }

    @Test
    @DisplayName("a closed registration receives nothing")
    void closedRegistrationReceivesNothing() {
        List<String> late = new ArrayList<>();
        FailureDetector.Registration registration = detector.addListener(recorder(late));
        registration.close();
        registration.close();
        detector.start();
        advance(3000);
        detector.tick();
        assertThat(late).isEmpty();
        assertThat(heard).hasSize(2);
    }

    @Test
    @DisplayName("events carry the module, the node, the peer and a node-clock Lamport tick")
    void eventsUseModuleNodeClockAndPeer() {
        node.clock().update(40);
        detector.start();
        advance(2600);
        detector.tick();
        List<ClusterEvent> events = events();
        assertThat(events).hasSize(2);
        assertThat(events).extracting(ClusterEvent::module).containsOnly("election");
        assertThat(events).extracting(ClusterEvent::nodeId).containsOnly(1);
        assertThat(events).extracting(ClusterEvent::peerId).containsExactlyInAnyOrder(2, 3);
        assertThat(events).extracting(ClusterEvent::lamportTime).containsExactlyInAnyOrder(42L, 43L);
        assertThat(events.get(0).data()).containsEntry("silentMillis", 2600L).containsEntry("timeoutMillis", 2500L);
        assertThat(node.clock().current()).isEqualTo(43);
    }

    @Test
    @DisplayName("reset gives a fresh view (no suspicion, nothing heard, grace from now) and keeps active as it was")
    void resetGivesFreshViewWithoutChangingActive() {
        detector.start();
        detector.onHeartbeat(3);
        advance(3000);
        detector.tick();
        assertThat(detector.suspectedPeers()).containsExactlyInAnyOrder(2, 3);
        detector.reset();
        assertThat(detector.isActive()).isTrue();
        assertThat(detector.peers()).containsExactly(new PeerHealth(2, false, null), new PeerHealth(3, false, null));
        advance(2500);
        detector.tick();
        assertThat(detector.suspectedPeers()).isEmpty();
        assertThat(events()).hasSize(2);   // the reset itself published nothing

        detector.stop();
        detector.reset();
        assertThat(detector.isActive()).isFalse();
    }

    @Test
    @DisplayName("the config needs a positive interval and a timeout above it")
    void configRejectsNonPositiveOrTimeoutNotAboveInterval() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FailureDetectorConfig(0, 2500));
        assertThatIllegalArgumentException().isThrownBy(() -> new FailureDetectorConfig(700, 700));
        assertThatIllegalArgumentException().isThrownBy(() -> new FailureDetectorConfig(700, 100));
        assertThat(new FailureDetectorConfig(700, 701).timeoutMillis()).isEqualTo(701);
        assertThatIllegalArgumentException().isThrownBy(() -> new FailureDetector(node, List.of(2), CONFIG, " ",
                peer -> { }, bus, () -> nanos));
    }
}
