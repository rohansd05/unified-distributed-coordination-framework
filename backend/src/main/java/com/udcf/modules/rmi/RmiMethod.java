package com.udcf.modules.rmi;

/**
 * The four {@link NodeEndpoint} methods a caller can invoke.
 *
 * <p>No dedicated test: an enum without behaviour (R6).</p>
 */
public enum RmiMethod {
    PING,
    NODE_INFO,
    ECHO,
    COMPUTE_HASH
}
