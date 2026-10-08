package com.udcf.modules.loadbalancing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * "Compare all four": the same batch run once per strategy, side by side.
 *
 * <p>The experiment's key finding is that round robin has the most even request counts and
 * the worst finish time, because equal request counts are not equal load. That is a
 * measured result, not a rule: this class only reports what the given runs show. A page may
 * say "Round Robin finished last" only when {@link #roundRobinFinishedLast()} is true for
 * the measured run.</p>
 *
 * <p>Ported from the comparison in legacy-demos/exp06-load-balancing
 * ({@code LoadBalancerDemo.printComparison}). Ties go to the strategy declared first in
 * {@link Strategy}.</p>
 */
public class StrategyComparison {

    private static final Comparator<PhaseReport> BY_STRATEGY =
            Comparator.comparing(PhaseReport::strategy);

    private final List<PhaseReport> reports;

    /** @param reports at most one report per strategy, in any order; may be empty */
    public StrategyComparison(List<PhaseReport> reports) {
        Objects.requireNonNull(reports, "reports must not be null");
        Set<Strategy> seen = EnumSet.noneOf(Strategy.class);
        List<PhaseReport> sorted = new ArrayList<>(reports.size());
        for (PhaseReport r : reports) {
            Objects.requireNonNull(r, "reports must not contain null");
            if (!seen.add(r.strategy())) {
                throw new IllegalArgumentException("more than one report for " + r.strategy());
            }
            sorted.add(r);
        }
        sorted.sort(BY_STRATEGY);
        this.reports = List.copyOf(sorted);
    }

    /** The reports in {@link Strategy} order (unmodifiable). */
    public List<PhaseReport> reports() {
        return reports;
    }

    public Optional<PhaseReport> report(Strategy strategy) {
        Objects.requireNonNull(strategy, "strategy must not be null");
        return reports.stream().filter(r -> r.strategy() == strategy).findFirst();
    }

    /** Shortest makespan. */
    public Optional<PhaseReport> fastest() {
        return reports.stream().min(Comparator.comparingDouble(PhaseReport::makespanMillis).thenComparing(BY_STRATEGY));
    }

    /** Longest makespan. */
    public Optional<PhaseReport> slowest() {
        return reports.stream().min(Comparator.comparingDouble(PhaseReport::makespanMillis).reversed()
                .thenComparing(BY_STRATEGY));
    }

    /** Smallest request-count spread. */
    public Optional<PhaseReport> mostEven() {
        return reports.stream().min(Comparator.comparingInt(PhaseReport::loadSpread).thenComparing(BY_STRATEGY));
    }

    /**
     * How much faster the fastest strategy finished than round robin, as a percentage of
     * round robin's makespan (the legacy formula). Empty when there is no round robin report,
     * no other report, round robin itself was fastest, or its makespan was 0.
     */
    public OptionalDouble gainOverRoundRobinPercent() {
        Optional<PhaseReport> rr = report(Strategy.ROUND_ROBIN);
        Optional<PhaseReport> best = fastest();
        if (rr.isEmpty() || best.isEmpty() || best.get().strategy() == Strategy.ROUND_ROBIN
                || rr.get().makespanMillis() == 0d) {
            return OptionalDouble.empty();
        }
        double rrMakespan = rr.get().makespanMillis();
        return OptionalDouble.of(100d * (rrMakespan - best.get().makespanMillis()) / rrMakespan);
    }

    /**
     * True only if there is a round robin report and at least one other, and round robin's
     * makespan is strictly longer than every other's. A tie is not "finished last".
     */
    public boolean roundRobinFinishedLast() {
        Optional<PhaseReport> rr = report(Strategy.ROUND_ROBIN);
        if (rr.isEmpty() || reports.size() < 2) {
            return false;
        }
        double rrMakespan = rr.get().makespanMillis();
        return reports.stream()
                .filter(r -> r.strategy() != Strategy.ROUND_ROBIN)
                .allMatch(r -> r.makespanMillis() < rrMakespan);
    }

    /**
     * True only if there is a round robin report and at least one other, and no other
     * strategy had a smaller request-count spread (a tie still counts as most even).
     */
    public boolean roundRobinMostEven() {
        Optional<PhaseReport> rr = report(Strategy.ROUND_ROBIN);
        if (rr.isEmpty() || reports.size() < 2) {
            return false;
        }
        int rrSpread = rr.get().loadSpread();
        return reports.stream()
                .filter(r -> r.strategy() != Strategy.ROUND_ROBIN)
                .allMatch(r -> r.loadSpread() >= rrSpread);
    }
}
