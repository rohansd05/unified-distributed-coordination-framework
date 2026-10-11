package com.udcf.modules.rmi.dto;

import com.udcf.modules.rmi.RmiMethod;

import java.time.Instant;

/**
 * The same call made directly (LOCAL) and then through RMI (REMOTE). Every figure is computed
 * from measured values only; the overhead can be negative and the ratio below 1 (see {@code note}).
 *
 * <p>No dedicated test: a record; built and tested in RmiDtoMapperTest.</p>
 *
 * @param localNanos    the LOCAL round trip; null if it did not succeed
 * @param remoteNanos   the REMOTE round trip; null if it did not succeed
 * @param overheadNanos remote minus local; null unless both are known
 * @param ratio         remote divided by local; null unless both are known and local is above 0
 * @param digestsMatch  COMPUTE_HASH with both sides successful only; null otherwise
 * @param note          how to read a single-measurement comparison honestly
 */
public record ComparisonDto(
        long id,
        Instant calledAt,
        RmiMethod method,
        int fromNode,
        int targetNode,
        RmiCallDto local,
        RmiCallDto remote,
        Long localNanos,
        Long remoteNanos,
        Long overheadNanos,
        Double ratio,
        Boolean digestsMatch,
        String note
) {
}
