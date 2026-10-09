package com.udcf.modules.mapreduce;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * A run's crash plan: crashes the chosen worker right after the first task is sent to it.
 *
 * <p><b>How.</b> {@link TcpTaskTransport} publishes {@code TASK_SENT} and only then asks its
 * port resolver for the target's port. {@link #resolver} wraps that resolver: on the first
 * lookup of the chosen worker it calls {@code crash} synchronously, then returns the port. The
 * attempt then finds the port closed, {@code TASK_ATTEMPT_FAILED} follows, and the pipeline
 * retries the task on another worker. The order {@code TASK_SENT}, crash, refused connection
 * is therefore fixed, and the retry always happens; a small task would otherwise finish long
 * before a crash ordered from another thread.</p>
 *
 * <p><b>Threads.</b> The crash runs on the pipeline's own attempt thread, never on an HTTP
 * thread or the event dispatcher, and nothing waits or polls. {@link #wrap} records, per
 * thread, whether the attempt is a map or a reduce task, so the report can say which kind of
 * task the crash interrupted. An {@link AtomicBoolean} lets exactly one attempt trigger the
 * crash, however many run at once.</p>
 *
 * <p>The crash is a real crash of the node on every protocol (through {@code Cluster.crash},
 * R10). It is injected by the module as part of the run, never automatic.</p>
 */
final class WorkerCrashTrigger {

    /** Told once, after the crash call, on the attempt thread that triggered it. */
    @FunctionalInterface
    interface Listener {
        void crashTriggered(int workerId, TaskType taskType, boolean nodeCrashed);
    }

    private final Integer workerId;
    private final IntPredicate crash;
    private final Listener listener;
    private final AtomicBoolean fired = new AtomicBoolean();
    private final ThreadLocal<TaskType> currentTaskType = new ThreadLocal<>();
    private volatile TaskType taskType;
    private volatile Boolean nodeCrashed;

    /**
     * @param workerId the node to crash, or {@code null} for a run without a crash
     * @param crash    performs the crash; returns true if it crashed the node, false if it was
     *                 already down
     * @param listener told once after the crash call
     */
    WorkerCrashTrigger(Integer workerId, IntPredicate crash, Listener listener) {
        this.workerId = workerId;
        this.crash = Objects.requireNonNull(crash, "crash must not be null");
        this.listener = Objects.requireNonNull(listener, "listener must not be null");
    }

    /** Wraps the transport so the resolver knows which kind of task each attempt carries. */
    TaskTransport wrap(TaskTransport delegate) {
        Objects.requireNonNull(delegate, "delegate must not be null");
        return (targetNodeId, type, jobName, payload) -> {
            currentTaskType.set(type);
            try {
                return delegate.executeTask(targetNodeId, type, jobName, payload);
            } finally {
                currentTaskType.remove();
            }
        };
    }

    /** Wraps the port resolver given to {@link TcpTaskTransport} (see the class description). */
    IntUnaryOperator resolver(IntUnaryOperator ports) {
        Objects.requireNonNull(ports, "ports must not be null");
        return nodeId -> {
            if (workerId != null && nodeId == workerId && fired.compareAndSet(false, true)) {
                TaskType type = currentTaskType.get();
                boolean crashed = crash.test(nodeId);
                taskType = type;
                nodeCrashed = crashed;
                listener.crashTriggered(nodeId, type, crashed);
            }
            return ports.applyAsInt(nodeId);
        };
    }

    /** The node to crash, or {@code null} for a run without a crash. */
    Integer workerId() {
        return workerId;
    }

    /** True once a task was sent to the chosen worker and the crash was carried out. */
    boolean triggered() {
        return nodeCrashed != null;
    }

    /** Whether the crash call crashed the node; {@code null} while not triggered. */
    Boolean nodeCrashed() {
        return nodeCrashed;
    }

    /** The kind of the task whose sending triggered the crash; {@code null} while not triggered. */
    TaskType taskType() {
        return taskType;
    }
}
