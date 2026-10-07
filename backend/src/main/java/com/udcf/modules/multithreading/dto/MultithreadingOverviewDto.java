package com.udcf.modules.multithreading.dto;

import com.udcf.core.module.ModuleStatus;

import java.util.List;

/**
 * Everything the Multithreading page needs in one read.
 *
 * <p>No dedicated test: a data carrier. MultithreadingControllerTest asserts its JSON fields.</p>
 *
 * @param status           IDLE, RUNNING (a batch is draining) or BUSY (the backpressure demo)
 * @param actionInProgress the guarded action while BUSY; null otherwise
 * @param capacityNote     a plain sentence: the capacity profiles are configured, not measured
 * @param workloads        every workload type, with its simulated label
 * @param nodes            every node, in id order
 */
public record MultithreadingOverviewDto(
        ModuleStatus status,
        String actionInProgress,
        String capacityNote,
        List<WorkloadDto> workloads,
        List<NodeRequestsDto> nodes
) {
}
