package com.udcf.modules.faulttolerance.dto;

/**
 * Experiment 8's settings, all from configuration.
 *
 * <p>No dedicated test: a record; built and tested through FaultToleranceModuleTest.</p>
 *
 * @param maxUpdates              updates all streams may send between two cluster resets
 * @param historyLimit            failover runs kept
 * @param retryWindowMillis       how long one client update keeps retrying (attempts x delay)
 * @param worstCaseFailoverMillis the worst failover the retry window covers
 * @param asyncDelayMillis        the delay before each asynchronous push, from the replication service
 * @param asyncDelaySimulated     true when that delay is above 0: it is simulated (R7)
 * @param coHostingSimulated      always true: the nodes share one process (R1)
 */
public record SettingsDto(int defaultRatePerSecond, int maxRatePerSecond, int maxUpdates, int historyLimit,
                          long heartbeatIntervalMillis, long heartbeatTimeoutMillis, long retryWindowMillis,
                          long worstCaseFailoverMillis, long asyncDelayMillis, boolean asyncDelaySimulated,
                          String asyncDelayReason, boolean coHostingSimulated, String coHostingReason) {
}
