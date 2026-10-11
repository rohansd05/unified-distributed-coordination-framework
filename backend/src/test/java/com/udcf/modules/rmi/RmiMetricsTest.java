package com.udcf.modules.rmi;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.metrics.MetricNames;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Experiment 1 meters: node_id is the calling node on every meter, the same tag keys per name,
 * and a timer sample only for a completed round trip.
 *
 * <p>Test-only ports 28781 to 28783 (RMI base 28780; Jai's sub-range 28750-28799), used only by the
 * gauge test.</p>
 */
@Timeout(60)
class RmiMetricsTest {

    private ClusterEventBus bus;
    private Cluster cluster;
    private SimpleMeterRegistry registry;
    private RmiMetrics metrics;

    @BeforeEach
    void setUp() {
        bus = RmiTestSupport.bus();
        cluster = RmiTestSupport.cluster(28780, 3, bus);
        registry = new SimpleMeterRegistry();
        metrics = new RmiMetrics(registry, cluster);
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    private double count(String name, String... tags) {
        return registry.find(name).tags(tags).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Test
    @DisplayName("a success counts once and records exactly one round-trip sample of the measured duration")
    void successRecordsTheRoundTrip() {
        metrics.record(RmiResultFixtures.remoteSuccess(RmiCall.ping(), 840_000));

        assertThat(count(MetricNames.RMI_CALLS_TOTAL, "node_id", "1", "target_node", "2", "method", "PING",
                "path", "REMOTE", "outcome", "SUCCESS")).isEqualTo(1);
        Timer timer = registry.find(MetricNames.RMI_ROUND_TRIP).tags("node_id", "1", "target_node", "2", "method",
                "PING", "path", "REMOTE").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isEqualTo(840_000d);
        assertThat(registry.find(MetricNames.RMI_FAILURES_TOTAL).counters()).isEmpty();
    }

    @Test
    @DisplayName("a failure counts as REMOTE_FAILURE and by stage and kind, and never records a round-trip sample")
    void failureRecordsNoRoundTrip() {
        metrics.record(RmiResultFixtures.lookupRefused(RmiCall.ping()));
        metrics.record(RmiResultFixtures.callTimedOut(RmiCall.computeHash(5)));

        assertThat(count(MetricNames.RMI_CALLS_TOTAL, "outcome", "REMOTE_FAILURE")).isEqualTo(2);
        assertThat(count(MetricNames.RMI_FAILURES_TOTAL, "node_id", "1", "target_node", "2", "method", "PING",
                "stage", "LOOKUP", "kind", "CONNECTION_REFUSED")).isEqualTo(1);
        assertThat(count(MetricNames.RMI_FAILURES_TOTAL, "method", "COMPUTE_HASH", "stage", "CALL",
                "kind", "TIMED_OUT")).isEqualTo(1);
        assertThat(registry.find(MetricNames.RMI_ROUND_TRIP).timers()).isEmpty();
    }

    @Test
    @DisplayName("a LOCAL half is counted and timed with path LOCAL")
    void localPathIsTagged() {
        metrics.record(RmiResultFixtures.localSuccess(RmiCall.echo("x")));

        assertThat(count(MetricNames.RMI_CALLS_TOTAL, "path", "LOCAL", "method", "ECHO")).isEqualTo(1);
        assertThat(registry.find(MetricNames.RMI_ROUND_TRIP).tag("path", "LOCAL").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("every meter carries node_id (the caller, or the node for the gauge) and one set of tag keys per name")
    void nodeIdAndTagKeys() {
        for (RmiCall call : RmiResultFixtures.everyMethod()) {
            metrics.record(RmiResultFixtures.remoteSuccess(call));
            metrics.record(RmiResultFixtures.localSuccess(call));
            metrics.record(RmiResultFixtures.lookupRefused(call));
            metrics.record(RmiResultFixtures.callTimedOut(call));
        }
        List<Meter> meters = registry.getMeters();
        assertThat(meters).extracting(meter -> meter.getId().getName()).contains(MetricNames.RMI_CALLS_TOTAL,
                MetricNames.RMI_FAILURES_TOTAL, MetricNames.RMI_ROUND_TRIP, MetricNames.RMI_ENDPOINT_EXPORTED);
        assertThat(meters).allSatisfy(meter -> assertThat(meter.getId().getTag(MetricNames.NODE_ID))
                .as(meter.getId().getName()).isNotNull());
        meters.stream().filter(m -> !m.getId().getName().equals(MetricNames.RMI_ENDPOINT_EXPORTED))
                .forEach(m -> assertThat(m.getId().getTag(MetricNames.NODE_ID)).isEqualTo("1"));

        Map<String, Set<Set<String>>> keysByName = new HashMap<>();
        for (Meter meter : meters) {
            Set<String> keys = meter.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toCollection(TreeSet::new));
            keysByName.computeIfAbsent(meter.getId().getName(), n -> new java.util.HashSet<>()).add(keys);
        }
        assertThat(keysByName).allSatisfy((name, keySets) -> assertThat(keySets).as(name).hasSize(1));
        assertThat(keysByName.get(MetricNames.RMI_CALLS_TOTAL).iterator().next())
                .containsExactlyInAnyOrder("node_id", "target_node", "method", "path", "outcome");
        assertThat(keysByName.get(MetricNames.RMI_FAILURES_TOTAL).iterator().next())
                .containsExactlyInAnyOrder("node_id", "target_node", "method", "stage", "kind");
        assertThat(keysByName.get(MetricNames.RMI_ROUND_TRIP).iterator().next())
                .containsExactlyInAnyOrder("node_id", "target_node", "method", "path");
    }

    private double exported(int nodeId) {
        return registry.find(MetricNames.RMI_ENDPOINT_EXPORTED).tag("node_id", String.valueOf(nodeId)).gauge().value();
    }

    @Test
    @DisplayName("the exported gauge reads the live service: 0, 1 after start, 0 when crashed, 1 after recovery; reading never starts it")
    void exportedGaugeIsLive() {
        assertThat(exported(1)).isZero();
        assertThat(cluster.node(1).service(RmiNodeService.NAME)).isEmpty();

        RmiTestSupport.start(cluster, 1);
        assertThat(exported(1)).isEqualTo(1);
        assertThat(exported(2)).isZero();

        cluster.crash(1);
        assertThat(exported(1)).isZero();
        cluster.recover(1);
        assertThat(exported(1)).isEqualTo(1);
    }

    @Test
    @DisplayName("creating the meters again on the same registry replaces each gauge instead of adding a second")
    void gaugesAreReplaced() {
        new RmiMetrics(registry, cluster);
        assertThat(registry.find(MetricNames.RMI_ENDPOINT_EXPORTED).gauges()).hasSize(3);
    }
}
