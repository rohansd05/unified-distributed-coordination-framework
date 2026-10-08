package com.udcf.modules.loadbalancing.dto;

import com.udcf.modules.loadbalancing.Strategy;

/**
 * A strategy with two plain sentences a student can read aloud.
 *
 * <p>No dedicated test: a record; the texts are checked in LoadBalancingModuleTest.</p>
 *
 * @param description     what it does
 * @param informationUsed what it knows when it chooses a worker
 */
public record StrategyDto(Strategy strategy, String description, String informationUsed) {
}
