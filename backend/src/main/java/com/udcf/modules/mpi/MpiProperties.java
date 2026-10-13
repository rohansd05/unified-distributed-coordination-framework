package com.udcf.modules.mpi;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Experiment 9 settings, bound from {@code udcf.mpi.*}.
 *
 * <p>No defaults in code, so a missing key fails startup. Registered by
 * {@code @ConfigurationPropertiesScan}. Binding is covered by MpiPropertiesTest.</p>
 *
 * @param maxPayloadElements       longest vector a collective may move (and the longest one a node accepts);
 *                                 smaller in the public profile (512 MB RAM). E9b's serial filter uses it as
 *                                 its array limit.
 * @param collectiveDeadlineMillis no new transfer starts once this much time has passed since the collective
 *                                 started; the rest are NOT_ATTEMPTED
 * @param historySize              collectives the module keeps for the page (used from E9c)
 * @param receiveStoreSize         received payloads each node keeps, by collective id; the oldest is dropped
 * @param maxFailureMessageLength  longest failure message kept in a transfer record; a longer one is cut
 */
@Validated
@ConfigurationProperties("udcf.mpi")
public record MpiProperties(
        @Min(1) int maxPayloadElements,
        @Min(1) int collectiveDeadlineMillis,
        @Min(1) int historySize,
        @Min(1) int receiveStoreSize,
        @Min(1) int maxFailureMessageLength
) {

    public MpiProperties {
        requireAtLeastOne("maxPayloadElements", maxPayloadElements);
        requireAtLeastOne("collectiveDeadlineMillis", collectiveDeadlineMillis);
        requireAtLeastOne("historySize", historySize);
        requireAtLeastOne("receiveStoreSize", receiveStoreSize);
        requireAtLeastOne("maxFailureMessageLength", maxFailureMessageLength);
    }

    private static void requireAtLeastOne(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be >= 1, was " + value);
        }
    }
}
