package com.udcf.modules.rmi;

/**
 * Where a remote call failed.
 *
 * <p>No dedicated test: an enum without behaviour (R6).</p>
 */
public enum CallStage {

    /** Obtaining the endpoint (the registry lookup): the method was never called. */
    LOOKUP,

    /** The method call itself, after the endpoint was obtained. */
    CALL
}
