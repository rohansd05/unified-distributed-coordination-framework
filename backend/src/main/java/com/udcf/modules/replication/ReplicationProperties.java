package com.udcf.modules.replication;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Experiment 5 replication settings shared by every node, bound from {@code udcf.replication.*}.
 *
 * <p>No defaults in code, so a missing key fails startup. Registered by
 * {@code @ConfigurationPropertiesScan}. Binding is covered by ReplicationPropertiesTest.</p>
 *
 * @param asyncDelayMillis <b>simulated</b> (R7): how long an asynchronous push waits before it
 *                         is sent. On one machine a replication round trip takes well under a
 *                         millisecond, so the eventual-consistency window would be invisible;
 *                         this delay stands in for wide-area network latency (Appendix B: 450 ms).
 *                         Synchronous pushes are never delayed.
 * @param timeoutMillis    connect and read timeout of every replication exchange, on the client
 *                         and on the server (an accepted connection's {@code SO_TIMEOUT})
 * @param batchSize        items per anti-entropy message and per dump page, 1 to
 *                         {@link ReplicationProtocol#MAX_ITEMS_PER_MESSAGE}
 */
@Validated
@ConfigurationProperties("udcf.replication")
public record ReplicationProperties(
        @Min(0) long asyncDelayMillis,
        @Min(1) int timeoutMillis,
        @Min(1) @Max(ReplicationProtocol.MAX_ITEMS_PER_MESSAGE) int batchSize
) {

    public ReplicationProperties {
        if (asyncDelayMillis < 0) {
            throw new IllegalArgumentException("asyncDelayMillis must be >= 0, was " + asyncDelayMillis);
        }
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("timeoutMillis must be >= 1, was " + timeoutMillis);
        }
        if (batchSize < 1 || batchSize > ReplicationProtocol.MAX_ITEMS_PER_MESSAGE) {
            throw new IllegalArgumentException("batchSize must be 1-" + ReplicationProtocol.MAX_ITEMS_PER_MESSAGE
                    + ", was " + batchSize);
        }
    }
}
