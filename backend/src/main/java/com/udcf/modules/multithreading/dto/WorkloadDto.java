package com.udcf.modules.multithreading.dto;

import com.udcf.modules.multithreading.WorkloadType;

/**
 * One workload type the page can offer, with an honest label (R7).
 *
 * <p>No dedicated test: a data carrier. MultithreadingModuleTest checks the labels and
 * MultithreadingControllerTest the JSON fields.</p>
 *
 * @param type            the workload, as sent in a batch request
 * @param description     what the work is, in a plain sentence
 * @param simulated       true when part of the work is a stand-in rather than real work
 * @param simulatedReason a plain sentence saying what is simulated and why; null when not simulated
 */
public record WorkloadDto(WorkloadType type, String description, boolean simulated, String simulatedReason) {
}
