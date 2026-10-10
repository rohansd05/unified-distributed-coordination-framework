package com.udcf.modules.rmi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Objects;

/**
 * Experiment 1 transport settings (step E1b), bound from the same {@code udcf.rmi.*} block as
 * {@link RmiProperties}, which keeps the call settings. Kept as a separate record so the E1a
 * properties stay unchanged.
 *
 * <p>No defaults in code, so a missing key fails startup. Registered by
 * {@code @ConfigurationPropertiesScan}. Binding is covered by RmiTransportPropertiesTest.</p>
 *
 * @param connectTimeoutMillis connect timeout of every RMI client socket; {@code call-timeout-millis}
 *                             is the read timeout of the same socket
 * @param listenBacklog        backlog of each node's RMI listener
 * @param serialFilter         JEP 290 limits for the arguments a node's {@link NodeEndpoint} reads
 */
@Validated
@ConfigurationProperties("udcf.rmi")
public record RmiTransportProperties(
        @Min(1) int connectTimeoutMillis,
        @Min(1) int listenBacklog,
        @Valid @NotNull SerialFilter serialFilter
) {

    public RmiTransportProperties {
        requireAtLeastOne("connectTimeoutMillis", connectTimeoutMillis);
        requireAtLeastOne("listenBacklog", listenBacklog);
        Objects.requireNonNull(serialFilter, "serialFilter must not be null");
    }

    /**
     * Limits of {@link RmiSerialFilter} ({@code udcf.rmi.serial-filter.*}). A normal call reaches
     * the filter with depth 1, 2 references and 74 bytes (measured in E1b).
     *
     * @param maxDepth most nested objects
     * @param maxRefs  most object references
     * @param maxBytes most bytes read before a filter checkpoint
     * @param maxArray longest array (RmiSerialFilter rejects every array anyway; this is
     *                 defence in depth)
     */
    public record SerialFilter(
            @Min(1) long maxDepth,
            @Min(1) long maxRefs,
            @Min(1) long maxBytes,
            @Min(1) long maxArray
    ) {

        public SerialFilter {
            requireAtLeastOne("maxDepth", maxDepth);
            requireAtLeastOne("maxRefs", maxRefs);
            requireAtLeastOne("maxBytes", maxBytes);
            requireAtLeastOne("maxArray", maxArray);
        }
    }

    private static void requireAtLeastOne(String name, long value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be >= 1, was " + value);
        }
    }
}
