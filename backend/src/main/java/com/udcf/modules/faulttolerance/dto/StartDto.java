package com.udcf.modules.faulttolerance.dto;

/**
 * Answer to {@code POST /start}.
 *
 * <p>No dedicated test: a record; built and tested through FaultToleranceModuleTest.</p>
 *
 * @param outcome STARTED (the services started and the first primary is being appointed) or
 *                ALREADY_STARTED (nothing changed)
 * @param reason  why nothing changed; null when STARTED
 */
public record StartDto(String outcome, String reason, FaultToleranceStatusDto status) {
}
