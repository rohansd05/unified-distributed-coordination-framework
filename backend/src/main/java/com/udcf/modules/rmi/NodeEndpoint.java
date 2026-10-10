package com.udcf.modules.rmi;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * The remote contract every node exports on its RMI registry (port {@code 110k}, step E1b), for
 * Experiment 1 (docs/HANDOFF.md Section 7).
 *
 * <p><b>Lamport time (link L4).</b> RMI has no per-call header for application data, so every
 * method takes the caller's {@link CallContext} as its first argument and every reply carries the
 * node's id and Lamport time ({@link EndpointReply}). The node applies the receive rule
 * {@code max(local, received) + 1} to the context, and the caller applies it to the reply.</p>
 *
 * <p><b>What crosses the wire.</b> Arguments: {@link CallContext}, a {@link String} and a
 * primitive {@code int}. Replies: {@link PingReply}, {@link NodeInfo}, {@link EchoReply} and
 * {@link HashResult}. No enum and no collection crosses (the allowed set for E1b's serial filter
 * is in docs/tracks/track-c-jai.md).</p>
 *
 * <p>No dedicated test: an interface without behaviour (R6). {@link NodeEndpointLogic} is the
 * implementation.</p>
 */
public interface NodeEndpoint extends Remote {

    /** The name every node binds this endpoint under in its own registry. */
    String BOUND_NAME = "NodeEndpoint";

    /** Answers at once: proves the node is reachable. */
    PingReply ping(CallContext context) throws RemoteException;

    /** The node's id, registry port, capacity profile, uptime, Lamport time and Java version. */
    NodeInfo nodeInfo(CallContext context) throws RemoteException;

    /** Returns {@code text} unchanged. */
    EchoReply echo(CallContext context, String text) throws RemoteException;

    /** Chained SHA-256 over the node's seed, {@code rounds} times: real work done on the node. */
    HashResult computeHash(CallContext context, int rounds) throws RemoteException;
}
