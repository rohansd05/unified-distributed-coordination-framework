package com.udcf.modules.rmi;

import java.rmi.RemoteException;
import java.util.List;
import java.util.Objects;

/**
 * One {@link NodeEndpoint} call to make: the method and its argument.
 *
 * <p>{@code text} is set for {@link RmiMethod#ECHO} only and {@code rounds} for
 * {@link RmiMethod#COMPUTE_HASH} only; every other combination is an
 * {@link IllegalArgumentException}. {@link #validate} checks the configured limits, the same
 * ones the node enforces.</p>
 *
 * @param method the method to call
 * @param text   the echo text, or null
 * @param rounds the hash rounds, or null
 */
public record RmiCall(RmiMethod method, String text, Integer rounds) {

    public RmiCall {
        if (method == null) {
            throw new IllegalArgumentException("method must not be null");
        }
        boolean wantsText = method == RmiMethod.ECHO;
        boolean wantsRounds = method == RmiMethod.COMPUTE_HASH;
        if (wantsText != (text != null)) {
            throw new IllegalArgumentException(wantsText ? "ECHO needs a text" : method + " takes no text");
        }
        if (wantsRounds != (rounds != null)) {
            throw new IllegalArgumentException(wantsRounds ? "COMPUTE_HASH needs rounds" : method + " takes no rounds");
        }
    }

    public static RmiCall ping() {
        return new RmiCall(RmiMethod.PING, null, null);
    }

    public static RmiCall nodeInfo() {
        return new RmiCall(RmiMethod.NODE_INFO, null, null);
    }

    public static RmiCall echo(String text) {
        return new RmiCall(RmiMethod.ECHO, text, null);
    }

    public static RmiCall computeHash(int rounds) {
        return new RmiCall(RmiMethod.COMPUTE_HASH, null, rounds);
    }

    /** Checks the configured limits: rounds 1 to max-hash-rounds, text at most max-echo-length. */
    public void validate(RmiProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        switch (method) {
            case ECHO -> NodeEndpointLogic.requireEchoText(text, properties);
            case COMPUTE_HASH -> NodeEndpointLogic.requireHashRounds(rounds, properties);
            case PING, NODE_INFO -> { }
        }
    }

    /**
     * The argument values in call order, for {@link PayloadSizer}. {@code rounds} is returned as
     * an {@link Integer}, which the sizer writes as the primitive {@code int} RMI sends.
     */
    public List<Object> arguments(CallContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return switch (method) {
            case PING, NODE_INFO -> List.of(context);
            case ECHO -> List.of(context, text);
            case COMPUTE_HASH -> List.of(context, rounds);
        };
    }

    /** The user-visible argument on one line, for example {@code rounds=1000}. */
    public String argumentSummary() {
        return switch (method) {
            case PING, NODE_INFO -> "no arguments";
            case ECHO -> "text=" + DisplayText.quoted(text);
            case COMPUTE_HASH -> "rounds=" + rounds;
        };
    }

    /** Makes this call on {@code endpoint}, a stub or the endpoint object itself. */
    public EndpointReply invokeOn(NodeEndpoint endpoint, CallContext context) throws RemoteException {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        return switch (method) {
            case PING -> endpoint.ping(context);
            case NODE_INFO -> endpoint.nodeInfo(context);
            case ECHO -> endpoint.echo(context, text);
            case COMPUTE_HASH -> endpoint.computeHash(context, rounds);
        };
    }

    /** A reply on one line, for example {@code reply from node 2}. */
    public static String resultSummary(EndpointReply reply) {
        Objects.requireNonNull(reply, "reply must not be null");
        return switch (reply) {
            case PingReply p -> "reply from node " + p.nodeId();
            case NodeInfo n -> "node " + n.nodeId() + ", registry port " + n.registryPort() + ", "
                    + DisplayText.singleLine(n.capacityProfile()) + ", up " + n.uptimeMillis() + " ms, Java "
                    + DisplayText.singleLine(n.javaVersion());
            case EchoReply e -> DisplayText.quoted(e.text());
            case HashResult h -> "digest " + h.digestHex() + " after " + h.rounds() + " rounds";
        };
    }
}
