package com.udcf.modules.rmi;

import java.io.EOFException;
import java.net.SocketTimeoutException;
import java.rmi.ConnectException;
import java.rmi.UnmarshalException;

/**
 * Call results built through the E1a factories, for the E1c tests that need no sockets. Every
 * measured value is non-zero on purpose, so a 0 in a DTO can only be an invented value.
 */
final class RmiResultFixtures {

    static final String STUB = "jdk.proxy2.$Proxy12";
    static final String LOGIC = NodeEndpointLogic.class.getName();
    static final String DIGEST = "a".repeat(64);

    private RmiResultFixtures() {
    }

    static EndpointReply replyFor(RmiCall call, int nodeId, long lamport) {
        return switch (call.method()) {
            case PING -> new PingReply(nodeId, lamport);
            case NODE_INFO -> new NodeInfo(nodeId, lamport, 1100 + nodeId, "MEDIUM", 4321, "21.0.12.1+1-LTS");
            case ECHO -> new EchoReply(nodeId, lamport, call.text());
            case COMPUTE_HASH -> new HashResult(nodeId, lamport, call.rounds(), NodeEndpointLogic.seedFor(nodeId),
                    DIGEST, 912_345);
        };
    }

    static RmiCall[] everyMethod() {
        return new RmiCall[]{RmiCall.ping(), RmiCall.nodeInfo(), RmiCall.echo("hello"), RmiCall.computeHash(1000)};
    }

    /** Node 1 calls node 2 through RMI: lookup 120 us, round trip {@code roundTripNanos}. */
    static RmiCallResult remoteSuccess(RmiCall call, long roundTripNanos) {
        return RmiCallResult.success(call, 2, 1, CallPath.REMOTE, replyFor(call, 2, 7), 120_000, roundTripNanos,
                74L, 61L, STUB, 5, 8);
    }

    static RmiCallResult remoteSuccess(RmiCall call) {
        return remoteSuccess(call, 840_000);
    }

    /** The same call made directly on node 2's endpoint object: nothing marshalled. */
    static RmiCallResult localSuccess(RmiCall call) {
        return RmiCallResult.success(call, 2, 1, CallPath.LOCAL, replyFor(call, 2, 4), 3_000, 52_000,
                null, null, LOGIC, 3, 5);
    }

    /** Node 2 refused the lookup. */
    static RmiCallResult lookupRefused(RmiCall call) {
        return RmiCallResult.lookupFailure(call, 2, 1, 1_200_000,
                new ConnectException("Connection refused to host: 127.0.0.1", new java.net.ConnectException("refused")),
                300);
    }

    /** The lookup worked, then the reply never came. */
    static RmiCallResult callTimedOut(RmiCall call) {
        return RmiCallResult.callFailure(call, 2, 1, 300_000, 3_000_000_000L, 80, STUB, 6,
                new UnmarshalException("Error unmarshaling return header", new SocketTimeoutException("Read timed out")),
                300);
    }

    /** The connection broke while the reply was read (a pooled connection to a crashed node). */
    static RmiCallResult callConnectionLost(RmiCall call) {
        return RmiCallResult.callFailure(call, 2, 1, 250_000, 900_000, 80, STUB, 6,
                new UnmarshalException("Error unmarshaling return header", new EOFException()), 300);
    }
}
