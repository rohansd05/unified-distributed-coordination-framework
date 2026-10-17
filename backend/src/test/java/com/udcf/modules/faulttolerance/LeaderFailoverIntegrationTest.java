package com.udcf.modules.faulttolerance;

import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.LeaderElector;
import com.udcf.core.cluster.NodeRole;
import com.udcf.core.cluster.RoleProvider;
import com.udcf.core.events.ClusterEvent;
import com.udcf.modules.election.ElectionModule;
import com.udcf.modules.replication.ConsistencyModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Links L1 and L2 on real 127.0.0.1 sockets: Experiments 4 and 8 act on one FailureDetector suspect
 * signal; the leader elected by Experiment 4 is the node Experiment 8 promotes; a recovered leader
 * takes over only after it resynchronised (planned handover); PRIMARY and BACKUP follow; and no
 * leader means no appointment, never a fallback.
 *
 * <p>Test ports (Track B block, own range 26560-26595): cluster bases 26560 to 26590, so node k
 * binds election 26572 + k and replication 26578 + k. Timings: {@link FailoverFixture}.</p>
 */
class LeaderFailoverIntegrationTest {

    private static final ClusterProperties.Ports PORTS = new ClusterProperties.Ports(26560, 26566, 26572, 26578, 26584,
            26590);
    private static final long UPDATE_TIMEOUT_SECONDS = 15;

    private FailoverFixture fixture;
    private final AtomicBoolean streaming = new AtomicBoolean();
    private Thread stream;
    private final List<UpdateOutcome> outcomes = new CopyOnWriteArrayList<>();
    private final List<String> violations = new CopyOnWriteArrayList<>();
    private final AtomicInteger samples = new AtomicInteger();

    @AfterEach
    void tearDown() throws Exception {
        stopStream();
        if (fixture != null) {
            fixture.close();
        }
    }

    private FailoverFixture fixture(int size) {
        fixture = new FailoverFixture(size, FailoverCluster.NO_PROBE, FailoverFixture.REPLICATION.asyncDelayMillis(), PORTS);
        return fixture;
    }

    private List<Integer> nodesWith(NodeRole role) {
        return fixture.cluster.nodes().stream().filter(n -> n.hasRole(role)).map(ClusterNode::id).toList();
    }

    /**
     * A synchronous update stream on its own thread, one update at a time with a 50 ms pause (as a real
     * stream is paced, and so the 5000-event buffer keeps every event of the test), while a second thread
     * samples every 5 ms that at most one node acts as primary and holds PRIMARY.
     */
    private void startStream() {
        streaming.set(true);
        stream = Thread.ofPlatform().name("test-stream").start(() -> {
            int sequence = 1;
            while (streaming.get()) {
                try {
                    outcomes.add(fixture.failover.submit(SystemUpdate.numbered(sequence++), ConsistencyModel.SYNCHRONOUS)
                            .get(UPDATE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    violations.add("update " + (sequence - 1) + " failed: " + e);
                }
            }
        });
        Thread.ofPlatform().name("test-sampler").daemon().start(() -> {
            while (streaming.get()) {
                SplitBrainReport report = fixture.failover.splitBrain();
                List<Integer> primaries = nodesWith(NodeRole.PRIMARY);
                if (!report.passed() || report.livePrimaries().size() > 1 || primaries.size() > 1) {
                    violations.add(report + " primaries " + primaries);
                }
                samples.incrementAndGet();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    private void stopStream() throws InterruptedException {
        streaming.set(false);
        if (stream != null) {
            stream.join(TimeUnit.SECONDS.toMillis(UPDATE_TIMEOUT_SECONDS + 5));
            stream = null;
        }
    }

    private void awaitAcknowledged(int more) {
        int target = outcomes.size() + more;
        FailoverFixture.within().until(() -> outcomes.size() >= target);
    }

    private long sequenceOf(ClusterEvent event) {
        return event.sequence();
    }

    @Test
    @DisplayName("L2: one suspect event of the shared detector drives both the re-election and the failover; the callback never waits")
    void oneSuspectEventDrivesReElectionAndFailover() throws Exception {
        fixture(2);   // one survivor, so exactly one detector can suspect the crashed leader
        fixture.startAndAwaitPrimary(2);
        assertThat(fixture.roles.leaderId()).contains(2);
        // Hold the role provider's dispatcher: promotion cannot start, but detection and election must go on.
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fixture.roles.addLeaderListener(change -> {
            held.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        fixture.failover.crashPrimary();
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            FailoverFixture.within().until(() -> !fixture.leaderChangesTo(1).isEmpty());
            assertThat(fixture.events("PRIMARY_PROMOTED").stream().map(ClusterEvent::nodeId)).containsOnly(2);
            assertThat(fixture.failover.currentPrimary()).isEmpty();
        } finally {
            release.countDown();
        }
        fixture.awaitPrimary(1);

        List<ClusterEvent> suspicions = fixture.events("election", "PEER_SUSPECTED").stream()
                .filter(e -> Integer.valueOf(2).equals(e.peerId())).toList();
        assertThat(suspicions).as("one suspect event").singleElement()
                .satisfies(e -> assertThat(e.nodeId()).isEqualTo(1));
        ClusterEvent suspected = suspicions.get(0);
        ClusterEvent detected = fixture.events("FAILURE_DETECTED").get(0);
        assertThat(fixture.events("FAILURE_DETECTED")).hasSize(1);
        assertThat(detected.nodeId()).isEqualTo(1);
        assertThat(detected.data()).containsEntry("silentMillis", suspected.data().get("silentMillis"))
                .containsEntry("timeoutMillis", FailoverFixture.ELECTION.heartbeatTimeoutMillis());
        List<ClusterEvent> rounds = fixture.events("election", ElectionModule.ROUND_STARTED).stream()
                .filter(e -> "LEADER_FAILURE".equals(e.data().get("trigger"))).toList();
        assertThat(rounds).singleElement().satisfies(e -> assertThat(e.data()).containsEntry("initiatorNodeId", 1));
        ClusterEvent elected = fixture.leaderChangesTo(1).get(0);
        ClusterEvent promoted = fixture.events("PRIMARY_PROMOTED").stream()
                .filter(e -> Long.valueOf(3).equals(e.data().get("epoch"))).findFirst().orElseThrow();
        assertThat(promoted.nodeId()).isEqualTo(1);
        // Experiment 8's listener is registered first; the round still opens on the same detector call.
        assertThat(List.of(suspected, detected, rounds.get(0), elected, promoted)).extracting(this::sequenceOf)
                .isSorted();
    }

    @Test
    @DisplayName("leader crash with the update stream running: the new leader is promoted at a higher epoch, never two primaries, zero loss")
    void leaderCrashWithRunningStreamPromotesTheNewLeader() throws Exception {
        fixture(5);
        fixture.startAndAwaitPrimary(5);
        startStream();
        awaitAcknowledged(5);

        assertThat(fixture.failover.crashPrimary()).isEqualTo(5);
        fixture.awaitPrimary(4);
        awaitAcknowledged(5);
        stopStream();

        assertThat(fixture.roles.leaderId()).contains(4);
        FailoverRun run = fixture.failover.snapshot().latestRun();
        assertThat(run.newPrimaryId()).isEqualTo(4);
        assertThat(run.newEpoch()).isEqualTo(3L).isGreaterThan(run.oldEpoch());
        assertThat(run.outcome()).isEqualTo(FailoverRun.Outcome.RESTORED);
        assertThat(run.electedAtNanos()).isGreaterThan(run.detectedAtNanos());
        assertThat(fixture.events("FAILURE_DETECTED")).hasSize(1);
        assertThat(fixture.events("election", ElectionModule.ROUND_STARTED).stream()
                .filter(e -> "LEADER_FAILURE".equals(e.data().get("trigger")))).hasSize(1);
        ClusterEvent detected = fixture.events("FAILURE_DETECTED").get(0);
        assertThat(fixture.events("election", "PEER_SUSPECTED")).anySatisfy(e -> {
            assertThat(e.nodeId()).isEqualTo(detected.nodeId());
            assertThat(e.peerId()).isEqualTo(5);
            assertThat(e.sequence()).isLessThan(detected.sequence());
        });
        ClusterEvent elected = fixture.leaderChangesTo(4).get(0);
        assertThat(fixture.events("PRIMARY_PROMOTED")).filteredOn(e -> e.nodeId() == 4).singleElement()
                .satisfies(e -> assertThat(e.sequence()).isGreaterThan(elected.sequence()));
        assertThat(outcomes).isNotEmpty().allSatisfy(o -> assertThat(o.accepted()).as("update %d", o.update().sequence())
                .isTrue());
        assertThat(fixture.failover.dataLoss().lost()).isZero();
        assertThat(violations).isEmpty();
        assertThat(samples.get()).isGreaterThan(10);
    }

    @Test
    @DisplayName("planned handover: the recovered leader demotes, resynchronises, then takes over at epoch 4 with every update")
    void recoveredLeaderTakesOverOnlyAfterResynchronising() throws Exception {
        fixture(5);
        fixture.startAndAwaitPrimary(5);
        startStream();
        awaitAcknowledged(3);
        fixture.failover.crashPrimary();
        fixture.awaitPrimary(4);
        awaitAcknowledged(3);

        fixture.cluster.recover(5);
        FailoverFixture.within().until(() -> Long.valueOf(4).equals(fixture.failover.snapshot().primaryEpoch())
                && fixture.failover.currentPrimary().equals(Optional.of(5)));
        awaitAcknowledged(3);
        stopStream();

        assertThat(fixture.roles.leaderId()).contains(5);
        ClusterEvent resynced = fixture.events("RESYNCHRONISED").stream().filter(e -> e.nodeId() == 5).findFirst()
                .orElseThrow();
        ClusterEvent handover = fixture.events("HANDOVER_STARTED").get(0);
        ClusterEvent promoted = fixture.events("PRIMARY_PROMOTED").stream()
                .filter(e -> Long.valueOf(4).equals(e.data().get("epoch"))).findFirst().orElseThrow();
        assertThat(fixture.events("HANDOVER_STARTED")).hasSize(1);
        assertThat(handover.nodeId()).isEqualTo(4);
        assertThat(handover.peerId()).isEqualTo(5);
        assertThat(promoted.nodeId()).isEqualTo(5);
        assertThat(promoted.data()).containsEntry("previousPrimaryId", 4);
        assertThat(promoted.data().get("caughtUpFrom")).asInstanceOf(InstanceOfAssertFactories.LIST).contains(4);
        assertThat(List.of(resynced, handover, promoted)).extracting(this::sequenceOf).isSorted();
        assertThat(outcomes).allSatisfy(o -> assertThat(o.accepted()).as("update %d", o.update().sequence()).isTrue());
        assertThat(fixture.failover.dataLoss().lost()).as("every acknowledged update is on node 5").isZero();
        assertThat(fixture.replication(4).isPrimary()).isFalse();
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("PRIMARY and BACKUP follow Experiment 8 through start, crash, promotion, handover and reset; a handover waits for the open run")
    void clusterRolesFollowExperiment8() throws Exception {
        fixture(3);
        fixture.startAndAwaitPrimary(3);
        FailoverFixture.within().until(() -> nodesWith(NodeRole.PRIMARY).equals(List.of(3))
                && nodesWith(NodeRole.BACKUP).equals(List.of(1, 2)));

        fixture.cluster.crash(3);
        assertThat(nodesWith(NodeRole.PRIMARY)).isEmpty();
        assertThat(fixture.events("cluster", "REPLICATION_ROLES_CHANGED")).last()
                .satisfies(e -> assertThat(e.data()).containsEntry("primaryId", null).containsEntry("previousPrimaryId", 3));
        fixture.awaitPrimary(2);
        FailoverFixture.within().until(() -> nodesWith(NodeRole.PRIMARY).equals(List.of(2))
                && nodesWith(NodeRole.BACKUP).equals(List.of(1)));

        // Node 3 recovers and leads again, but the failover run stays open until the first accepted update:
        // the handover waits for it, so the run is measured.
        fixture.cluster.recover(3);
        FailoverFixture.within().until(() -> fixture.roles.leaderId().equals(Optional.of(3))
                && fixture.service(3).rejoinState() == RejoinState.READY);
        org.awaitility.Awaitility.await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
                .until(() -> fixture.failover.currentPrimary().equals(Optional.of(2)));
        assertThat(fixture.failover.submit(SystemUpdate.numbered(1), ConsistencyModel.SYNCHRONOUS)
                .get(UPDATE_TIMEOUT_SECONDS, TimeUnit.SECONDS).accepted()).isTrue();
        fixture.awaitPrimary(3);
        FailoverFixture.within().until(() -> nodesWith(NodeRole.PRIMARY).equals(List.of(3))
                && nodesWith(NodeRole.BACKUP).equals(List.of(1, 2)));
        assertThat(nodesWith(NodeRole.LEADER)).containsExactly(3);

        fixture.failover.reset();
        assertThat(nodesWith(NodeRole.PRIMARY)).isEmpty();
        assertThat(nodesWith(NodeRole.BACKUP)).isEmpty();
    }

    @Test
    @DisplayName("no leader means no appointment (no fallback): start says so; the first leader to appear is appointed")
    void noLeaderMeansNoAppointment() {
        fixture(3);
        RoleProvider noElector = new RoleProvider(fixture.cluster, (LeaderElector) null);
        FailoverCluster failover = new FailoverCluster(fixture.cluster, noElector, FailoverFixture.PROPERTIES,
                FailoverFixture.ELECTION, FailoverFixture.REPLICATION, fixture.bus, new SimpleMeterRegistry());
        try {
            assertThat(failover.start()).isFalse();
            org.awaitility.Awaitility.await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
                    .until(() -> failover.currentPrimary().isEmpty());
            assertThat(fixture.events("PRIMARY_PROMOTED")).isEmpty();

            fixture.cluster.assignLeader(2);   // as the election module does once every live node agrees

            FailoverFixture.within().until(() -> failover.currentPrimary().equals(Optional.of(2)));
            assertThat(fixture.events("PRIMARY_PROMOTED")).singleElement()
                    .satisfies(e -> assertThat(e.data()).containsEntry("epoch", 2L));
        } finally {
            failover.close();
            noElector.close();
        }
    }
}
