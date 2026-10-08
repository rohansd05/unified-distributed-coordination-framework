package com.udcf.modules.loadbalancing.dto;

/**
 * Request part: crash {@code nodeId} once {@code afterServed} requests of the run have been
 * served. The module injects this crash itself; it is a real crash of the node on every
 * protocol (R10), and the node stays down after the run.
 *
 * <p>No dedicated test: a record without behaviour; validated by LoadBalancingModule
 * (afterServed in 1..requestCount-1, the node exists and is up), tested in
 * LoadBalancingModuleTest and LoadBalancingControllerTest.</p>
 */
public record CrashPlan(int nodeId, int afterServed) {
}
