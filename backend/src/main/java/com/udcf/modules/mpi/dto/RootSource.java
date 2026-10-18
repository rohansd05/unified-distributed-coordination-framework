package com.udcf.modules.mpi.dto;

/**
 * Which rule chose a collective's root.
 *
 * <p>No dedicated test: an enum without behaviour (R6); the rule is tested in MpiModuleTest and
 * MatrixModuleTest.</p>
 */
public enum RootSource {
    /** The request named the root. */
    REQUESTED,
    /** No root was requested: the leader elected by Experiment 4 (link L1) became the root. */
    LEADER
}
