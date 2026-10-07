package com.udcf.modules.loadbalancing;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Experiment 6 settings, bound from {@code udcf.loadbalancing.*}. No defaults in code, so a
 * missing key fails startup. Registered by {@code @ConfigurationPropertiesScan}.
 *
 * <p>Binding is covered by LoadBalancingPropertiesTest.</p>
 *
 * @param requestTimeoutMillis connect and read timeout for one attempt. The read includes the
 *                             worker's queue wait, so it must stay well above the worst queue
 *                             wait: a timeout trips the circuit breaker on a worker that may
 *                             still be alive and finishing the work
 */
@Validated
@ConfigurationProperties("udcf.loadbalancing")
public record LoadBalancingProperties(
        @Min(1) int requestTimeoutMillis
) {
}
