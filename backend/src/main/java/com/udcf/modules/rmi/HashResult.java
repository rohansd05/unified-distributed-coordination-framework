package com.udcf.modules.rmi;

import java.io.Serial;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Reply to {@link NodeEndpoint#computeHash}.
 *
 * @param nodeId      the node that did the work
 * @param lamportTime its Lamport time when it replied
 * @param rounds      SHA-256 rounds computed
 * @param seed        the text the chain started from ({@link NodeEndpointLogic#seedFor})
 * @param digestHex   the final digest, 64 lowercase hex characters
 * @param serverNanos time the node spent hashing, measured around the loop only
 */
public record HashResult(
        int nodeId,
        long lamportTime,
        int rounds,
        String seed,
        String digestHex,
        long serverNanos
) implements EndpointReply {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public HashResult {
        EndpointReply.requireStamp(nodeId, lamportTime);
        if (rounds < 1) {
            throw new IllegalArgumentException("rounds must be >= 1, was " + rounds);
        }
        Objects.requireNonNull(seed, "seed must not be null");
        Objects.requireNonNull(digestHex, "digestHex must not be null");
        if (!SHA256_HEX.matcher(digestHex).matches()) {
            throw new IllegalArgumentException("digestHex must be 64 lowercase hex characters, was " + digestHex);
        }
        if (serverNanos < 0) {
            throw new IllegalArgumentException("serverNanos must be >= 0, was " + serverNanos);
        }
    }
}
