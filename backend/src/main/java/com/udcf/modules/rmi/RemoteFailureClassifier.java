package com.udcf.modules.rmi;

import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.rmi.ConnectIOException;
import java.rmi.MarshalException;
import java.rmi.NoSuchObjectException;
import java.rmi.NotBoundException;
import java.rmi.ServerError;
import java.rmi.ServerException;
import java.rmi.UnmarshalException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * Maps what a failed remote call threw to a {@link RemoteFailureKind}. Pure: it only inspects
 * the exception and its causes.
 *
 * <p>Checked in this order, first match wins:</p>
 * <ol>
 *   <li>A {@link ServerException} whose cause is an {@link UnmarshalException}:
 *       {@code ARGUMENTS_REJECTED}. The node could not read the call's arguments (for example its
 *       serial filter refused them), so the method never ran.</li>
 *   <li>Any other {@link ServerException}, or a {@link ServerError}: the method ran on the node
 *       and threw, so {@code METHOD_THREW}, whatever its cause (a node making its own remote call
 *       may have timed out, but this call did reach it).</li>
 *   <li>A {@link SocketTimeoutException} anywhere in the cause chain: {@code TIMED_OUT}. RMI
 *       wraps a read timeout in an {@link UnmarshalException} and a connect timeout in a
 *       {@link ConnectIOException}.</li>
 *   <li>{@link java.rmi.ConnectException} or {@link java.net.ConnectException}:
 *       {@code CONNECTION_REFUSED}.</li>
 *   <li>{@link ConnectIOException}: {@code CONNECTION_FAILED}.</li>
 *   <li>{@link NoSuchObjectException}: {@code NO_SUCH_OBJECT}.</li>
 *   <li>{@link NotBoundException}: {@code NOT_BOUND}.</li>
 *   <li>An {@link UnmarshalException} or {@link MarshalException} whose cause is an
 *       {@link EOFException} or a {@link SocketException} that is not a
 *       {@link java.net.ConnectException}: {@code CONNECTION_LOST}. Measured in step E1b: RMI
 *       reuses a pooled connection without checking it if it was used less than about 5 ms
 *       before, so a call right after the node crashed (or crashed and recovered) fails while
 *       reading the reply, with an {@link EOFException} on Linux and a {@link SocketException}
 *       ("An established connection was aborted ...") on Windows.</li>
 *   <li>{@link MarshalException}: {@code MARSHAL_FAILED}.</li>
 *   <li>{@link UnmarshalException}: {@code UNMARSHAL_FAILED}.</li>
 *   <li>Any other {@link java.rmi.RemoteException}: {@code OTHER}.</li>
 *   <li>Anything else at stage {@link CallStage#CALL}: {@code METHOD_THREW}. Since JDK 1.2, RMI
 *       rethrows an exception the remote method threw (for example an
 *       {@link IllegalArgumentException}) to the caller as itself. At
 *       {@link CallStage#LOOKUP}: {@code OTHER}.</li>
 * </ol>
 */
public final class RemoteFailureClassifier {

    private RemoteFailureClassifier() {
    }

    public static RemoteFailureKind classify(Throwable failure, CallStage stage) {
        Objects.requireNonNull(failure, "failure must not be null");
        Objects.requireNonNull(stage, "stage must not be null");
        if (failure instanceof ServerException && failure.getCause() instanceof UnmarshalException) {
            return RemoteFailureKind.ARGUMENTS_REJECTED;
        }
        if (failure instanceof ServerException || failure instanceof ServerError) {
            return RemoteFailureKind.METHOD_THREW;
        }
        if (hasCause(failure, SocketTimeoutException.class)) {
            return RemoteFailureKind.TIMED_OUT;
        }
        if (failure instanceof java.rmi.ConnectException || failure instanceof java.net.ConnectException) {
            return RemoteFailureKind.CONNECTION_REFUSED;
        }
        if (failure instanceof ConnectIOException) {
            return RemoteFailureKind.CONNECTION_FAILED;
        }
        if (failure instanceof NoSuchObjectException) {
            return RemoteFailureKind.NO_SUCH_OBJECT;
        }
        if (failure instanceof NotBoundException) {
            return RemoteFailureKind.NOT_BOUND;
        }
        if ((failure instanceof UnmarshalException || failure instanceof MarshalException)
                && isConnectionBreak(failure.getCause())) {
            return RemoteFailureKind.CONNECTION_LOST;
        }
        if (failure instanceof MarshalException) {
            return RemoteFailureKind.MARSHAL_FAILED;
        }
        if (failure instanceof UnmarshalException) {
            return RemoteFailureKind.UNMARSHAL_FAILED;
        }
        if (failure instanceof java.rmi.RemoteException) {
            return RemoteFailureKind.OTHER;
        }
        return stage == CallStage.CALL ? RemoteFailureKind.METHOD_THREW : RemoteFailureKind.OTHER;
    }

    /** The innermost cause of {@code failure}, or null if it has none. Safe on a cyclic chain. */
    static Throwable innermostCause(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        Throwable innermost = null;
        while (current.getCause() != null && seen.add(current)) {
            innermost = current.getCause();
            current = innermost;
        }
        return innermost;
    }

    /** An end of stream or a broken socket, but not a refused connection. */
    private static boolean isConnectionBreak(Throwable cause) {
        return cause instanceof EOFException
                || (cause instanceof SocketException && !(cause instanceof java.net.ConnectException));
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
