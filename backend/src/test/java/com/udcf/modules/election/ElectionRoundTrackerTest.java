package com.udcf.modules.election;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The election round lifecycle as a pure class: a fake nano clock, no sockets. */
class ElectionRoundTrackerTest {

    private static final long MS = 1_000_000L;
    private static final Instant START = Instant.parse("2026-10-09T10:00:00Z");

    private final AtomicLong nanos = new AtomicLong(5_000 * MS);
    private final ElectionRoundTracker tracker =
            new ElectionRoundTracker(nanos::get, Clock.fixed(START, ZoneOffset.UTC), 10_000);

    @Test
    @DisplayName("only one round is open at a time")
    void opensOnlyOneRoundAtATime() {
        Optional<ElectionRound> first = tracker.open(ElectionAlgorithm.BULLY, RoundTrigger.MANUAL, 1);
        assertThat(first).hasValueSatisfying(round -> {
            assertThat(round.roundId()).isEqualTo(1);
            assertThat(round.outcome()).isEqualTo(RoundOutcome.IN_PROGRESS);
            assertThat(round.startedAt()).isEqualTo(START);
            assertThat(round.leaderId()).isNull();
            assertThat(round.durationMillis()).isNull();
        });
        assertThat(tracker.open(ElectionAlgorithm.RING, RoundTrigger.MANUAL, 2)).isEmpty();
        assertThat(tracker.current()).isEqualTo(first);
    }

    @Test
    @DisplayName("eight threads opening at once for the same dead leader open exactly one round")
    void concurrentOpenersOpenExactlyOneRound() throws Exception {
        int callers = 8;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Optional<ElectionRound>>> results = new ArrayList<>();
            for (int i = 1; i <= callers; i++) {
                int nodeId = i;
                results.add(pool.submit(() -> {
                    go.await();
                    return tracker.open(ElectionAlgorithm.BULLY, RoundTrigger.LEADER_FAILURE, nodeId);
                }));
            }
            go.countDown();
            long opened = 0;
            for (Future<Optional<ElectionRound>> result : results) {
                opened += result.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0;
            }
            assertThat(opened).isEqualTo(1);
            assertThat(tracker.current()).isPresent();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a round completes only after an ELECTED event, with the measured duration")
    void completesOnlyAfterElectedWithMeasuredDuration() {
        tracker.markElected();   // no round: ignored
        tracker.open(ElectionAlgorithm.BULLY, RoundTrigger.MANUAL, 1);
        assertThat(tracker.complete(5)).isEmpty();
        nanos.addAndGet(250 * MS);
        tracker.markElected();
        Optional<ElectionRound> done = tracker.complete(5);
        assertThat(done).hasValueSatisfying(round -> {
            assertThat(round.outcome()).isEqualTo(RoundOutcome.ELECTED);
            assertThat(round.leaderId()).isEqualTo(5);
            assertThat(round.durationMillis()).isEqualTo(250.0);
            assertThat(round.initiatorNodeId()).isEqualTo(1);
        });
        assertThat(tracker.current()).isEmpty();
        assertThat(tracker.last()).isEqualTo(done);
        assertThat(tracker.complete(5)).isEmpty();
    }

    @Test
    @DisplayName("a round open longer than the timeout ends TIMED_OUT with no leader and no duration")
    void expiresOverdueRoundAsTimedOutWithNullDuration() {
        tracker.open(ElectionAlgorithm.RING, RoundTrigger.MANUAL, 1);
        nanos.addAndGet(10_000 * MS);
        assertThat(tracker.expireIfOverdue()).isEmpty();
        nanos.addAndGet(1);
        Optional<ElectionRound> expired = tracker.expireIfOverdue();
        assertThat(expired).hasValueSatisfying(round -> {
            assertThat(round.outcome()).isEqualTo(RoundOutcome.TIMED_OUT);
            assertThat(round.leaderId()).isNull();
            assertThat(round.durationMillis()).isNull();
        });
        assertThat(tracker.current()).isEmpty();
        assertThat(tracker.last()).isEqualTo(expired);
    }

    @Test
    @DisplayName("a cancelled round is dropped and not kept as the last round")
    void cancelDropsRoundWithoutKeepingIt() {
        ElectionRound round = tracker.open(ElectionAlgorithm.BULLY, RoundTrigger.MANUAL, 2).orElseThrow();
        tracker.cancel(round.roundId() + 1);
        assertThat(tracker.current()).isPresent();
        tracker.cancel(round.roundId());
        assertThat(tracker.current()).isEmpty();
        assertThat(tracker.last()).isEmpty();
    }

    @Test
    @DisplayName("clear forgets the open and the last round; round ids keep counting")
    void clearForgetsCurrentAndLastButIdsKeepCounting() {
        tracker.open(ElectionAlgorithm.BULLY, RoundTrigger.MANUAL, 1);
        tracker.markElected();
        tracker.complete(5);
        tracker.open(ElectionAlgorithm.BULLY, RoundTrigger.RECOVERY, 5);
        tracker.clear();
        assertThat(tracker.current()).isEmpty();
        assertThat(tracker.last()).isEmpty();
        assertThat(tracker.open(ElectionAlgorithm.RING, RoundTrigger.MANUAL, 3))
                .hasValueSatisfying(round -> assertThat(round.roundId()).isEqualTo(3));
    }
}
