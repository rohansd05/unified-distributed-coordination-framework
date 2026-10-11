package com.udcf.modules.rmi.dto;

/**
 * The configured Experiment 1 limits ({@code udcf.rmi.*}).
 *
 * <p>No dedicated test: a record; tested through RmiControllerTest.</p>
 *
 * @param historySize          calls and comparisons kept, newest first
 * @param callTimeoutMillis    read timeout of every RMI client socket
 * @param connectTimeoutMillis connect timeout of every RMI client socket
 */
public record RmiLimitsDto(
        int maxHashRounds,
        int maxEchoLength,
        int historySize,
        int callTimeoutMillis,
        int connectTimeoutMillis,
        int maxFailureMessageLength
) {
}
