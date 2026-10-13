package com.udcf.modules.matrix;

import com.udcf.core.clock.LamportClock;
import com.udcf.modules.mpi.CollectiveEndpointLogic;
import com.udcf.modules.mpi.CollectiveTransport;
import com.udcf.modules.mpi.Contribution;
import com.udcf.modules.mpi.MpiProperties;
import com.udcf.modules.mpi.ReceiveReceipt;
import com.udcf.modules.mpi.TransferException;
import com.udcf.modules.rmi.CallContext;
import com.udcf.modules.rmi.CallStage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * A cluster for the runner tests, with no network: each node is a real {@link CollectiveEndpointLogic}
 * and a real {@link MatrixEndpointLogic} on its own Lamport clock and its own node clock (a stepping
 * clock, so a node's compute time is exactly one step), so replies are what a node really computes. A node
 * can be scripted to fail a step (a {@link TransferException} with a stage), to run a hook first, to use
 * another executor, or to have its reply rewritten. Every call is logged. Thread safe: the root calls
 * several nodes at once.
 */
final class InMemoryMatrixNodes implements CollectiveTransport, MatrixTransport {

    /** One node's compute time: each read of a node clock advances it by this much. */
    static final long NODE_STEP = 500;

    /** Runs a task at once on the calling thread. */
    static final NodeExecutor DIRECT = task -> {
        try {
            return CompletableFuture.completedFuture(task.call());
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    };

    /** A step of a node. */
    enum Step { DELIVER, COMPUTE, FETCH }

    /** One call the root made. */
    record Call(Step step, int nodeId, long id, int rank) {
    }

    /** A hook that may throw. */
    interface Hook {
        void run() throws Exception;
    }

    private final Map<Integer, LamportClock> clocks = new ConcurrentHashMap<>();
    private final Map<Integer, CollectiveEndpointLogic> collectives = new ConcurrentHashMap<>();
    private final Map<Integer, MatrixEndpointLogic> matrices = new ConcurrentHashMap<>();
    private final Map<Integer, NodeExecutor> executors = new ConcurrentHashMap<>();
    private final Map<String, TransferException> failures = new ConcurrentHashMap<>();
    private final Map<String, Hook> hooks = new ConcurrentHashMap<>();
    private final Map<String, UnaryOperator<Object>> rewrites = new ConcurrentHashMap<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    InMemoryMatrixNodes(MpiProperties mpi, MatrixRunProperties run, int... nodeIds) {
        for (int id : nodeIds) {
            LamportClock clock = new LamportClock();
            CollectiveEndpointLogic collective = new CollectiveEndpointLogic(id, clock, mpi);
            AtomicLong nodeTime = new AtomicLong();
            clocks.put(id, clock);
            collectives.put(id, collective);
            matrices.put(id, new MatrixEndpointLogic(id, clock, () -> Optional.of(collective),
                    task -> executor(id).submit(task), run, () -> nodeTime.getAndAdd(NODE_STEP)));
        }
    }

    InMemoryMatrixNodes failing(Step step, int nodeId, CallStage stage, Exception failure) {
        failures.put(step + "/" + nodeId, new TransferException(stage, failure));
        return this;
    }

    InMemoryMatrixNodes before(Step step, int nodeId, Hook hook) {
        hooks.put(step + "/" + nodeId, hook);
        return this;
    }

    InMemoryMatrixNodes rewriting(Step step, int nodeId, UnaryOperator<Object> rewrite) {
        rewrites.put(step + "/" + nodeId, rewrite);
        return this;
    }

    InMemoryMatrixNodes executing(int nodeId, NodeExecutor executor) {
        executors.put(nodeId, executor);
        return this;
    }

    /** Node {@code nodeId}'s executor: the scripted one, else {@link #DIRECT}. */
    NodeExecutor executor(int nodeId) {
        return executors.getOrDefault(nodeId, DIRECT);
    }

    LamportClock clock(int nodeId) {
        return clocks.get(nodeId);
    }

    CollectiveEndpointLogic collective(int nodeId) {
        return collectives.get(nodeId);
    }

    MatrixEndpointLogic matrix(int nodeId) {
        return matrices.get(nodeId);
    }

    List<Call> calls() {
        return calls;
    }

    List<Call> calls(Step step) {
        return calls.stream().filter(call -> call.step() == step).toList();
    }

    @Override
    public ReceiveReceipt deliver(int targetNodeId, CallContext context, long collectiveId, int rank, double[] values)
            throws TransferException {
        calls.add(new Call(Step.DELIVER, targetNodeId, collectiveId, rank));
        prepare(Step.DELIVER, targetNodeId);
        return (ReceiveReceipt) rewrite(Step.DELIVER, targetNodeId,
                collectives.get(targetNodeId).receive(context, collectiveId, rank, values));
    }

    @Override
    public Contribution collect(int targetNodeId, CallContext context, long collectiveId, int rank, int count, long seed) {
        throw new UnsupportedOperationException("a matrix run never gathers through Experiment 9");
    }

    @Override
    public ComputeAck compute(int targetNodeId, CallContext context, long runId, int rank, int n, long scatterId,
                              long broadcastId) throws TransferException {
        calls.add(new Call(Step.COMPUTE, targetNodeId, runId, rank));
        prepare(Step.COMPUTE, targetNodeId);
        try {
            return (ComputeAck) rewrite(Step.COMPUTE, targetNodeId,
                    matrices.get(targetNodeId).computeBlock(context, runId, rank, n, scatterId, broadcastId));
        } catch (MatrixComputeException | RuntimeException e) {
            throw new TransferException(CallStage.CALL, e);   // as RMI rethrows the method's own exception
        }
    }

    @Override
    public BlockReply fetch(int targetNodeId, CallContext context, long runId, int rank) throws TransferException {
        calls.add(new Call(Step.FETCH, targetNodeId, runId, rank));
        prepare(Step.FETCH, targetNodeId);
        try {
            return (BlockReply) rewrite(Step.FETCH, targetNodeId, matrices.get(targetNodeId).fetchBlock(context, runId, rank));
        } catch (MatrixComputeException | RuntimeException e) {
            throw new TransferException(CallStage.CALL, e);
        }
    }

    private void prepare(Step step, int nodeId) throws TransferException {
        Hook hook = hooks.get(step + "/" + nodeId);
        if (hook != null) {
            try {
                hook.run();
            } catch (Exception e) {
                throw new IllegalStateException("hook failed", e);
            }
        }
        TransferException failure = failures.get(step + "/" + nodeId);
        if (failure != null) {
            throw failure;
        }
    }

    private Object rewrite(Step step, int nodeId, Object reply) {
        UnaryOperator<Object> rewrite = rewrites.get(step + "/" + nodeId);
        return rewrite == null ? reply : rewrite.apply(reply);
    }
}
