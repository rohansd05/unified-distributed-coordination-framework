package com.udcf.modules.election.dto;

import com.udcf.modules.election.ElectionProperties;

/**
 * The configured election and failure-detector timings (udcf.election.*).
 *
 * <p>No dedicated test: a plain record. ElectionControllerTest checks the values.</p>
 */
public record ElectionSettingsDto(
        long okTimeoutMillis,
        long coordinatorTimeoutMillis,
        long probeTimeoutMillis,
        long ringCompletionTimeoutMillis,
        long heartbeatIntervalMillis,
        long heartbeatTimeoutMillis,
        long roundTimeoutMillis
) {

    public static ElectionSettingsDto from(ElectionProperties p) {
        return new ElectionSettingsDto(p.okTimeoutMillis(), p.coordinatorTimeoutMillis(), p.probeTimeoutMillis(),
                p.ringCompletionTimeoutMillis(), p.heartbeatIntervalMillis(), p.heartbeatTimeoutMillis(),
                p.roundTimeoutMillis());
    }
}
