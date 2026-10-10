package com.udcf.modules.rmi;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Experiment 1 settings, bound from {@code udcf.rmi.*}.
 *
 * <p>No defaults in code, so a missing key fails startup. Registered by
 * {@code @ConfigurationPropertiesScan}. Binding is covered by RmiPropertiesTest.</p>
 *
 * @param maxHashRounds           most SHA-256 rounds one {@code computeHash} call may ask for; smaller
 *                                in the public profile (0.1 CPU), so the longest hash stays well
 *                                inside {@code callTimeoutMillis}
 * @param maxEchoLength           longest {@code echo} text, in UTF-16 characters
 * @param historySize             calls the module keeps for the page (used from E1c)
 * @param callTimeoutMillis       connect and response timeout of one RMI call (used from E1b)
 * @param maxFailureMessageLength longest failure message kept in a call result; a longer one is cut
 *                                and ends with "..." (the ellipsis character)
 */
@Validated
@ConfigurationProperties("udcf.rmi")
public record RmiProperties(
        @Min(1) int maxHashRounds,
        @Min(1) int maxEchoLength,
        @Min(1) int historySize,
        @Min(1) int callTimeoutMillis,
        @Min(1) int maxFailureMessageLength
) {

    public RmiProperties {
        requireAtLeastOne("maxHashRounds", maxHashRounds);
        requireAtLeastOne("maxEchoLength", maxEchoLength);
        requireAtLeastOne("historySize", historySize);
        requireAtLeastOne("callTimeoutMillis", callTimeoutMillis);
        requireAtLeastOne("maxFailureMessageLength", maxFailureMessageLength);
    }

    private static void requireAtLeastOne(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be >= 1, was " + value);
        }
    }
}
