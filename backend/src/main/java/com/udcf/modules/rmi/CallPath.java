package com.udcf.modules.rmi;

/**
 * How a call reached the endpoint.
 *
 * <p>No dedicated test: an enum without behaviour (R6).</p>
 */
public enum CallPath {

    /** A direct Java call on the endpoint object: no stub, no registry, nothing marshalled. */
    LOCAL,

    /** Through the node's RMI registry and a stub: arguments and result are marshalled over a socket. */
    REMOTE
}
