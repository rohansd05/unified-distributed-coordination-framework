package com.udcf.modules.multithreading.dto;

/**
 * Why a burst of requests was sent: an ordinary batch, or the backpressure demonstration.
 *
 * <p>No dedicated test: a plain enum. MultithreadingModuleTest and the controller tests check
 * where each value is used.</p>
 */
public enum BatchKind {
    BATCH,
    BACKPRESSURE
}
