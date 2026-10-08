package com.udcf.modules.election;

public record ElectionConfig(
        long okTimeoutMs,
        long coordinatorTimeoutMs,
        long probeTimeoutMs,
        long ringCompletionTimeoutMs
) {
    public ElectionConfig {
        if (okTimeoutMs <= 0 || coordinatorTimeoutMs <= 0 || probeTimeoutMs <= 0 || ringCompletionTimeoutMs <= 0) {
            throw new IllegalArgumentException("All timeouts must be strictly positive");
        }
        if (probeTimeoutMs >= okTimeoutMs) {
            throw new IllegalArgumentException("probeTimeoutMs must be strictly less than okTimeoutMs");
        }
    }
}
