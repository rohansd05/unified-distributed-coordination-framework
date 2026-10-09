package com.udcf.modules.election;

import com.udcf.core.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;

/**
 * Experiment 4 meters, each tagged with {@code node_id} (R5), {@code algorithm} and
 * {@code trigger}:
 * <ul>
 *   <li>{@value MetricNames#LEADER_ELECTIONS_TOTAL} {node_id = the elected leader}: rounds that
 *       ended with every live node agreeing on it.</li>
 *   <li>{@value MetricNames#ELECTION_DURATION} {node_id = the node the round started from}: the
 *       measured time from the round's start to agreement. For a LEADER_FAILURE round the start
 *       is the detection, not the crash.</li>
 * </ul>
 *
 * <p>A round that timed out records nothing: it has no leader and no measured duration (R7).
 * Plain class, owned by {@link ElectionModule}. Covered by ElectionMetricsTest.</p>
 */
public class ElectionMetrics {

    static final String ALGORITHM = "algorithm";
    static final String TRIGGER = "trigger";

    private final MeterRegistry registry;

    public ElectionMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /** Records a finished round; anything but a measured ELECTED round is ignored. */
    public void record(ElectionRound round) {
        Objects.requireNonNull(round, "round must not be null");
        if (round.outcome() != RoundOutcome.ELECTED || round.leaderId() == null || round.durationMillis() == null) {
            return;
        }
        String algorithm = round.algorithm().name();
        String trigger = round.trigger().name();
        registry.counter(MetricNames.LEADER_ELECTIONS_TOTAL,
                MetricNames.NODE_ID, String.valueOf(round.leaderId()), ALGORITHM, algorithm, TRIGGER, trigger)
                .increment();
        Timer.builder(MetricNames.ELECTION_DURATION)
                .description("Measured time from an election round's start until every live node agreed on the leader")
                .tag(MetricNames.NODE_ID, String.valueOf(round.initiatorNodeId()))
                .tag(ALGORITHM, algorithm)
                .tag(TRIGGER, trigger)
                .register(registry)
                .record(Duration.ofNanos(Math.round(round.durationMillis() * 1_000_000d)));
    }
}
