package com.udcf.modules.mpi;

import com.udcf.core.clock.LamportClock;
import com.udcf.modules.rmi.CallContext;
import com.udcf.modules.rmi.CallStage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * A transport for tests, with no network: each node is a real {@link CollectiveEndpointLogic} on its
 * own Lamport clock, so replies are what a node really computes. A node can be scripted to fail
 * (as a {@link TransferException} with a stage) or to have its reply rewritten (to a wrong node,
 * collective, rank or length, or to null). Every request is recorded in order.
 */
final class ScriptedCollectiveTransport implements CollectiveTransport {

    /** One request the executor made. */
    record Sent(String kind, int targetNodeId, CallContext context, long collectiveId, int rank, int length) {
    }

    private final Map<Integer, CollectiveEndpointLogic> nodes = new LinkedHashMap<>();
    private final Map<Integer, LamportClock> clocks = new LinkedHashMap<>();
    private final Map<Integer, TransferException> failures = new HashMap<>();
    private final Map<Integer, UnaryOperator<Object>> rewrites = new HashMap<>();
    private final List<Sent> sent = new ArrayList<>();

    ScriptedCollectiveTransport(MpiProperties properties, int... nodeIds) {
        for (int id : nodeIds) {
            LamportClock clock = new LamportClock();
            clocks.put(id, clock);
            nodes.put(id, new CollectiveEndpointLogic(id, clock, properties));
        }
    }

    /** Node {@code nodeId} fails every request at {@code stage} with {@code failure}. */
    ScriptedCollectiveTransport failing(int nodeId, CallStage stage, Exception failure) {
        failures.put(nodeId, new TransferException(stage, failure));
        return this;
    }

    /** Node {@code nodeId}'s real reply is passed through {@code rewrite} (which may return null). */
    ScriptedCollectiveTransport rewriting(int nodeId, UnaryOperator<Object> rewrite) {
        rewrites.put(nodeId, rewrite);
        return this;
    }

    List<Sent> sent() {
        return sent;
    }

    CollectiveEndpointLogic node(int nodeId) {
        return nodes.get(nodeId);
    }

    LamportClock clock(int nodeId) {
        return clocks.get(nodeId);
    }

    @Override
    public ReceiveReceipt deliver(int targetNodeId, CallContext context, long collectiveId, int rank, double[] values)
            throws TransferException {
        sent.add(new Sent("deliver", targetNodeId, context, collectiveId, rank, values.length));
        throwIfScripted(targetNodeId);
        return (ReceiveReceipt) rewrite(targetNodeId, nodes.get(targetNodeId).receive(context, collectiveId, rank, values));
    }

    @Override
    public Contribution collect(int targetNodeId, CallContext context, long collectiveId, int rank, int count, long seed)
            throws TransferException {
        sent.add(new Sent("collect", targetNodeId, context, collectiveId, rank, count));
        throwIfScripted(targetNodeId);
        return (Contribution) rewrite(targetNodeId, nodes.get(targetNodeId).contribute(context, collectiveId, rank, count, seed));
    }

    private void throwIfScripted(int targetNodeId) throws TransferException {
        TransferException failure = failures.get(targetNodeId);
        if (failure != null) {
            throw failure;
        }
    }

    private Object rewrite(int targetNodeId, Object reply) {
        UnaryOperator<Object> rewrite = rewrites.get(targetNodeId);
        return rewrite == null ? reply : rewrite.apply(reply);
    }
}
