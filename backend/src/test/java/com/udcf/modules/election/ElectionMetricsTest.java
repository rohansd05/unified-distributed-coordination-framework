package com.udcf.modules.election;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Exact meter names and tags of Experiment 4 (R5); unmeasured rounds record nothing (R7). No sockets. */
class ElectionMetricsTest {

    private static final Instant AT = Instant.parse("2026-10-09T10:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ElectionMetrics metrics = new ElectionMetrics(registry);

    private static ElectionRound round(RoundOutcome outcome, Integer leader, Double millis) {
        return new ElectionRound(7, ElectionAlgorithm.BULLY, RoundTrigger.LEADER_FAILURE, 2, AT, outcome, leader, millis);
    }

    private static List<Tag> tags(Meter meter) {
        return meter.getId().getTags();
    }

    @Test
    @DisplayName("an ELECTED round counts distributed_leader_elections_total{node_id=leader, algorithm, trigger}")
    void electionCountedPerLeaderNodeWithAlgorithmAndTrigger() {
        metrics.record(round(RoundOutcome.ELECTED, 4, 312.5));
        metrics.record(round(RoundOutcome.ELECTED, 4, 100.0));
        Meter counter = registry.get("distributed_leader_elections_total").meter();
        assertThat(tags(counter)).containsExactlyInAnyOrder(
                Tag.of("node_id", "4"), Tag.of("algorithm", "BULLY"), Tag.of("trigger", "LEADER_FAILURE"));
        assertThat(registry.get("distributed_leader_elections_total")
                .tags("node_id", "4", "algorithm", "BULLY", "trigger", "LEADER_FAILURE").counter().count())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("an ELECTED round times distributed_election_duration{node_id=initiator, algorithm, trigger}")
    void durationTimedPerInitiatorNode() {
        metrics.record(round(RoundOutcome.ELECTED, 4, 312.5));
        Timer timer = registry.get("distributed_election_duration").timer();
        assertThat(tags(timer)).containsExactlyInAnyOrder(
                Tag.of("node_id", "2"), Tag.of("algorithm", "BULLY"), Tag.of("trigger", "LEADER_FAILURE"));
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MICROSECONDS)).isEqualTo(312_500.0);
        assertThat(registry.getMeters()).extracting(m -> m.getId().getName())
                .containsExactlyInAnyOrder("distributed_leader_elections_total", "distributed_election_duration");
    }

    @Test
    @DisplayName("a TIMED_OUT or unfinished round records no meter at all")
    void timedOutRoundRecordsNothing() {
        metrics.record(round(RoundOutcome.TIMED_OUT, null, null));
        metrics.record(round(RoundOutcome.IN_PROGRESS, null, null));
        assertThat(registry.getMeters()).isEmpty();
    }
}
