package com.udcf.modules.replication.dto;

import com.udcf.core.module.ModuleStatus;

import java.util.List;

/**
 * Everything the Experiment 5 page needs in one read. Building it never starts a service,
 * changes a role or runs a takeover.
 *
 * <p>No dedicated test: a record; built and tested through ReplicationModuleTest and
 * ReplicationControllerTest.</p>
 *
 * @param actionInProgress       the guarded action running now; null when not BUSY
 * @param primaryNodeId          the node the selector picks now (lowest live node); null if every
 *                               node is crashed
 * @param currentPrimaryNodeId   the node last made primary; null if none yet (or since a reset)
 * @param takeoverPending        true if a primary was made before and the selector now picks another
 *                               node: the next write, anti-entropy, injection or recover will run a
 *                               takeover (catch-up, then push)
 * @param lastTakeover           the latest selection; null if none yet
 * @param asyncDelayMillis       the simulated delay before each asynchronous push (R7)
 * @param asyncDelayReason       why that delay is simulated
 * @param timeoutMillis          connect and read timeout of every replication exchange
 * @param batchSize              items per anti-entropy message and per dump page
 * @param conflictRuleNote       how conflicts are resolved, including what last-writer-wins can lose
 * @param healthMeasuredByNodeId the node whose push statistics fill {@code health}; null if none
 * @param health                 one row per backup of that node, in node order; empty if none
 * @param latestWrite            null if none since the last reset
 * @param latestAntiEntropy      null if none since the last reset
 * @param latestInjection        null if none since the last reset
 */
public record ReplicationOverviewDto(
        ModuleStatus status,
        String actionInProgress,
        Integer primaryNodeId,
        Integer currentPrimaryNodeId,
        boolean takeoverPending,
        TakeoverDto lastTakeover,
        long asyncDelayMillis,
        String asyncDelayReason,
        int timeoutMillis,
        int batchSize,
        String conflictRuleNote,
        List<ModelDto> models,
        List<NodeReplicaDto> nodes,
        Integer healthMeasuredByNodeId,
        List<HealthRowDto> health,
        WriteDto latestWrite,
        AntiEntropyDto latestAntiEntropy,
        InjectionDto latestInjection
) {
}
