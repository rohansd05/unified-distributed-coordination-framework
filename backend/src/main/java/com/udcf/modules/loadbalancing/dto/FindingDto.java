package com.udcf.modules.loadbalancing.dto;

import com.udcf.modules.loadbalancing.Strategy;

/**
 * What a measured comparison shows, straight from {@code StrategyComparison}. A page may say
 * "Round Robin finished last" only when {@code roundRobinFinishedLast} is true.
 *
 * <p>No dedicated test: a record without behaviour.</p>
 *
 * @param gainOverRoundRobinPercent how much faster the fastest strategy finished than round
 *                                  robin; null when round robin was itself the fastest
 */
public record FindingDto(
        Strategy fastest,
        Strategy slowest,
        Strategy mostEven,
        boolean roundRobinFinishedLast,
        boolean roundRobinMostEven,
        Double gainOverRoundRobinPercent
) {
}
