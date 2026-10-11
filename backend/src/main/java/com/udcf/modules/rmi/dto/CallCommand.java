package com.udcf.modules.rmi.dto;

import com.udcf.modules.rmi.RmiMethod;
import jakarta.validation.constraints.NotNull;

/**
 * Request body of {@code POST /api/modules/rmi/calls} and {@code POST /api/modules/rmi/comparisons}.
 * The module also checks the method's arguments against the configured limits, and that a field
 * the method does not take is left out (400 with field messages).
 *
 * <p>No dedicated test: a record; validation is tested in RmiControllerTest and RmiModuleTest.</p>
 *
 * @param fromNode   the calling node (a real node with its own Lamport clock); may equal {@code targetNode}
 * @param targetNode the node whose endpoint is called
 * @param method     PING, NODE_INFO, ECHO or COMPUTE_HASH
 * @param text       ECHO only: 0 to max-echo-length characters; must be left out otherwise
 * @param rounds     COMPUTE_HASH only: 1 to max-hash-rounds; must be left out otherwise
 */
public record CallCommand(
        @NotNull(message = "fromNode is required") Integer fromNode,
        @NotNull(message = "targetNode is required") Integer targetNode,
        @NotNull(message = "method is required") RmiMethod method,
        String text,
        Integer rounds
) {
}
