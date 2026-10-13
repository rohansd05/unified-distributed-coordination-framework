package com.udcf.modules.rmi;

import java.util.Objects;

/**
 * What one failed remote exchange looked like, by the same rules as {@link RmiCallResult}: the
 * stage, the kind ({@link RemoteFailureClassifier}), the exception class, the exception's own
 * message (single-line and cut, never a stack trace) and the innermost cause class.
 *
 * <p>Added for Experiment 9 (step E9a), so its transfers report failures exactly as Experiment 1's
 * calls do without copying the cut and cause rules. A failure the caller found itself, with no
 * exception (for example a reply that does not match its request), is built with the
 * constructor and has no exception or cause class.</p>
 *
 * @param stage             where the exchange failed
 * @param kind              the classified kind
 * @param failureClass      the exception's class; null when no exception was thrown
 * @param failureMessage    single-line, at most the configured length; null if there was none
 * @param failureCauseClass the innermost cause's class; null if there is none
 */
public record RemoteFailureDetail(CallStage stage, RemoteFailureKind kind, String failureClass, String failureMessage,
                                  String failureCauseClass) {

    public RemoteFailureDetail {
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        if (failureMessage != null && !DisplayText.isSingleLine(failureMessage)) {
            throw new IllegalArgumentException("failureMessage must be a single line without control characters");
        }
    }

    /**
     * The detail of {@code failure} at {@code stage}.
     *
     * @param maxMessageLength the longest message kept; a longer one is cut (at least 1)
     */
    public static RemoteFailureDetail of(Throwable failure, CallStage stage, int maxMessageLength) {
        Objects.requireNonNull(failure, "failure must not be null");
        Objects.requireNonNull(stage, "stage must not be null");
        if (maxMessageLength < 1) {
            throw new IllegalArgumentException("maxMessageLength must be >= 1, was " + maxMessageLength);
        }
        Throwable cause = RemoteFailureClassifier.innermostCause(failure);
        return new RemoteFailureDetail(stage, RemoteFailureClassifier.classify(failure, stage), failure.getClass().getName(),
                RmiCallResult.failureMessage(failure, maxMessageLength), cause == null ? null : cause.getClass().getName());
    }

    /**
     * A failure the caller found itself, with no exception (for example a reply that does not match
     * its request): no exception or cause class; the message single-line and cut like any other.
     */
    public static RemoteFailureDetail observed(CallStage stage, RemoteFailureKind kind, String message, int maxMessageLength) {
        Objects.requireNonNull(message, "message must not be null");
        if (maxMessageLength < 1) {
            throw new IllegalArgumentException("maxMessageLength must be >= 1, was " + maxMessageLength);
        }
        return new RemoteFailureDetail(stage, kind, null, DisplayText.singleLine(message, maxMessageLength), null);
    }
}
