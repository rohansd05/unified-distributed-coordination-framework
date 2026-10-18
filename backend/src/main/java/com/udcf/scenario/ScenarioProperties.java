package com.udcf.scenario;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code udcf.scenario}: the scenario engine's limits, from YAML only. Registered by
 * {@code @ConfigurationPropertiesScan}.
 *
 * <p>No dedicated test: a record; bound in every Spring test context, used in ScenarioEngineTest.</p>
 *
 * @param historyLimit       runs kept in memory (the oldest is dropped first)
 * @param resetTimeoutMillis how long the clean-slate reset before a run may retry while a module is busy
 * @param stepDeadlineMillis default deadline of one step's waits; above Experiment 8's worst failover
 * @param pollIntervalMillis polling fallback of every wait (the bus subscription wakes it sooner)
 * @param slowNode           "A slow node under load"
 */
@Validated
@ConfigurationProperties("udcf.scenario")
public record ScenarioProperties(@Min(1) int historyLimit, @Min(1) long resetTimeoutMillis,
                                 @Min(1) long stepDeadlineMillis, @Min(1) long pollIntervalMillis,
                                 @NotNull @Valid SlowNode slowNode) {

    /** @param workUnits work units per request in the comparison (requests and clients are Experiment 6's defaults) */
    public record SlowNode(@Min(1) int workUnits) {
    }
}
