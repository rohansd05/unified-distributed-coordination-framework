package com.udcf.modules.loadbalancing.dto;

/**
 * Request body of {@code POST /api/modules/loadbalancing/comparisons}: the same batch for all
 * four strategies. Checked against the configured limits by the module.
 *
 * <p>No dedicated test: a record; validation is tested in LoadBalancingControllerTest.</p>
 */
public record CompareCommand(int requestCount, int workUnits, int concurrency) {
}
