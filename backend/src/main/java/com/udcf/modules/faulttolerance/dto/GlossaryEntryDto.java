package com.udcf.modules.faulttolerance.dto;

/**
 * One figure the page shows.
 *
 * <p>No dedicated test: a record; its texts are checked in FaultToleranceModuleTest.</p>
 *
 * @param kind measured (observed) or derived (computed from settings)
 */
public record GlossaryEntryDto(String id, String label, String kind, String definition) {
}
