package com.udcf.modules.loadbalancing.dto;

/**
 * Where a run or a comparison is: RUNNING until it ends, then FINISHED or FAILED.
 *
 * <p>No dedicated test: a plain enum; the transitions are tested in LoadBalancingModuleTest.</p>
 */
public enum ActionState {
    RUNNING,
    FINISHED,
    FAILED
}
