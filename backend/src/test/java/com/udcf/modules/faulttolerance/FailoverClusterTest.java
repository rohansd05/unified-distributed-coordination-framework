package com.udcf.modules.faulttolerance;

import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeRole;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.metrics.MetricNames;
import com.udcf.modules.election.ElectionNodeService;
import com.udcf.modules.replication.ConsistencyModel;
import com.udcf.modules.replication.DataItem;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Experiment 8 on a standalone five-node cluster with real 127.0.0.1 sockets (UDP heartbeats on
 * the election channel, TCP replication) and Experiment 4's election: the first appointment of the
 * elected leader, detection and promotion of the new leader, the stale-epoch fence, the old
 * primary's rejoin and planned handover, no split brain, data loss in both modes, the threading
 * rule, the bounds of a promotion, events and metrics. Bully elects the highest live node, so the
 * first primary is the highest node and a failover promotes the next highest. Ports and timings:
 * {@link FailoverFixture}.
 */
class FailoverClusterTest {

    private static final long UPDATE_TIMEOUT_SECONDS = 15;

    private FailoverFixture fixture;

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    private static UpdateOutcome await(java.util.concurrent.CompletableFuture<UpdateOutcome> future) throws Exception {
        return future.get(UPDATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private List<ClusterEvent> promotedAt(long epoch) {
        return fixture.events("PRIMARY_PROMOTED").stream().filter(e -> Long.valueOf(epoch).equals(e.data().get("epoch")))
                .toList();
    }

    private List<Integer> nodesWith(NodeRole role) {
        return fixture.cluster.nodes().stream().filter(n -> n.hasRole(role)).map(ClusterNode::id).toList();
    }

    @Test
    @DisplayName("a client retry window shorter than the worst-case failover fails construction")
    void tooSmallWindowFailsConstruction() {
        fixture = new FailoverFixture(3);
        FaultToleranceProperties small = new FaultToleranceProperties(new FaultToleranceProperties.Client(2, 100),
                FailoverFixture.PROPERTIES.roleQuery(), FailoverFixture.PROPERTIES.promotion(), 10);
        assertThatIllegalStateException().isThrownBy(() -> new FailoverCluster(fixture.cluster, fixture.roles, small,
                        FailoverFixture.ELECTION, FailoverFixture.REPLICATION, fixture.bus, new SimpleMeterRegistry()))
                .withMessageContaining("200 ms").withMessageContaining("3450 ms");
    }

    @Test
    @DisplayName("start elects a leader (a requested Bully round) and appoints it at epoch 2, with the term record on every node")
    void startAppointsFirstPrimaryAtEpochTwo() {
        fixture = new FailoverFixture(5);
        fixture.startAndAwaitPrimary(5);

        assertThat(fixture.roles.leaderId()).contains(5);
        assertThat(fixture.events("election", "ELECTION_ROUND_STARTED"))
                .anySatisfy(e -> assertThat(e.data()).containsEntry("trigger", "REQUESTED").containsEntry("initiatorNodeId", 1));
        for (int id = 1; id <= 5; id++) {
            assertThat(fixture.replication(id).epoch()).as("node %d", id).isEqualTo(2);
            assertThat(fixture.service(id).believedPrimaryId()).as("node %d", id).isEqualTo(5);
        }
        assertThat(promotedAt(2)).singleElement().satisfies(event -> {
            assertThat(event.nodeId()).isEqualTo(5);
            assertThat(event.data()).containsEntry("previousPrimaryId", null);
        });
        FailoverFixture.within().until(() -> nodesWith(NodeRole.BACKUP).equals(List.of(1, 2, 3, 4)));
        assertThat(nodesWith(NodeRole.PRIMARY)).containsExactly(5);
        assertThat(fixture.failover.snapshot().primaryEpoch()).isEqualTo(2L);
        assertThat(fixture.failover.acknowledged()).isEmpty();   // the term record is not a client write
        assertThat(fixture.failover.splitBrain().passed()).isTrue();
        fixture.assertEventsWellFormed();
    }

    @Test
    @DisplayName("ElectionNodeService.on is idempotent: Experiment 8 and a second caller get the same service")
    void electionServiceOnIsIdempotent() {
        fixture = new FailoverFixture(3);
        fixture.startAndAwaitPrimary(3);
        ClusterNode node = fixture.cluster.node(2);

        ElectionNodeService first = ElectionNodeService.on(node, fixture.cluster, FailoverFixture.ELECTION, fixture.bus);
        ElectionNodeService second = ElectionNodeService.on(node, fixture.cluster, FailoverFixture.ELECTION, fixture.bus);

        assertThat(first).isSameAs(second).isSameAs(ElectionNodeService.find(node).orElseThrow());
        assertThat(first.failureDetector().isActive()).isTrue();
    }

    @Test
    @DisplayName("a primary crash is detected once, the new leader is promoted once at epoch 3; the first write restores the service")
    void crashDetectedOncePromotedOnceAndMeasured() throws Exception {
        fixture = new FailoverFixture(5);
        fixture.startAndAwaitPrimary(5);
        assertThat(await(fixture.failover.submit(SystemUpdate.numbered(1), ConsistencyModel.SYNCHRONOUS)).accepted())
                .isTrue();

        assertThat(fixture.failover.crashPrimary()).isEqualTo(5);
        fixture.awaitPrimary(4);
        UpdateOutcome after = await(fixture.failover.submit(SystemUpdate.numbered(2), ConsistencyModel.SYNCHRONOUS));
        // Every live detector suspects node 5; let them all report before counting.
        FailoverFixture.within().until(() -> fixture.events("election", "PEER_SUSPECTED").stream()
                .filter(e -> Integer.valueOf(5).equals(e.peerId())).count() >= 4);

        assertThat(after.accepted()).isTrue();
        assertThat(after.nodeId()).isEqualTo(4);
        assertThat(after.epoch()).isEqualTo(3L);
        assertThat(fixture.roles.leaderId()).contains(4);
        assertThat(fixture.events("FAILURE_DETECTED")).hasSize(1);
        assertThat(promotedAt(3)).singleElement().satisfies(e -> assertThat(e.nodeId()).isEqualTo(4));
        assertThat(fixture.events("SERVICE_RESTORED")).hasSize(1);
        assertThat(fixture.events("PRIMARY_CRASHED")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("source", "ACTION"));
        FailoverRun run = fixture.failover.snapshot().latestRun();
        assertThat(run.crashSource()).isEqualTo(InstantSource.ACTION);
        assertThat(run.outcome()).isEqualTo(FailoverRun.Outcome.RESTORED);
        assertThat(run.electedAtNanos()).isGreaterThan(run.detectedAtNanos());
        FailoverMeasurements m = fixture.failover.measurements().orElseThrow();
        assertThat(m.detectionMillis()).isPositive();
        assertThat(m.failoverMillis()).isPositive();
        assertThat(m.serviceRestoredMillis()).isPositive();
        assertThat(m.outageMillis()).isGreaterThan(m.detectionMillis());
        assertThat(m.recoveryMillis()).isNull();
        SimpleMeterRegistry registry = fixture.registry;
        assertThat(registry.get(MetricNames.FAILURES_TOTAL).tag(MetricNames.NODE_ID, "5").counter().count()).isEqualTo(1);
        for (String interval : List.of("detection", "failover", "service_restored", "outage")) {
            Timer timer = registry.get(MetricNames.RECOVERY_DURATION).tag(MetricNames.NODE_ID, "5")
                    .tag(FaultToleranceMetrics.INTERVAL, interval).timer();
            assertThat(timer.count()).as(interval).isEqualTo(1);
        }
        assertThat(registry.find(MetricNames.RECOVERY_DURATION).tag(FaultToleranceMetrics.INTERVAL, "recovery").timer())
                .isNull();
        fixture.assertEventsWellFormed();
    }

    @Test
    @DisplayName("two nodes reporting a real crash at once, besides the detectors, promote exactly one node")
    void concurrentSuspicionsPromoteOnce() throws Exception {
        fixture = new FailoverFixture(5);
        fixture.startAndAwaitPrimary(5);
        fixture.cluster.crash(5);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> reporters = new ArrayList<>();
        for (int observer : List.of(2, 3)) {
            Thread reporter = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                fixture.failover.onSuspected(observer, 5, 700);   // as the detector listener reports it
            }, "test-reporter-" + observer);
            reporter.start();
            reporters.add(reporter);
        }
        go.countDown();
        for (Thread reporter : reporters) {
            reporter.join(5000);
        }

        fixture.awaitPrimary(4);
        FailoverFixture.within().until(() -> fixture.events("election", "PEER_SUSPECTED").stream()
                .filter(e -> Integer.valueOf(5).equals(e.peerId())).count() >= 4);
        assertThat(fixture.events("FAILURE_DETECTED")).hasSize(1);
        assertThat(promotedAt(3)).singleElement().satisfies(e -> assertThat(e.nodeId()).isEqualTo(4));
        assertThat(fixture.failover.snapshot().highestEpoch()).isEqualTo(3);
    }

    @Test
    @DisplayName("a false suspicion of a live primary is detected but promotes nobody: the election keeps the leader")
    void falseSuspicionPromotesNobody() {
        fixture = new FailoverFixture(5);
        fixture.startAndAwaitPrimary(5);

        fixture.failover.onSuspected(2, 5, 700);   // node 5 is alive and still sends heartbeats

        assertThat(fixture.events("FAILURE_DETECTED")).hasSize(1);
        long electionBound = FailoverFixture.ELECTION.okTimeoutMillis() + FailoverFixture.ELECTION.coordinatorTimeoutMillis();
        org.awaitility.Awaitility.await().during(Duration.ofMillis(electionBound + 500)).atMost(Duration.ofSeconds(10))
                .until(() -> fixture.failover.currentPrimary().equals(Optional.of(5)) && promotedAt(3).isEmpty());
        assertThat(fixture.roles.leaderId()).contains(5);
        assertThat(fixture.failover.splitBrain().passed()).isTrue();
    }

    @Test
    @DisplayName("a crash started elsewhere (Cluster page) is stamped OBSERVED")
    void crashFromElsewhereIsObserved() {
        fixture = new FailoverFixture(3);
        fixture.startAndAwaitPrimary(3);

        fixture.cluster.crash(3);

        assertThat(fixture.failover.snapshot().latestRun().crashSource()).isEqualTo(InstantSource.OBSERVED);
        assertThat(fixture.events("PRIMARY_CRASHED")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("source", "OBSERVED").containsEntry("epoch", 2L));
        fixture.awaitPrimary(2);
    }

    @Test
    @DisplayName("after a planned handover the old primary's delayed update at a stale epoch is refused, and the new primary holds it")
    void staleEpochUpdateRefusedByBackups() throws Exception {
        fixture = new FailoverFixture(5, FailoverCluster.NO_PROBE, 5000);
        fixture.cluster.crash(5);
        fixture.startAndAwaitPrimary(4);
        // Node 4 accepts an asynchronous update; its pushes wait for the simulated delay (5000 ms).
        UpdateOutcome inFlight = await(fixture.failover.submit(SystemUpdate.numbered(1), ConsistencyModel.ASYNCHRONOUS));
        assertThat(inFlight.nodeId()).isEqualTo(4);

        fixture.cluster.recover(5);   // the highest node wins Bully again: a planned handover to it
        fixture.awaitPrimary(5);

        FailoverFixture.within().until(() -> !fixture.events("STALE_EPOCH_REFUSED").isEmpty());
        assertThat(inFlight.result().replication().get(10, TimeUnit.SECONDS))
                .anySatisfy(o -> assertThat(o.result()).contains(com.udcf.modules.replication.ApplyResult.STALE_EPOCH));
        ClusterEvent refusal = fixture.events("STALE_EPOCH_REFUSED").get(0);
        assertThat(refusal.nodeId()).isEqualTo(4);
        assertThat(refusal.peerId()).isIn(1, 2, 3, 5);
        assertThat(refusal.data()).containsEntry("senderEpoch", 2L).containsEntry("backupEpoch", 3L);
        assertThat(fixture.events("replication", "REPLICA_STALE_EPOCH")).isNotEmpty();
        assertThat(fixture.events("HANDOVER_STARTED")).singleElement().satisfies(e -> {
            assertThat(e.nodeId()).isEqualTo(4);
            assertThat(e.peerId()).isEqualTo(5);
        });
        // The handover copied the fenced old primary's store, so the acknowledged update is not lost.
        assertThat(fixture.replication(5).get(SystemUpdate.numbered(1).key())).isPresent();
        assertThat(fixture.failover.dataLoss().lost()).isZero();
        fixture.assertEventsWellFormed();
    }

    @Test
    @DisplayName("a recovered old primary queries, demotes on the higher epoch, resynchronises, then takes over again as the leader")
    void recoveredOldPrimaryDemotesAndResyncs() throws Exception {
        fixture = new FailoverFixture(5);
        fixture.startAndAwaitPrimary(5);
        for (int seq = 1; seq <= 3; seq++) {
            assertThat(await(fixture.failover.submit(SystemUpdate.numbered(seq), ConsistencyModel.SYNCHRONOUS))
                    .accepted()).isTrue();
        }
        fixture.failover.crashPrimary();
        fixture.awaitPrimary(4);
        for (int seq = 4; seq <= 5; seq++) {
            assertThat(await(fixture.failover.submit(SystemUpdate.numbered(seq), ConsistencyModel.SYNCHRONOUS))
                    .nodeId()).isEqualTo(4);
        }

        fixture.cluster.recover(5);

        FailoverFixture.within().until(() -> fixture.events("RESYNCHRONISED").stream().anyMatch(e -> e.nodeId() == 5));
        ClusterEvent query = fixture.events("ROLE_QUERY").stream().filter(e -> e.nodeId() == 5).findFirst().orElseThrow();
        assertThat(query.data()).containsEntry("action", "DEMOTE_AND_RESYNC").containsEntry("epoch", 3L)
                .containsEntry("primaryId", 4).containsEntry("ownRole", "PRIMARY").containsEntry("ownEpoch", 2L);
        assertThat(fixture.events("OLD_PRIMARY_DEMOTED")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("previousEpoch", 2L).containsEntry("epoch", 3L));
        assertThat(fixture.events("RESYNCHRONISED")).anySatisfy(e -> {
            assertThat(e.nodeId()).isEqualTo(5);
            assertThat(e.peerId()).isEqualTo(4);
        });
        FailoverRun run = fixture.failover.snapshot().latestRun();
        assertThat(run.oldPrimaryRecoveredAtNanos()).isNotNull();
        assertThat(run.demotedAtNanos()).isNotNull();
        assertThat(run.resyncedAtNanos()).isNotNull();
        assertThat(fixture.failover.measurements().orElseThrow().recoveryMillis()).isPositive();
        assertThat(fixture.registry.get(MetricNames.RECOVERY_DURATION).tag(MetricNames.NODE_ID, "5")
                .tag(FaultToleranceMetrics.INTERVAL, "recovery").timer().count()).isEqualTo(1);

        // Node 5 won Bully again: once ready, it takes over at epoch 4 holding every update.
        fixture.awaitPrimary(5);
        assertThat(fixture.failover.snapshot().primaryEpoch()).isEqualTo(4L);
        SortedMap<String, DataItem> newPrimaryStore = fixture.replication(5).snapshot();
        for (int seq = 1; seq <= 5; seq++) {
            assertThat(newPrimaryStore).as("update %d", seq).containsKey(SystemUpdate.numbered(seq).key());
        }
        assertThat(fixture.replication(4).isPrimary()).isFalse();
        fixture.assertEventsWellFormed();
    }

    @Test
    @DisplayName("a recovered old primary that reaches nobody stays non-primary, says so, and invents no role")
    void noAnswerKeepsOldPrimaryNonPrimary() {
        fixture = new FailoverFixture(5);
        fixture.startAndAwaitPrimary(5);
        fixture.failover.crashPrimary();
        fixture.awaitPrimary(4);
        for (int id = 1; id <= 4; id++) {
            fixture.cluster.crash(id);
        }

        fixture.cluster.recover(5);

        int maxAttempts = FailoverFixture.PROPERTIES.roleQuery().maxAttempts();
        FailoverFixture.within().until(() -> fixture.events("ROLE_QUERY_FAILED").size() == maxAttempts);
        List<ClusterEvent> failures = fixture.events("ROLE_QUERY_FAILED");
        assertThat(failures).allSatisfy(e -> assertThat(e.nodeId()).isEqualTo(5));
        assertThat(failures.get(0).data()).containsEntry("attempt", 1).containsEntry("retryInMillis", 300L);
        assertThat(failures.get(maxAttempts - 1).data()).containsEntry("attempt", maxAttempts)
                .containsEntry("retryInMillis", null);
        FaultToleranceNodeService old = fixture.service(5);
        assertThat(old.rejoinState()).isEqualTo(RejoinState.WAITING_FOR_ANSWER);
        assertThat(old.wasPrimaryAtCrash()).isTrue();   // its role is unknown, not replaced by a guess
        assertThat(old.isServing()).isFalse();
        assertThat(fixture.replication(5).isPrimary()).isFalse();
        assertThat(fixture.events("ROLE_QUERY")).noneMatch(e -> e.nodeId() == 5);
        assertThat(fixture.failover.currentPrimary()).isEmpty();
        assertThat(fixture.roles.primaryId()).isEmpty();   // the lone node may lead, but it is not made primary
        fixture.assertEventsWellFormed();
    }

    @Test
    @DisplayName("no two primaries at any sampled instant through crash, promotion, recovery, demotion and handover")
    void neverTwoPrimaries() throws Exception {
        fixture = new FailoverFixture(5);
        List<SplitBrainReport> samples = new ArrayList<>();
        List<List<Integer>> primaryRoles = new ArrayList<>();
        fixture.failover.start();
        sampleUntil(samples, primaryRoles, () -> fixture.failover.currentPrimary().equals(Optional.of(5)));
        await(fixture.failover.submit(SystemUpdate.numbered(1), ConsistencyModel.SYNCHRONOUS));

        fixture.failover.crashPrimary();
        sampleUntil(samples, primaryRoles, () -> fixture.failover.currentPrimary().equals(Optional.of(4)));
        await(fixture.failover.submit(SystemUpdate.numbered(2), ConsistencyModel.SYNCHRONOUS));
        fixture.cluster.recover(5);
        sampleUntil(samples, primaryRoles, () -> fixture.failover.currentPrimary().equals(Optional.of(5)));

        assertThat(samples).hasSizeGreaterThan(10).allSatisfy(report -> {
            assertThat(report.passed()).as(report.toString()).isTrue();
            assertThat(report.livePrimaries()).hasSizeLessThanOrEqualTo(1);
        });
        assertThat(primaryRoles).allSatisfy(nodes -> assertThat(nodes).hasSizeLessThanOrEqualTo(1));
    }

    private void sampleUntil(List<SplitBrainReport> samples, List<List<Integer>> primaryRoles,
                             java.util.concurrent.Callable<Boolean> done) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(5))
                .until(() -> {
                    samples.add(fixture.failover.splitBrain());
                    primaryRoles.add(nodesWith(NodeRole.PRIMARY));
                    return done.call();
                });
    }

    @Test
    @DisplayName("synchronous mode: every update the client was told succeeded survives a primary crash")
    void synchronousModeLosesNothing() throws Exception {
        fixture = new FailoverFixture(5);
        fixture.startAndAwaitPrimary(5);
        List<UpdateOutcome> outcomes = new ArrayList<>();

        for (int seq = 1; seq <= 20; seq++) {
            if (seq == 8) {
                fixture.failover.crashPrimary();   // the next updates retry through the failover
            }
            outcomes.add(await(fixture.failover.submit(SystemUpdate.numbered(seq), ConsistencyModel.SYNCHRONOUS)));
        }

        assertThat(outcomes).allSatisfy(o -> assertThat(o.accepted()).as("update %d", o.update().sequence()).isTrue());
        assertThat(outcomes.get(19).nodeId()).isEqualTo(4);
        DataLossReport loss = fixture.failover.dataLoss();
        assertThat(loss.acknowledged()).isEqualTo(20);
        assertThat(loss.lost()).isZero();
        assertThat(loss.lostSynchronous()).isZero();
        assertThat(loss.simulated()).isFalse();
    }

    @Test
    @DisplayName("asynchronous mode: updates still waiting to be pushed when the primary crashes are reported lost, labelled simulated")
    void asynchronousModeReportsInFlightLoss() throws Exception {
        fixture = new FailoverFixture(5);
        fixture.startAndAwaitPrimary(5);
        List<UpdateOutcome> outcomes = new ArrayList<>();
        for (int seq = 1; seq <= 4; seq++) {
            outcomes.add(await(fixture.failover.submit(SystemUpdate.numbered(seq), ConsistencyModel.ASYNCHRONOUS)));
        }

        fixture.failover.crashPrimary();   // before the simulated delay ends: the pushes are dropped
        fixture.awaitPrimary(4);

        long reportedDelay = outcomes.get(0).result().simulatedDelayMillis();
        assertThat(reportedDelay).isEqualTo(FailoverFixture.REPLICATION.asyncDelayMillis());
        DataLossReport loss = fixture.failover.dataLoss();
        assertThat(loss.acknowledged()).isEqualTo(4);
        assertThat(loss.lost()).isEqualTo(4);
        assertThat(loss.lostAsynchronous()).isEqualTo(4);
        assertThat(loss.lostSynchronous()).isZero();
        assertThat(loss.simulated()).isTrue();
        assertThat(loss.simulatedDelayMillis()).isEqualTo(reportedDelay);
        assertThat(fixture.events("replication", "ASYNC_PUSHES_DROPPED")).isNotEmpty();
    }

    @Test
    @DisplayName("the detector callback only hands off: promotion runs on the new primary's worker while the election worker stays free")
    void detectorCallbackDoesNoBlockingWork() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> promotionThread = new AtomicReference<>();
        fixture = new FailoverFixture(5, nodeId -> {
            if (nodeId == 4) {
                promotionThread.set(Thread.currentThread().getName());
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        fixture.startAndAwaitPrimary(5);

        fixture.failover.crashPrimary();
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(promotionThread.get()).isEqualTo("udcf-faulttolerance-n4-worker");
            int observer = fixture.events("FAILURE_DETECTED").get(0).nodeId();
            int other = List.of(1, 2, 3).stream().filter(id -> id != observer).findFirst().orElseThrow();
            fixture.cluster.crash(other);
            // The observer's election worker (where its detector callback ran) still ticks and detects.
            FailoverFixture.within().until(() -> fixture.events("election", "PEER_SUSPECTED").stream()
                    .anyMatch(e -> e.nodeId() == observer && Integer.valueOf(other).equals(e.peerId())));
            assertThat(fixture.failover.currentPrimary()).isEmpty();   // still held
        } finally {
            release.countDown();
        }
        fixture.awaitPrimary(4);
    }

    @Test
    @DisplayName("the term record write with a silent dead backup delays the promotion by at most its bound")
    void termRecordWithDeadBackupIsBounded() throws Exception {
        fixture = new FailoverFixture(5);
        fixture.cluster.crash(1);   // node 1 never binds its replication port; a silent peer takes it
        try (SilentServer silent = new SilentServer(FailoverFixture.replicationPort(1))) {
            fixture.startAndAwaitPrimary(5);
            int acceptedAtStart = silent.accepted();

            fixture.failover.crashPrimary();
            fixture.awaitPrimary(4);

            ClusterEvent elected = fixture.leaderChangesTo(4).get(0);
            ClusterEvent promoted = promotedAt(3).get(0);
            long promotionMillis = Duration.between(elected.wallTime(), promoted.wallTime()).toMillis();
            long bound = FaultToleranceProperties.promotionAttemptBoundMillis(
                    FailoverFixture.PROPERTIES.promotion().catchUpTimeoutMillis(), FailoverFixture.REPLICATION.timeoutMillis());
            assertThat(silent.accepted()).isGreaterThan(acceptedAtStart);   // the term record did reach it
            assertThat(promotionMillis).isGreaterThanOrEqualTo(FailoverFixture.REPLICATION.timeoutMillis() - 20L)
                    .isLessThanOrEqualTo(bound + 500);
            assertThat(fixture.events("PROMOTION_FAILED")).isEmpty();
        }
    }

    @Test
    @DisplayName("catch-up from a silent live peer is skipped after its bound, with an event, and the promotion goes on")
    void catchUpWithSilentPeerIsBounded() throws Exception {
        fixture = new FailoverFixture(5);
        fixture.cluster.crash(1);   // node 1's replication port stays unused until the silent peer takes it
        try (SilentServer silent = new SilentServer(FailoverFixture.replicationPort(1))) {
            fixture.startAndAwaitPrimary(5);
            fixture.cluster.recover(1);   // it joins, but its replication service cannot bind: a silent live peer
            FailoverFixture.within().until(() -> !fixture.events("SERVICE_START_FAILED").isEmpty());
            assertThat(fixture.events("SERVICE_START_FAILED").get(0).data()).containsEntry("service", "replication");

            fixture.failover.crashPrimary();
            fixture.awaitPrimary(4);

            assertThat(fixture.events("CATCH_UP_SKIPPED")).singleElement().satisfies(e -> {
                assertThat(e.nodeId()).isEqualTo(4);
                assertThat(e.peerId()).isEqualTo(1);
                assertThat((String) e.data().get("reason")).contains("no answer within 250 ms");
            });
            ClusterEvent promoted = promotedAt(3).get(0);
            assertThat(promoted.data()).containsEntry("caughtUpFrom", List.of(2, 3)).containsEntry("skipped", List.of(1));
            ClusterEvent elected = fixture.leaderChangesTo(4).get(0);
            long promotionMillis = Duration.between(elected.wallTime(), promoted.wallTime()).toMillis();
            assertThat(promotionMillis).isLessThanOrEqualTo(FaultToleranceProperties.promotionAttemptBoundMillis(
                    FailoverFixture.PROPERTIES.promotion().catchUpTimeoutMillis(),
                    FailoverFixture.REPLICATION.timeoutMillis()) + 500);
        }
    }

    @Test
    @DisplayName("five nodes, three silent live peers and a dead old primary: each promotion phase costs one bound, not one per peer")
    void promotionWithSeveralSilentAndDeadPeersIsBounded() throws Exception {
        fixture = new FailoverFixture(5);
        for (int id = 1; id <= 3; id++) {
            fixture.cluster.crash(id);   // their replication ports stay unused until the silent peers take them
        }
        try (SilentServer s1 = new SilentServer(FailoverFixture.replicationPort(1));
             SilentServer s2 = new SilentServer(FailoverFixture.replicationPort(2));
             SilentServer s3 = new SilentServer(FailoverFixture.replicationPort(3))) {
            fixture.startAndAwaitPrimary(5);
            for (int id = 1; id <= 3; id++) {
                fixture.cluster.recover(id);   // up and ready, but their replication service cannot bind: silent
            }
            FailoverFixture.within().until(() -> fixture.events("SERVICE_START_FAILED").size() == 3);
            List<SilentServer> silent = List.of(s1, s2, s3);
            List<Integer> acceptedBefore = silent.stream().map(SilentServer::accepted).toList();

            fixture.failover.crashPrimary();   // node 5 is now a dead backup: its pushes are refused
            fixture.awaitPrimary(4);

            long catchUpTimeout = FailoverFixture.PROPERTIES.promotion().catchUpTimeoutMillis();   // 250
            long readTimeout = FailoverFixture.REPLICATION.timeoutMillis();                       // 300
            ClusterEvent elected = fixture.leaderChangesTo(4).get(0);
            List<ClusterEvent> skips = fixture.events("CATCH_UP_SKIPPED");
            ClusterEvent promoted = promotedAt(3).get(0);
            assertThat(skips).extracting(ClusterEvent::peerId).containsExactlyInAnyOrder(1, 2, 3);
            assertThat(promoted.data()).containsEntry("caughtUpFrom", List.of()).containsEntry("skipped", List.of(1, 2, 3));
            for (int i = 0; i < silent.size(); i++) {   // each silent peer got a catch-up and a term-record connection
                assertThat(silent.get(i).accepted() - acceptedBefore.get(i)).isGreaterThanOrEqualTo(2);
            }
            java.time.Instant lastSkip = skips.stream().map(ClusterEvent::wallTime).max(java.time.Instant::compareTo)
                    .orElseThrow();
            long catchUpPhase = Duration.between(elected.wallTime(), lastSkip).toMillis();
            long termRecordPhase = Duration.between(lastSkip, promoted.wallTime()).toMillis();
            long whole = Duration.between(elected.wallTime(), promoted.wallTime()).toMillis();
            // One overall deadline: three peers one after another would need 3 x 250 = 750 ms.
            assertThat(catchUpPhase).isGreaterThanOrEqualTo(catchUpTimeout - 20).isLessThan(3 * catchUpTimeout);
            // Pushes in parallel, each bounded by connect + read: the term-record bound is 2 x 300 = 600 ms;
            // three silent backups one after another would need at least 3 x 300 = 900 ms.
            assertThat(termRecordPhase).isGreaterThanOrEqualTo(readTimeout - 20).isLessThanOrEqualTo(2 * readTimeout);
            assertThat(whole).isLessThanOrEqualTo(
                    FaultToleranceProperties.promotionAttemptBoundMillis(catchUpTimeout, readTimeout) + 250);
            assertThat(fixture.events("PROMOTION_FAILED")).isEmpty();
        }
    }

    @Test
    @DisplayName("a node down when Experiment 8 started joins when it recovers, adopts the current epoch and becomes a backup")
    void nodeDownAtStartJoinsOnRecovery() {
        fixture = new FailoverFixture(5);
        fixture.cluster.crash(1);
        fixture.startAndAwaitPrimary(5);
        assertThat(FaultToleranceNodeService.find(fixture.cluster.node(1))).isEmpty();

        fixture.cluster.recover(1);

        FailoverFixture.within().until(() -> FaultToleranceNodeService.find(fixture.cluster.node(1))
                .map(s -> s.rejoinState() == RejoinState.READY).orElse(false));
        assertThat(fixture.events("ROLE_QUERY")).anySatisfy(e -> {
            assertThat(e.nodeId()).isEqualTo(1);
            assertThat(e.data()).containsEntry("action", "ADOPT_AND_RESYNC").containsEntry("epoch", 2L);
        });
        assertThat(fixture.replication(1).epoch()).isEqualTo(2);
        assertThat(fixture.service(1).believedPrimaryId()).isEqualTo(5);
        FailoverFixture.within().until(() -> nodesWith(NodeRole.BACKUP).contains(1));
        assertThat(fixture.roles.leaderId()).contains(5);
    }

    @Test
    @DisplayName("reset forgets runs, the ledger and the epoch authority, steps down the primary it served and clears the roles")
    void resetClearsState() throws Exception {
        fixture = new FailoverFixture(3);
        fixture.startAndAwaitPrimary(3);
        await(fixture.failover.submit(SystemUpdate.numbered(1), ConsistencyModel.SYNCHRONOUS));
        fixture.failover.crashPrimary();
        fixture.awaitPrimary(2);

        fixture.failover.reset();

        FailoverSnapshot snapshot = fixture.failover.snapshot();
        assertThat(snapshot.started()).isFalse();
        assertThat(snapshot.primaryId()).isNull();
        assertThat(snapshot.primaryEpoch()).isNull();
        assertThat(snapshot.runs()).isEmpty();
        assertThat(snapshot.acknowledgedUpdates()).isZero();
        assertThat(snapshot.highestEpoch()).isEqualTo(1);
        assertThat(fixture.replication(2).isPrimary()).isFalse();
        assertThat(fixture.failover.currentPrimary()).isEmpty();
        assertThat(nodesWith(NodeRole.PRIMARY)).isEmpty();
        assertThat(nodesWith(NodeRole.BACKUP)).isEmpty();
    }
}
