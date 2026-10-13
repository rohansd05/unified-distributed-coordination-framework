package com.udcf.modules.matrix;

import java.io.Serial;
import java.util.Objects;

/**
 * A node could not compute or hand out a block of C, and says why (step E10b). Thrown by
 * {@link MatrixEndpointLogic} and by {@link NodeExecutor#multiply} for the root's own block. It is a
 * declared exception of {@link MatrixEndpoint}, so RMI carries it back unchanged: the root sees it at
 * stage CALL, classified METHOD_THREW like any exception of the method itself, and reads
 * {@link #reason()}. No cause crosses the wire; the message names the cause's class when there is one.
 * Every message is built here from fixed text, numbers and class names, so it is one line.
 *
 * <p>No dedicated test: a carrier with null checks only (R6); MatrixEndpointLogicTest and
 * RmiMatrixTransportTest cover every reason.</p>
 */
public class MatrixComputeException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Why a node refused or failed.
     *
     * <p>No dedicated test: an enum without behaviour (R6).</p>
     */
    public enum Reason {
        /** A or B is not in the node's collective store: it never arrived, or the store's limit dropped it. */
        INPUT_MISSING,
        /** What is stored does not fit the request (another rank, or lengths that do not fit n). */
        INPUT_MISMATCH,
        /** The node's Experiment 2 executor is not running and could not be started (for example the node is down). */
        EXECUTOR_UNAVAILABLE,
        /** The node's Experiment 2 queue was full (AbortPolicy). */
        EXECUTOR_REJECTED,
        /** The multiply did not finish within compute-timeout-millis, queue wait included. */
        COMPUTE_TIMED_OUT,
        /** The multiply task failed for another reason. */
        COMPUTE_FAILED,
        /** No computed block is kept for that run and rank: never computed, or dropped by result-store-size. */
        BLOCK_MISSING
    }

    private final Reason reason;

    public MatrixComputeException(Reason reason, String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public Reason reason() {
        return reason;
    }
}
