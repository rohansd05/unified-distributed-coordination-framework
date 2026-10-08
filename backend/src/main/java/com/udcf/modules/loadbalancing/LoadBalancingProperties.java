package com.udcf.modules.loadbalancing;

import com.udcf.modules.multithreading.dto.GenerateRequestsCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
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
 * @param defaults             what the page offers first; must fit the limits, including a
 *                             whole comparison under the total-work cap
 * @param limits               the largest run the API accepts
 */
@Validated
@ConfigurationProperties("udcf.loadbalancing")
public record LoadBalancingProperties(
        @Min(1) int requestTimeoutMillis,
        @Valid @NotNull Defaults defaults,
        @Valid @NotNull Limits limits
) {

    /** A comparison runs the batch {@value} times: one unreported warm-up plus the four strategies. */
    public static final int COMPARISON_BATCHES = 5;

    /**
     * @param requestCount requests per run
     * @param workUnits    Exp 2 payload per request (one unit is 40 SHA-256 rounds times the
     *                     node's work multiplier)
     * @param concurrency  concurrent clients
     */
    public record Defaults(
            @Min(1) int requestCount,
            @Min(1) int workUnits,
            @Min(1) int concurrency
    ) {
    }

    /**
     * @param maxRequestCount most requests in one run
     * @param maxWorkUnits    most work units per request (never above the Exp 2 payload limit)
     * @param maxConcurrency  most concurrent clients
     * @param maxTotalWork    most requestCount x workUnits in one run; a comparison may use
     *                        {@value #COMPARISON_BATCHES} times that product in total
     */
    public record Limits(
            @Min(1) int maxRequestCount,
            @Min(1) @Max(GenerateRequestsCommand.MAX_PAYLOAD_SIZE) int maxWorkUnits,
            @Min(1) int maxConcurrency,
            @Min(1) long maxTotalWork
    ) {
    }

    /** The defaults fit every limit, and a default comparison fits the total-work cap. */
    @AssertTrue(message = "defaults must fit the limits, and a default comparison "
            + "(5 x requestCount x workUnits) must fit max-total-work")
    public boolean isDefaultsWithinLimits() {
        if (defaults == null || limits == null) {
            return true;   // reported by @NotNull
        }
        return defaults.requestCount() <= limits.maxRequestCount()
                && defaults.workUnits() <= limits.maxWorkUnits()
                && defaults.concurrency() <= limits.maxConcurrency()
                && (long) COMPARISON_BATCHES * defaults.requestCount() * defaults.workUnits() <= limits.maxTotalWork();
    }
}
