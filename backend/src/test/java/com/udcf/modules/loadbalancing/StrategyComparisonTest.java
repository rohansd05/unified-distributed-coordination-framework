package com.udcf.modules.loadbalancing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class StrategyComparisonTest {

    /** A report whose node k (1-based) served perNode[k-1] requests. */
    private static PhaseReport report(Strategy strategy, double makespan, int... perNode) {
        List<Integer> ids = new ArrayList<>();
        List<DispatchResult> results = new ArrayList<>();
        int requestId = 1;
        for (int k = 1; k <= perNode.length; k++) {
            ids.add(k);
            for (int i = 0; i < perNode[k - 1]; i++) {
                results.add(new DispatchResult(requestId++, k, 10d, true, 1));
            }
        }
        return new PhaseReport(strategy, ids, results, makespan);
    }

    /** The shape the legacy demo prints: round robin even and slowest. */
    private static List<PhaseReport> legacyShape() {
        return List.of(
                report(Strategy.LEAST_RESPONSE_TIME, 380d, 42, 14, 4),
                report(Strategy.ROUND_ROBIN, 900d, 20, 20, 20),
                report(Strategy.LEAST_CONNECTIONS, 400d, 40, 15, 5),
                report(Strategy.WEIGHTED_ROUND_ROBIN, 500d, 34, 17, 9));
    }

    @Test
    @DisplayName("reports what the measured runs show when round robin is even and slowest")
    void legacyFinding() {
        StrategyComparison c = new StrategyComparison(legacyShape());

        assertThat(c.fastest()).get().extracting(PhaseReport::strategy).isEqualTo(Strategy.LEAST_RESPONSE_TIME);
        assertThat(c.slowest()).get().extracting(PhaseReport::strategy).isEqualTo(Strategy.ROUND_ROBIN);
        assertThat(c.mostEven()).get().extracting(PhaseReport::strategy).isEqualTo(Strategy.ROUND_ROBIN);
        assertThat(c.roundRobinFinishedLast()).isTrue();
        assertThat(c.roundRobinMostEven()).isTrue();
        // Legacy formula: 100 * (rr - best) / rr
        assertThat(c.gainOverRoundRobinPercent().getAsDouble()).isCloseTo(100d * 520 / 900, within(1e-9));
    }

    @Test
    @DisplayName("reports are kept in Strategy order and can be looked up")
    void ordering() {
        StrategyComparison c = new StrategyComparison(legacyShape());

        assertThat(c.reports()).extracting(PhaseReport::strategy).containsExactly(Strategy.values());
        assertThat(c.report(Strategy.LEAST_CONNECTIONS)).get()
                .extracting(PhaseReport::makespanMillis).isEqualTo(400d);
    }

    @Test
    @DisplayName("never claims round robin finished last when it did not")
    void roundRobinNotLast() {
        StrategyComparison c = new StrategyComparison(List.of(
                report(Strategy.ROUND_ROBIN, 300d, 5, 5, 5),
                report(Strategy.LEAST_CONNECTIONS, 400d, 9, 4, 2)));

        assertThat(c.roundRobinFinishedLast()).isFalse();
        assertThat(c.fastest()).get().extracting(PhaseReport::strategy).isEqualTo(Strategy.ROUND_ROBIN);
        assertThat(c.gainOverRoundRobinPercent()).isEmpty();
    }

    @Test
    @DisplayName("a makespan tie is not 'finished last'; tied ranks go to the strategy declared first")
    void ties() {
        StrategyComparison c = new StrategyComparison(List.of(
                report(Strategy.WEIGHTED_ROUND_ROBIN, 500d, 5, 5),
                report(Strategy.ROUND_ROBIN, 500d, 5, 5)));

        assertThat(c.roundRobinFinishedLast()).isFalse();
        assertThat(c.roundRobinMostEven()).isTrue();
        assertThat(c.slowest()).get().extracting(PhaseReport::strategy).isEqualTo(Strategy.ROUND_ROBIN);
        assertThat(c.fastest()).get().extracting(PhaseReport::strategy).isEqualTo(Strategy.ROUND_ROBIN);
        assertThat(c.mostEven()).get().extracting(PhaseReport::strategy).isEqualTo(Strategy.ROUND_ROBIN);
    }

    @Test
    @DisplayName("round robin is not 'most even' when another strategy had a smaller spread")
    void roundRobinNotMostEven() {
        StrategyComparison c = new StrategyComparison(List.of(
                report(Strategy.ROUND_ROBIN, 900d, 6, 5, 4),
                report(Strategy.LEAST_CONNECTIONS, 400d, 5, 5, 5)));

        assertThat(c.roundRobinMostEven()).isFalse();
        assertThat(c.mostEven()).get().extracting(PhaseReport::strategy).isEqualTo(Strategy.LEAST_CONNECTIONS);
    }

    @Test
    @DisplayName("without round robin, or with round robin alone, nothing is claimed")
    void missingRoundRobin() {
        StrategyComparison noRr = new StrategyComparison(List.of(
                report(Strategy.LEAST_CONNECTIONS, 400d, 9, 4),
                report(Strategy.LEAST_RESPONSE_TIME, 380d, 10, 3)));
        StrategyComparison rrOnly = new StrategyComparison(List.of(report(Strategy.ROUND_ROBIN, 900d, 5, 5)));
        StrategyComparison empty = new StrategyComparison(List.of());

        for (StrategyComparison c : List.of(noRr, rrOnly, empty)) {
            assertThat(c.roundRobinFinishedLast()).isFalse();
            assertThat(c.roundRobinMostEven()).isFalse();
            assertThat(c.gainOverRoundRobinPercent()).isEmpty();
        }
        assertThat(empty.fastest()).isEmpty();
        assertThat(empty.slowest()).isEmpty();
        assertThat(empty.mostEven()).isEmpty();
    }

    @Test
    @DisplayName("rejects null arguments with NullPointerException and duplicate strategies with IllegalArgumentException")
    void validation() {
        assertThatThrownBy(() -> new StrategyComparison(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StrategyComparison(Arrays.asList(report(Strategy.ROUND_ROBIN, 1d, 1), null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StrategyComparison(List.of(
                report(Strategy.ROUND_ROBIN, 1d, 1), report(Strategy.ROUND_ROBIN, 2d, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        StrategyComparison c = new StrategyComparison(List.of());
        assertThatThrownBy(() -> c.report(null)).isInstanceOf(NullPointerException.class);
    }
}
