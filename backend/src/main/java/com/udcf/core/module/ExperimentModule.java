package com.udcf.core.module;

/**
 * One of the ten lab experiments, running on the shared cluster.
 *
 * <p>No dedicated test: an interface. ModuleRegistryTest checks how modules are collected
 * and validated.</p>
 */
public interface ExperimentModule {

    /** Stable id used in URLs and events, e.g. "election". */
    String id();

    /** Lab experiment number, 1 to 10. */
    int labNumber();

    /** Display title, e.g. "Bully and Ring Election". */
    String title();

    ModuleStatus status();

    /** Back to a clean demonstration state. */
    void reset();
}
