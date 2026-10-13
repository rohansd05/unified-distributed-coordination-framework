package com.udcf.modules.mpi;

import com.udcf.modules.rmi.CallStage;

import java.io.Serial;
import java.util.Objects;

/**
 * A transfer failed: where ({@link CallStage#LOOKUP} or {@link CallStage#CALL}), and the original
 * exception as the cause, which {@link CollectiveExecutor} classifies.
 *
 * <p>No dedicated test: a carrier with null checks only (R6); CollectiveExecutorTest uses it.</p>
 */
public class TransferException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    private final CallStage stage;

    public TransferException(CallStage stage, Exception cause) {
        super(Objects.requireNonNull(cause, "cause must not be null").toString(), cause);
        this.stage = Objects.requireNonNull(stage, "stage must not be null");
    }

    public CallStage stage() {
        return stage;
    }

    /** The original exception. */
    public Exception original() {
        return (Exception) getCause();
    }
}
