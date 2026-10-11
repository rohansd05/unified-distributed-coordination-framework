package com.udcf.modules.rmi.dto;

import java.util.Objects;

/**
 * One kept call or comparison. Exactly one of {@code call} and {@code comparison} is set.
 *
 * <p>No dedicated test: a record with two factories; tested through RmiHistoryTest and
 * RmiControllerTest.</p>
 *
 * @param id sequential, starting at 1; restarts at 1 after a reset
 */
public record HistoryEntryDto(long id, HistoryKind kind, RmiCallDto call, ComparisonDto comparison) {

    public static HistoryEntryDto of(RmiCallDto call) {
        Objects.requireNonNull(call, "call must not be null");
        return new HistoryEntryDto(call.id(), HistoryKind.CALL, call, null);
    }

    public static HistoryEntryDto of(ComparisonDto comparison) {
        Objects.requireNonNull(comparison, "comparison must not be null");
        return new HistoryEntryDto(comparison.id(), HistoryKind.COMPARISON, null, comparison);
    }
}
