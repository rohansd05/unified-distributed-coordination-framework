package com.udcf.modules.faulttolerance.dto;

import java.util.List;

/**
 * Everything the Experiment 8 page needs in one read. Building it starts nothing, records
 * nothing and publishes nothing.
 *
 * <p>No dedicated test: a record; built and tested through FaultToleranceModuleTest and
 * FaultToleranceControllerTest.</p>
 *
 * @param latestRun null before the first failover since the last reset
 * @param glossary  every figure, measured or derived
 * @param notes     what is real and what is simulated, in plain sentences
 */
public record FaultToleranceOverviewDto(FaultToleranceStatusDto current, RunDto latestRun, SettingsDto settings,
                                        List<GlossaryEntryDto> glossary, List<String> notes) {
}
