package com.udcf.modules.mpi.dto;

/**
 * Which rule chose a collective's root.
 *
 * <p>No dedicated test: an enum without behaviour (R6); the rule is tested in MpiRoleSelectorTest.</p>
 */
public enum RootSource {
    /** The request named the root. */
    REQUESTED,
    /** No root was requested; the elected leader (Experiment 4, link L1) was live and became the root. */
    LEADER,
    /** No root was requested and no live leader exists: the lowest live node id. */
    LOWEST_LIVE_ID
}
