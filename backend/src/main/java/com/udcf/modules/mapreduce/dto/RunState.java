package com.udcf.modules.mapreduce.dto;

/**
 * Where a run is: RUNNING until it ends, then COMPLETED or FAILED.
 *
 * <p>No dedicated test: a plain enum; the transitions are tested in MapReduceModuleTest.</p>
 */
public enum RunState {
    RUNNING,
    COMPLETED,
    FAILED
}
