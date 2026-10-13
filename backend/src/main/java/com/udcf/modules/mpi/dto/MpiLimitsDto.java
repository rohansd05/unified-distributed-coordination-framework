package com.udcf.modules.mpi.dto;

/**
 * The configured limits of Experiment 9, and two bounds derived from them.
 *
 * <p>No dedicated test: a record; tested through MpiModuleTest and MpiControllerTest.</p>
 *
 * @param maxPayloadElements                the largest count
 * @param collectiveDeadlineMillis          no new transfer starts after this
 * @param historySize                       collectives kept
 * @param maxSeed                           the largest seed (2^53 - 1)
 * @param connectTimeoutMillis              connect timeout of every RMI client socket
 * @param callTimeoutMillis                 read timeout of every RMI client socket
 * @param worstCaseTransferMillis           a BOUND derived from the configured timeouts, not a measurement: three
 *                                          exchanges (registry lookup, RMI's DGC dirty call, the method call),
 *                                          each one connect plus one read
 * @param recommendedMinClientTimeoutMillis collectiveDeadlineMillis + worstCaseTransferMillis, exactly; a client
 *                                          adds its own margin
 */
public record MpiLimitsDto(int maxPayloadElements, int collectiveDeadlineMillis, int historySize, long maxSeed,
                           int connectTimeoutMillis, int callTimeoutMillis, long worstCaseTransferMillis,
                           long recommendedMinClientTimeoutMillis) {
}
