package com.udcf.modules.loadbalancing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PhaseReportTest {

    private static DispatchResult served(int id, int node, double latency) {
        return new DispatchResult(id, node, latency, true, 1);
    }

    private static DispatchResult failed(int id) {
        return new DispatchResult(id, 0, 3d, false, 3);
    }

    @Test
    @DisplayName("requests per node lists every node in id order, zeros included, failures excluded")
    void requestsPerNode() {
        PhaseReport report = new PhaseReport(Strategy.ROUND_ROBIN, List.of(3, 1, 2),
                List.of(served(1, 1, 5d), served(2, 1, 7d), served(3, 2, 9d), failed(4)), 50d);

        assertThat(report.requestsPerNode()).containsExactly(Map.entry(1, 2), Map.entry(2, 1), Map.entry(3, 0));
        assertThat(report.nodeIds()).containsExactly(1, 2, 3);
        // Legacy counted the failure under node 0 and left node 3 out: spread 2 - 1 = 1.
        assertThat(report.loadSpread()).isEqualTo(2);
    }

    @Test
    @DisplayName("average latency per node covers only what that node served")
    void averageLatencyByNode() {
        PhaseReport report = new PhaseReport(Strategy.LEAST_CONNECTIONS, List.of(1, 2),
                List.of(served(1, 1, 4d), served(2, 1, 8d), failed(3)), 20d);

        assertThat(report.averageLatencyByNode().get(1).getAsDouble()).isEqualTo(6d);
        assertThat(report.averageLatencyByNode().get(2)).isEmpty();
    }

    @Test
    @DisplayName("average, nearest-rank p95 and max are over served requests only")
    void latencyFigures() {
        List<DispatchResult> results = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            results.add(served(i, 1, i));
        }
        results.add(failed(21));
        PhaseReport report = new PhaseReport(Strategy.ROUND_ROBIN, List.of(1), results, 100d);

        assertThat(report.averageLatency().getAsDouble()).isCloseTo(10.5d, within(1e-9));
        assertThat(report.p95Latency().getAsDouble()).isEqualTo(19d);   // rank ceil(0.95 * 20) = 19
        assertThat(report.maxLatency().getAsDouble()).isEqualTo(20d);
    }

    @Test
    @DisplayName("p95 of a single request is that request")
    void p95Single() {
        PhaseReport report = new PhaseReport(Strategy.ROUND_ROBIN, List.of(1), List.of(served(1, 1, 42d)), 42d);

        assertThat(report.p95Latency().getAsDouble()).isEqualTo(42d);
    }

    @Test
    @DisplayName("with nothing served the latency figures are empty, never 0")
    void emptyLatency() {
        PhaseReport report = new PhaseReport(Strategy.ROUND_ROBIN, List.of(1, 2), List.of(failed(1)), 0d);

        assertThat(report.averageLatency()).isEmpty();
        assertThat(report.p95Latency()).isEmpty();
        assertThat(report.maxLatency()).isEmpty();
        assertThat(report.loadSpread()).isZero();
        assertThat(report.served()).isZero();
    }

    @Test
    @DisplayName("counts failures, reroutes (served or not), served and total; keeps the makespan")
    void counts() {
        PhaseReport report = new PhaseReport(Strategy.LEAST_RESPONSE_TIME, List.of(1, 2),
                List.of(served(1, 1, 1d), new DispatchResult(2, 2, 9d, true, 2), failed(3)), 123.4d);

        assertThat(report.failures()).isEqualTo(1);
        assertThat(report.reroutes()).isEqualTo(2);
        assertThat(report.served()).isEqualTo(2);
        assertThat(report.total()).isEqualTo(3);
        assertThat(report.makespanMillis()).isEqualTo(123.4d);
        assertThat(report.strategy()).isEqualTo(Strategy.LEAST_RESPONSE_TIME);
    }

    @Test
    @DisplayName("results, node ids and maps are unmodifiable copies")
    void unmodifiable() {
        List<DispatchResult> source = new ArrayList<>(List.of(served(1, 1, 1d)));
        PhaseReport report = new PhaseReport(Strategy.ROUND_ROBIN, List.of(1), source, 1d);
        source.add(served(2, 1, 1d));

        assertThat(report.total()).isEqualTo(1);
        assertThatThrownBy(() -> report.results().add(served(3, 1, 1d)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> report.nodeIds().add(9)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> report.requestsPerNode().put(9, 9)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("rejects null arguments with NullPointerException and bad values with IllegalArgumentException")
    void validation() {
        List<DispatchResult> ok = List.of(served(1, 1, 1d));
        assertThatThrownBy(() -> new PhaseReport(null, List.of(1), ok, 1d)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PhaseReport(Strategy.ROUND_ROBIN, null, ok, 1d))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PhaseReport(Strategy.ROUND_ROBIN, List.of(1), null, 1d))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PhaseReport(Strategy.ROUND_ROBIN, List.of(1),
                Arrays.asList(served(1, 1, 1d), null), 1d)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PhaseReport(Strategy.ROUND_ROBIN, List.of(), ok, 1d))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhaseReport(Strategy.ROUND_ROBIN, List.of(1, 1), ok, 1d))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhaseReport(Strategy.ROUND_ROBIN, List.of(2), ok, 1d))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not in nodeIds");
        assertThatThrownBy(() -> new PhaseReport(Strategy.ROUND_ROBIN, List.of(1), ok, -1d))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhaseReport(Strategy.ROUND_ROBIN, List.of(1), ok, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
