package com.udcf.modules.faulttolerance;

import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.metrics.MetricNames;
import com.udcf.modules.election.ElectionNodeService;
import com.udcf.modules.replication.ConsistencyModel;
import com.udcf.modules.replication.DataItem;
import com.udcf.modules.replication.ReplicationNodeService;
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
 * the election channel, TCP replication): the first appointment, detection and promotion, the
 * stale-epoch fence, the old primary's rejoin, no split brain, data loss in both modes, the
 * threading rule, the bounds of a promotion, events and metrics. Ports and timings:
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

    @Test
    @DisplayName("a client retry window shorter than the worst-case failover fails construction")
    void tooSmallWindowFailsConstruction() {
        fixture = new FailoverFixture(3);
        FaultToleranceProperties small = new FaultToleranceProperties(new FaultToleranceProperties.Client(2, 100),
                FailoverFixture.PROPERTIES.roleQuery(), FailoverFixture.PROPERTIES.promotion(), 10);
        assertThatIllegalStateException().isThrownBy(() -> new FailoverCluster(fixture.cluster, small,
                        FailoverFixture.ELECTION, FailoverFixture.REPLICATION, fixture.bus, new SimpleMeterRegistry()))
                .withMessageContaining("200 ms").withMessageContaining("2400 ms");
    }

    @Test
    @DisplayName("start appoints the lowest live node at epoch 2 and writes the term record to every node")
    void startAppointsFirstPrimaryAtEpochTwo() {
        fixture = new FailoverFixture(5);
        fixture.startAndAwaitPrimary(1);

        for (int id = 1; id <= 5; id++) {
            assertThat(fixture.replication(id).epoch()).as("node %d", id).isEqualTo(2);
            assertThat(fixture.service(id).believedPrimaryId()).as("node %d", id).isEqualTo(1);
        }
        assertThat(promotedAt(2)).singleElement().satisfies(event -> {
            assertThat(event.nodeId()).isEqualTo(1);
            assertThat(event.data()).containsEntry("previousPrimaryId", null);
        });
        assertThat(fixture.failover.snapshot().primaryEpoch()).isEqualTo(2L);
        assertThat(fixture.failover.acknowledged()).isEmpty();   // the term record is not a client write
        assertThat(fixture.failover.splitBrain().passed()).isTrue();
        fixture.assertEventsWellFormed();
    }

    @Test
    @DisplayName("ElectionNodeService.on is idempotent: Experiment 8 and a second caller get the same service")
    void electionServiceOnIsIdempotent() {
        fixture = new FailoverFixture(3);
        fixture.startAndAwaitPrimary(1);
        ClusterNode node = fixture.cluster.node(2);

        ElectionNodeService first = ElectionNodeService.on(node, fixture.cluster, FailoverFixture.ELECTION, fixture.bus);
        ElectionNodeService second = ElectionNodeService.on(node, fixture.cluster, FailoverFixture.ELECTION, fixture.bus);

        assertThat(first).isSameAs(second).isSameAs(ElectionNodeService.find(node).orElseThrow());
        assertThat(first.failureDetector().isActive()).isTrue();
    }

    @Test
    @DisplayName("a primary crash is detected once and promotes once at epoch 3; the first write restores the service")
    void crashDetectedOncePromotedOnceAndMeasured() throws Exception {
        fixture = new FailoverFixture(5);
        fixture.startAndAwaitPrimary(1);
        assertThat(await(fixture.failover.submit(SystemUpdate.numbered(1), ConsistencyModel.SYNCHRONOUS)).accepted())
                .isTrue();

        assertThat(fixture.failover.crashPrimary()).isEqualTo(1);
        fixture.awaitPrimary(2);
        UpdateOutcome after = await(fixture.failover.submit(SystemUpdate.numbered(2), ConsistencyModel.SYNCHRONOUS));
        // Every live detector suspects node 1; let them all report before counting.
        FailoverFixture.within().until(() -> fixture.events("election", "PEER_SUSPECTED").stream()
                .filter(e -> Integer.valueOf(1).equals(e.peerId())).count() >= 4);

        assertThat(after.accepted()).isTrue();
        assertThat(after.nodeId()).isEqualTo(2);
        assertThat(after.epoch()).isEqualTo(3L);
        assertThat(fixture.events("FAILURE_DETECTED")).hasSize(1);
        assertThat(promotedAt(3)).singleElement().satisfies(e -> assertThat(e.nodeId()).isEqualTo(2));
        assertThat(fixture.events("SERVICE_RESTORED")).hasSize(1);
        assertThat(fixture.events("PRIMARY_CRASHED")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("source", "ACTION"));
        FailoverRun run = fixture.failover.snapshot().latestRun();
        assertThat(run.crashSource()).isEqualTo(InstantSource.ACTION);
        assertThat(run.outcome()).isEqualTo(FailoverRun.Outcome.RESTORED);
        FailoverMeasurements m = fixture.failover.measurements().orElseThrow();
        assertThat(m.detectionMillis()).isPositive();
        assertThat(m.failoverMillis()).isPositive();
        assertThat(m.serviceRestoredMillis()).isPositive();
        assertThat(m.outageMillis()).isGreaterThan(m.detectionMillis());
        assertThat(m.recoveryMillis()).isNull();
        SimpleMeterRegistry registry = fixture.registry;
        assertThat(registry.get(MetricNames.FAILURES_TOTAL).tag(MetricNames.NODE_ID, "1").counter().count()).isEqualTo(1);
        for (String interval : List.of("detection", "failover", "service_restored", "outage")) {
            Timer timer = registry.get(MetricNames.RECOVERY_DURATION).tag(MetricNames.NODE_ID, "1")
                    .tag(FaultToleranceMetrics.INTERVAL, interval).timer();
            assertThat(timer.count()).as(interval).isEqualTo(1);
        }
        assertThat(registry.find(MetricNames.RECOVERY_DURATION).tag(FaultToleranceMetrics.INTERVAL, "recovery").timer())
                .isNull();
        fixture.assertEventsWellFormed();
    }

    @Test
    @DisplayName("two nodes reporting the same failure at once promote exactly one node")
    void concurrentSuspicionsPromoteOnce() throws Exception {
        fixture = new FailoverFixture(5);
        fixture.startAndAwaitPrimary(1);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> reporters = new ArrayList<>();
        for (int observer : List.of(3, 4)) {
            Thread reporter = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                fixture.failover.onSuspected(observer, 1, 700);   // as the detector listener reports it
            }, "test-reporter-" + observer);
            reporter.start();
            reporters.add(reporter);
        }
        go.countDown();
        for (Thread reporter : reporters) {
            reporter.join(5000);
        }

        fixture.awaitPrimary(2);
        assertThat(fixture.events("FAILURE_DETECTED")).hasSize(1);
        assertThat(promotedAt(3)).hasSize(1);
        assertThat(fixture.failover.snapshot().highestEpoch()).isEqualTo(3);
        // Node 1 was alive (a simulated false suspicion): the term record at epoch 3 superseded it.
        FailoverFixture.within().until(() -> fixture.failover.splitBrain().passed());
    }

    @Test
    @DisplayName("a crash started elsewhere (Cluster page) is stamped OBSERVED")
    void crashFromElsewhereIsObserved() {
        fixture = new FailoverFixture(3);
        fixture.startAndAwaitPrimary(1);

        fixture.cluster.crash(1);

        assertThat(fixture.failover.snapshot().latestRun().crashSource()).isEqualTo(InstantSource.OBSERVED);
        assertThat(fixture.events("PRIMARY_CRASHED")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("source", "OBSERVED").containsEntry("epoch", 2L));
        fixture.awaitPrimary(2);
    }

    @Test
    @DisplayName("an old primary's update at a stale epoch is refused by the backups, and the refusal event fires")
    void staleEpochUpdateRefusedByBackups() throws Exception {
        fixture = new FailoverFixture(5);
        fixture.startAndAwaitPrimary(1);
        // Node 1 accepts an asynchronous update; its pushes wait for the simulated delay (400 ms).
        UpdateOutcome inFlight = await(fixture.failover.submit(SystemUpdate.numbered(1), ConsistencyModel.ASYNCHRONOUS));
        assertThat(inFlight.nodeId()).isEqualTo(1);

        fixture.failover.onSuspected(2, 1, 700);   // a false suspicion: node 1 is alive and still sends
        fixture.awaitPrimary(2);

        FailoverFixture.within().until(() -> !fixture.events("STALE_EPOCH_REFUSED").isEmpty());
        assertThat(inFlight.result().replication().get(5, TimeUnit.SECONDS))
                .anySatisfy(o -> assertThat(o.result()).contains(com.udcf.modules.replication.ApplyResult.STALE_EPOCH));
        ClusterEvent refusal = fixture.events("STALE_EPOCH_REFUSED").get(0);
        assertThat(refusal.nodeId()).isEqualTo(1);
        assertThat(refusal.peerId()).isIn(2, 3, 4, 5);
        assertThat(refusal.data()).containsEntry("senderEpoch", 2L).containsEntry("backupEpoch", 3L);
        assertThat(fixture.events("replication", "REPLICA_STALE_EPOCH")).isNotEmpty();
        assertThat(fixture.replication(3).get(SystemUpdate.numbered(1).key())).isEmpty();
        fixture.assertEventsWellFormed();
    }

    @Test
    @DisplayName("a recovered old primary queries, demotes on the higher epoch and resynchronises from the new primary")
    void recoveredOldPrimaryDemotesAndResyncs() throws Exception {
        fixture = new FailoverFixture(5);
        fixture.startAndAwaitPrimary(1);
        for (int seq = 1; seq <= 3; seq++) {
            assertThat(await(fixture.failover.submit(SystemUpdate.numbered(seq), ConsistencyModel.SYNCHRONOUS))
                    .accepted()).isTrue();
        }
        fixture.failover.crashPrimary();
        fixture.awaitPrimary(2);
        for (int seq = 4; seq <= 5; seq++) {
            assertThat(await(fixture.failover.submit(SystemUpdate.numbered(seq), ConsistencyModel.SYNCHRONOUS))
                    .nodeId()).isEqualTo(2);
        }

        fixture.cluster.recover(1);

        FailoverFixture.within().until(() -> fixture.service(1).rejoinState() == RejoinState.READY);
        ClusterEvent query = fixture.events("ROLE_QUERY").stream().filter(e -> e.nodeId() == 1).findFirst().orElseThrow();
        assertThat(query.data()).containsEntry("action", "DEMOTE_AND_RESYNC").containsEntry("epoch", 3L)
                .containsEntry("primaryId", 2).containsEntry("ownRole", "PRIMARY").containsEntry("ownEpoch", 2L);
        assertThat(fixture.events("OLD_PRIMARY_DEMOTED")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("previousEpoch", 2L).containsEntry("epoch", 3L));
        assertThat(fixture.events("RESYNCHRONISED")).anySatisfy(e -> {
            assertThat(e.nodeId()).isEqualTo(1);
            assertThat(e.peerId()).isEqualTo(2);
        });
        ReplicationNodeService old = fixture.replication(1);
        assertThat(old.isPrimary()).isFalse();
        assertThat(old.epoch()).isEqualTo(3);
        SortedMap<String, DataItem> newPrimaryStore = fixture.replication(2).snapshot();
        for (int seq = 1; seq <= 5; seq++) {
            String key = SystemUpdate.numbered(seq).key();
            assertThat(old.get(key)).as(key).isEqualTo(Optional.ofNullable(newPrimaryStore.get(key)));
        }
        FailoverRun run = fixture.failover.snapshot().latestRun();
        assertThat(run.oldPrimaryRecoveredAtNanos()).isNotNull();
        assertThat(run.demotedAtNanos()).isNotNull();
        assertThat(run.resyncedAtNanos()).isNotNull();
        assertThat(fixture.failover.measurements().orElseThrow().recoveryMillis()).isPositive();
        assertThat(fixture.registry.get(MetricNames.RECOVERY_DURATION).tag(MetricNames.NODE_ID, "1")
                .tag(FaultToleranceMetrics.INTERVAL, "recovery").timer().count()).isEqualTo(1);
        assertThat(fixture.failover.currentPrimary()).contains(2);
        fixture.assertEventsWellFormed();
    }

    @Test
    @DisplayName("a recovered old primary that reaches nobody stays non-primary, says so, and invents no role")
    void noAnswerKeepsOldPrimaryNonPrimary() {
        fixture = new FailoverFixture(5);
        fixture.startAndAwaitPrimary(1);
        fixture.failover.crashPrimary();
        fixture.awaitPrimary(2);
        for (int id = 2; id <= 5; id++) {
            fixture.cluster.crash(id);
        }

        fixture.cluster.recover(1);

        int maxAttempts = FailoverFixture.PROPERTIES.roleQuery().maxAttempts();
        FailoverFixture.within().until(() -> fixture.events("ROLE_QUERY_FAILED").size() == maxAttempts);
        List<ClusterEvent> failures = fixture.events("ROLE_QUERY_FAILED");
        assertThat(failures).allSatisfy(e -> assertThat(e.nodeId()).isEqualTo(1));
        assertThat(failures.get(0).data()).containsEntry("attempt", 1).containsEntry("retryInMillis", 300L);
        assertThat(failures.get(maxAttempts - 1).data()).containsEntry("attempt", maxAttempts)
                .containsEntry("retryInMillis", null);
        FaultToleranceNodeService old = fixture.service(1);
        assertThat(old.rejoinState()).isEqualTo(RejoinState.WAITING_FOR_ANSWER);
        assertThat(old.wasPrimaryAtCrash()).isTrue();   // its role is unknown, not replaced by a guess
        assertThat(old.isServing()).isFalse();
        assertThat(fixture.replication(1).isPrimary()).isFalse();
        assertThat(fixture.events("ROLE_QUERY")).noneMatch(e -> e.nodeId() == 1);
        assertThat(fixture.failover.currentPrimary()).isEmpty();
        fixture.assertEventsWellFormed();
    }

    @Test
    @DisplayName("no two primaries at any sampled instant through crash, promotion, recovery and demotion")
    void neverTwoPrimaries() throws Exception {
        fixture = new FailoverFixture(5);
        List<SplitBrainReport> samples = new ArrayList<>();
        fixture.failover.start();
        sampleUntil(samples, () -> fixture.failover.currentPrimary().equals(Optional.of(1)));
        await(fixture.failover.submit(SystemUpdate.numbered(1), ConsistencyModel.SYNCHRONOUS));

        fixture.failover.crashPrimary();
        sampleUntil(samples, () -> fixture.failover.currentPrimary().equals(Optional.of(2)));
        await(fixture.failover.submit(SystemUpdate.numbered(2), ConsistencyModel.SYNCHRONOUS));
        fixture.cluster.recover(1);
        sampleUntil(samples, () -> fixture.service(1).rejoinState() == RejoinState.READY);

        assertThat(samples).hasSizeGreaterThan(10).allSatisfy(report -> {
            assertThat(report.passed()).as(report.toString()).isTrue();
            assertThat(report.livePrimaries()).hasSizeLessThanOrEqualTo(1);
        });
    }

    private void sampleUntil(List<SplitBrainReport> samples, java.util.concurrent.Callable<Boolean> done) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(5))
                .until(() -> {
                    samples.add(fixture.failover.splitBrain());
                    return done.call();
                });
    }

    @Test
    @DisplayName("synchronous mode: every update the client was told succeeded survives a primary crash")
    void synchronousModeLosesNothing() throws Exception {
        fixture = new FailoverFixture(5);
        fixture.startAndAwaitPrimary(1);
        List<UpdateOutcome> outcomes = new ArrayList<>();

        for (int seq = 1; seq <= 20; seq++) {
            if (seq == 8) {
                fixture.failover.crashPrimary();   // the next updates retry through the failover
            }
            outcomes.add(await(fixture.failover.submit(SystemUpdate.numbered(seq), ConsistencyModel.SYNCHRONOUS)));
        }

        assertThat(outcomes).allSatisfy(o -> assertThat(o.accepted()).as("update %d", o.update().sequence()).isTrue());
        assertThat(outcomes.get(19).nodeId()).isEqualTo(2);
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
        fixture.startAndAwaitPrimary(1);
        List<UpdateOutcome> outcomes = new ArrayList<>();
        for (int seq = 1; seq <= 4; seq++) {
            outcomes.add(await(fixture.failover.submit(SystemUpdate.numbered(seq), ConsistencyModel.ASYNCHRONOUS)));
        }

        fixture.failover.crashPrimary();   // before the simulated delay ends: the pushes are dropped
        fixture.awaitPrimary(2);

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
            if (nodeId == 2) {
                promotionThread.set(Thread.currentThread().getName());
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        fixture.startAndAwaitPrimary(1);

        fixture.failover.crashPrimary();
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(promotionThread.get()).isEqualTo("udcf-faulttolerance-n2-worker");
            int observer = fixture.events("FAILURE_DETECTED").get(0).nodeId();
            int other = List.of(3, 4, 5).stream().filter(id -> id != observer).findFirst().orElseThrow();
            fixture.cluster.crash(other);
            // The observer's election worker (where its detector callback ran) still ticks and detects.
            FailoverFixture.within().until(() -> fixture.events("election", "PEER_SUSPECTED").stream()
                    .anyMatch(e -> e.nodeId() == observer && Integer.valueOf(other).equals(e.peerId())));
            assertThat(fixture.failover.currentPrimary()).isEmpty();   // still held
        } finally {
            release.countDown();
        }
        fixture.awaitPrimary(2);
    }

    @Test
    @DisplayName("the term record write with a silent dead backup delays the promotion by at most its bound")
    void termRecordWithDeadBackupIsBounded() throws Exception {
        fixture = new FailoverFixture(5);
        fixture.cluster.crash(5);   // node 5 never binds its replication port; a silent peer takes it
        try (SilentServer silent = new SilentServer(FailoverFixture.replicationPort(5))) {
            fixture.startAndAwaitPrimary(1);
            int acceptedAtStart = silent.accepted();

            fixture.failover.crashPrimary();
            fixture.awaitPrimary(2);

            ClusterEvent detected = fixture.events("FAILURE_DETECTED").get(0);
            ClusterEvent promoted = promotedAt(3).get(0);
            long promotionMillis = Duration.between(detected.wallTime(), promoted.wallTime()).toMillis();
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
        fixture.cluster.crash(5);   // node 5's replication port stays unused until the silent peer takes it
        try (SilentServer silent = new SilentServer(FailoverFixture.replicationPort(5))) {
            fixture.startAndAwaitPrimary(1);
            fixture.cluster.recover(5);   // it joins, but its replication service cannot bind: a silent live peer
            FailoverFixture.within().until(() -> !fixture.events("SERVICE_START_FAILED").isEmpty());
            assertThat(fixture.events("SERVICE_START_FAILED").get(0).data()).containsEntry("service", "replication");

            fixture.failover.crashPrimary();
            fixture.awaitPrimary(2);

            assertThat(fixture.events("CATCH_UP_SKIPPED")).singleElement().satisfies(e -> {
                assertThat(e.nodeId()).isEqualTo(2);
                assertThat(e.peerId()).isEqualTo(5);
                assertThat((String) e.data().get("reason")).contains("no answer within 250 ms");
            });
            ClusterEvent promoted = promotedAt(3).get(0);
            assertThat(promoted.data()).containsEntry("caughtUpFrom", List.of(3, 4)).containsEntry("skipped", List.of(5));
            ClusterEvent detected = fixture.events("FAILURE_DETECTED").get(0);
            long promotionMillis = Duration.between(detected.wallTime(), promoted.wallTime()).toMillis();
            assertThat(promotionMillis).isLessThanOrEqualTo(FaultToleranceProperties.promotionAttemptBoundMillis(
                    FailoverFixture.PROPERTIES.promotion().catchUpTimeoutMillis(),
                    FailoverFixture.REPLICATION.timeoutMillis()) + 500);
        }
    }

    @Test
    @DisplayName("five nodes, three silent live peers and a dead old primary: each promotion phase costs one bound, not one per peer")
    void promotionWithSeveralSilentAndDeadPeersIsBounded() throws Exception {
        fixture = new FailoverFixture(5);
        for (int id = 3; id <= 5; id++) {
            fixture.cluster.crash(id);   // their replication ports stay unused until the silent peers take them
        }
        try (SilentServer s3 = new SilentServer(FailoverFixture.replicationPort(3));
             SilentServer s4 = new SilentServer(FailoverFixture.replicationPort(4));
             SilentServer s5 = new SilentServer(FailoverFixture.replicationPort(5))) {
            fixture.startAndAwaitPrimary(1);
            for (int id = 3; id <= 5; id++) {
                fixture.cluster.recover(id);   // up and ready, but their replication service cannot bind: silent
            }
            FailoverFixture.within().until(() -> fixture.events("SERVICE_START_FAILED").size() == 3);
            List<SilentServer> silent = List.of(s3, s4, s5);
            List<Integer> acceptedBefore = silent.stream().map(SilentServer::accepted).toList();

            fixture.failover.crashPrimary();   // node 1 is now a dead backup: its pushes are refused
            fixture.awaitPrimary(2);

            long catchUpTimeout = FailoverFixture.PROPERTIES.promotion().catchUpTimeoutMillis();   // 250
            long readTimeout = FailoverFixture.REPLICATION.timeoutMillis();                       // 300
            ClusterEvent detected = fixture.events("FAILURE_DETECTED").get(0);
            List<ClusterEvent> skips = fixture.events("CATCH_UP_SKIPPED");
            ClusterEvent promoted = promotedAt(3).get(0);
            assertThat(skips).extracting(ClusterEvent::peerId).containsExactlyInAnyOrder(3, 4, 5);
            assertThat(promoted.data()).containsEntry("caughtUpFrom", List.of()).containsEntry("skipped", List.of(3, 4, 5));
            for (int i = 0; i < silent.size(); i++) {   // each silent peer got a catch-up and a term-record connection
                assertThat(silent.get(i).accepted() - acceptedBefore.get(i)).isGreaterThanOrEqualTo(2);
            }
            java.time.Instant lastSkip = skips.stream().map(ClusterEvent::wallTime).max(java.time.Instant::compareTo)
                    .orElseThrow();
            long catchUpPhase = Duration.between(detected.wallTime(), lastSkip).toMillis();
            long termRecordPhase = Duration.between(lastSkip, promoted.wallTime()).toMillis();
            long whole = Duration.between(detected.wallTime(), promoted.wallTime()).toMillis();
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
    @DisplayName("a node down when Experiment 8 started joins when it recovers and adopts the current epoch")
    void nodeDownAtStartJoinsOnRecovery() {
        fixture = new FailoverFixture(5);
        fixture.cluster.crash(5);
        fixture.startAndAwaitPrimary(1);
        assertThat(FaultToleranceNodeService.find(fixture.cluster.node(5))).isEmpty();

        fixture.cluster.recover(5);

        FailoverFixture.within().until(() -> FaultToleranceNodeService.find(fixture.cluster.node(5))
                .map(s -> s.rejoinState() == RejoinState.READY).orElse(false));
        assertThat(fixture.events("ROLE_QUERY")).anySatisfy(e -> {
            assertThat(e.nodeId()).isEqualTo(5);
            assertThat(e.data()).containsEntry("action", "ADOPT_AND_RESYNC").containsEntry("epoch", 2L);
        });
        assertThat(fixture.replication(5).epoch()).isEqualTo(2);
        assertThat(fixture.service(5).believedPrimaryId()).isEqualTo(1);
    }

    @Test
    @DisplayName("reset forgets runs, the ledger and the epoch authority, and steps down the primary it served")
    void resetClearsState() throws Exception {
        fixture = new FailoverFixture(3);
        fixture.startAndAwaitPrimary(1);
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
    }
}
