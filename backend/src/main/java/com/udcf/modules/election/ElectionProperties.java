package com.udcf.modules.election;

import com.udcf.core.failure.FailureDetectorConfig;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Experiment 4 settings shared by every node, bound from {@code udcf.election.*}
 * (docs/HANDOFF.md Appendix B), including the shared failure detector's timing: its heartbeats
 * run on the election socket (link L2).
 *
 * <p>No defaults in code, so a missing key fails startup. Registered by
 * {@code @ConfigurationPropertiesScan}. Binding is covered by ElectionPropertiesTest.</p>
 *
 * @param okTimeoutMillis             Bully: wait for an OK before self-promotion (900 ms)
 * @param coordinatorTimeoutMillis    Bully: wait for COORDINATOR after an OK before restarting (2200 ms)
 * @param probeTimeoutMillis          Ring: wait for a PROBE_ACK before skipping a successor (300 ms);
 *                                    must be shorter than the OK timeout
 * @param ringCompletionTimeoutMillis Ring: give up an election whose token never came back (not
 *                                    in Appendix B; 5000 ms)
 * @param heartbeatIntervalMillis     failure detector: heartbeat to every peer this often (700 ms)
 * @param heartbeatTimeoutMillis      failure detector: suspect a peer silent for longer (2500 ms);
 *                                    must exceed the interval
 * @param roundTimeoutMillis          ElectionModule: an election round not agreed within this is
 *                                    closed as TIMED_OUT and not measured (10000 ms); must exceed
 *                                    the ring completion timeout and OK + coordinator timeouts
 */
@Validated
@ConfigurationProperties("udcf.election")
public record ElectionProperties(
        @Min(1) long okTimeoutMillis,
        @Min(1) long coordinatorTimeoutMillis,
        @Min(1) long probeTimeoutMillis,
        @Min(1) long ringCompletionTimeoutMillis,
        @Min(1) long heartbeatIntervalMillis,
        @Min(1) long heartbeatTimeoutMillis,
        @Min(1) long roundTimeoutMillis
) {

    public ElectionProperties {
        // Both conversions validate (positive values, probe < OK, heartbeat timeout > interval).
        toElectionConfig(okTimeoutMillis, coordinatorTimeoutMillis, probeTimeoutMillis, ringCompletionTimeoutMillis);
        new FailureDetectorConfig(heartbeatIntervalMillis, heartbeatTimeoutMillis);
        if (roundTimeoutMillis <= ringCompletionTimeoutMillis
                || roundTimeoutMillis <= okTimeoutMillis + coordinatorTimeoutMillis) {
            throw new IllegalArgumentException("roundTimeoutMillis (" + roundTimeoutMillis
                    + ") must exceed ringCompletionTimeoutMillis (" + ringCompletionTimeoutMillis
                    + ") and okTimeoutMillis + coordinatorTimeoutMillis ("
                    + (okTimeoutMillis + coordinatorTimeoutMillis) + ")");
        }
    }

    public ElectionConfig toElectionConfig() {
        return toElectionConfig(okTimeoutMillis, coordinatorTimeoutMillis, probeTimeoutMillis, ringCompletionTimeoutMillis);
    }

    public FailureDetectorConfig toFailureDetectorConfig() {
        return new FailureDetectorConfig(heartbeatIntervalMillis, heartbeatTimeoutMillis);
    }

    private static ElectionConfig toElectionConfig(long ok, long coordinator, long probe, long ringCompletion) {
        try {
            return new ElectionConfig(ok, coordinator, probe, ringCompletion);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid udcf.election timeouts (okTimeoutMillis=" + ok
                    + ", coordinatorTimeoutMillis=" + coordinator + ", probeTimeoutMillis=" + probe
                    + ", ringCompletionTimeoutMillis=" + ringCompletion + "): " + e.getMessage(), e);
        }
    }
}
