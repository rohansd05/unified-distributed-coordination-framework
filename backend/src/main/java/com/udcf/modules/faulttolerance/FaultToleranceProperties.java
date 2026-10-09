package com.udcf.modules.faulttolerance;

import com.udcf.modules.election.ElectionProperties;
import com.udcf.modules.replication.ReplicationProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Objects;

/**
 * Experiment 8 settings, bound from {@code udcf.faulttolerance.*}.
 *
 * <p>No defaults in code, so a missing key fails startup. Registered by
 * {@code @ConfigurationPropertiesScan}. Failure detection has no settings here: it is the
 * shared detector's ({@code udcf.election.heartbeat-*}, link L2). Binding and the window
 * formula are covered by FaultTolerancePropertiesTest.</p>
 *
 * <h2>The client retry window must cover the worst failover</h2>
 * <pre>
 * worst = detection                      heartbeat timeout + heartbeat interval
 *       + 2 x promotion attempt          (the chosen node fails, a second candidate is tried)
 * promotion attempt = catch-up bound     promotion.catch-up-timeout-millis (peers in parallel)
 *                   + term-record bound  2 x replication timeout (connect + read; pushes in parallel)
 * </pre>
 * {@link #worstCaseFailoverMillis(long, long, long, long)} is that formula, the only copy;
 * {@link #requireWindowCovers} applies it and {@link FailoverCluster} calls that at construction.
 *
 * @param client       the update client's retry policy (E8a {@link UpdateRetryPolicy})
 * @param roleQuery    how often a recovering node asks again when no peer answered its role query
 * @param promotion    bounds of one promotion
 * @param historyLimit failover runs kept by the {@link FailoverStateMachine}
 */
@Validated
@ConfigurationProperties("udcf.faulttolerance")
public record FaultToleranceProperties(
        @NotNull @Valid Client client,
        @NotNull @Valid RoleQuerySettings roleQuery,
        @NotNull @Valid Promotion promotion,
        @Min(1) int historyLimit
) {

    /**
     * @param maxAttempts      sends and discoveries per update
     * @param retryDelayMillis wait before a retry after an unreachable node or a failed discovery
     */
    public record Client(@Min(1) int maxAttempts, @Min(1) long retryDelayMillis) {

        public Client {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1, was " + maxAttempts);
            }
            if (retryDelayMillis < 1) {
                throw new IllegalArgumentException("retryDelayMillis must be >= 1, was " + retryDelayMillis);
            }
        }
    }

    /**
     * @param retryDelayMillis wait before asking again after no peer answered
     * @param maxAttempts      role queries before the node stops asking (it stays non-primary)
     */
    public record RoleQuerySettings(@Min(1) long retryDelayMillis, @Min(1) int maxAttempts) {

        public RoleQuerySettings {
            if (retryDelayMillis < 1) {
                throw new IllegalArgumentException("retryDelayMillis must be >= 1, was " + retryDelayMillis);
            }
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1, was " + maxAttempts);
            }
        }
    }

    /**
     * @param catchUpTimeoutMillis how long the chosen node waits for its catch-up from the other
     *                             live peers (all in parallel); a peer not done by then is skipped
     */
    public record Promotion(@Min(1) long catchUpTimeoutMillis) {

        public Promotion {
            if (catchUpTimeoutMillis < 1) {
                throw new IllegalArgumentException("catchUpTimeoutMillis must be >= 1, was " + catchUpTimeoutMillis);
            }
        }
    }

    public FaultToleranceProperties {
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(roleQuery, "roleQuery must not be null");
        Objects.requireNonNull(promotion, "promotion must not be null");
        if (historyLimit < 1) {
            throw new IllegalArgumentException("historyLimit must be >= 1, was " + historyLimit);
        }
    }

    /** The E8a retry policy of the update client. */
    public UpdateRetryPolicy retryPolicy() {
        return new UpdateRetryPolicy(client.maxAttempts(), client.retryDelayMillis());
    }

    /** The least time an update keeps retrying: attempts x delay. */
    public long retryWindowMillis() {
        return (long) client.maxAttempts() * client.retryDelayMillis();
    }

    /** Upper bound of one promotion attempt: catch-up bound + term-record bound (2 x replication timeout). */
    public static long promotionAttemptBoundMillis(long catchUpTimeoutMillis, long replicationTimeoutMillis) {
        return catchUpTimeoutMillis + 2 * replicationTimeoutMillis;
    }

    /**
     * The worst-case failover the client must outlast:
     * {@code (heartbeatTimeout + heartbeatInterval) + 2 x (catchUpTimeout + 2 x replicationTimeout)}.
     */
    public static long worstCaseFailoverMillis(long heartbeatIntervalMillis, long heartbeatTimeoutMillis,
                                               long catchUpTimeoutMillis, long replicationTimeoutMillis) {
        long detection = heartbeatTimeoutMillis + heartbeatIntervalMillis;
        return detection + 2 * promotionAttemptBoundMillis(catchUpTimeoutMillis, replicationTimeoutMillis);
    }

    /** {@link #worstCaseFailoverMillis(long, long, long, long)} with the configured values. */
    public long worstCaseFailoverMillis(ElectionProperties election, ReplicationProperties replication) {
        Objects.requireNonNull(election, "election must not be null");
        Objects.requireNonNull(replication, "replication must not be null");
        return worstCaseFailoverMillis(election.heartbeatIntervalMillis(), election.heartbeatTimeoutMillis(),
                promotion.catchUpTimeoutMillis(), replication.timeoutMillis());
    }

    /**
     * Fails fast if an update could give up during a worst-case failover.
     *
     * @throws IllegalStateException if attempts x delay is below {@link #worstCaseFailoverMillis(ElectionProperties, ReplicationProperties)}
     */
    public void requireWindowCovers(ElectionProperties election, ReplicationProperties replication) {
        long worst = worstCaseFailoverMillis(election, replication);
        if (retryWindowMillis() < worst) {
            throw new IllegalStateException("udcf.faulttolerance.client retries for " + client.maxAttempts() + " x "
                    + client.retryDelayMillis() + " = " + retryWindowMillis() + " ms, less than the worst-case failover "
                    + "of " + worst + " ms = detection (" + election.heartbeatTimeoutMillis() + " + "
                    + election.heartbeatIntervalMillis() + ") + 2 x (catch-up " + promotion.catchUpTimeoutMillis()
                    + " + term record 2 x " + replication.timeoutMillis() + ")");
        }
    }
}
