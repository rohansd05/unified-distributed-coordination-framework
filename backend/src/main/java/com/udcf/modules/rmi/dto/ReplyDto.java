package com.udcf.modules.rmi.dto;

import com.udcf.modules.rmi.RmiMethod;

/**
 * The reply a node returned. Fields that belong to another method's reply are null.
 *
 * <p>No dedicated test: a record; tested through RmiDtoMapperTest.</p>
 *
 * @param method          which reply this is
 * @param nodeId          the node that answered
 * @param lamportTime     its Lamport time on the reply
 * @param registryPort    NODE_INFO only
 * @param capacityProfile NODE_INFO only
 * @param uptimeMillis    NODE_INFO only: time since this endpoint was exported on the node
 * @param javaVersion     NODE_INFO only: the JVM version number (no host, user or path)
 * @param text            ECHO only: the text, unchanged
 * @param rounds          COMPUTE_HASH only
 * @param seed            COMPUTE_HASH only
 * @param digestHex       COMPUTE_HASH only
 * @param serverNanos     COMPUTE_HASH only: time the node spent hashing, measured on the node
 */
public record ReplyDto(
        RmiMethod method,
        int nodeId,
        long lamportTime,
        Integer registryPort,
        String capacityProfile,
        Long uptimeMillis,
        String javaVersion,
        String text,
        Integer rounds,
        String seed,
        String digestHex,
        Long serverNanos
) {
}
