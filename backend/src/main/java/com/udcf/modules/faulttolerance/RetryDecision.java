package com.udcf.modules.faulttolerance;

import java.util.Objects;

/**
 * What the update client does next ({@link UpdateAttempt}).
 *
 * <p>Covered by FaultToleranceRecordsTest (validation) and UpdateRetryPolicyTest.</p>
 *
 * @param action       what to do
 * @param targetNodeId for {@link Action#SEND} only: the node to send to
 * @param delayMillis  how long to wait first, at least 0 (the caller waits; nothing here sleeps)
 */
public record RetryDecision(Action action, Integer targetNodeId, long delayMillis) {

    /** The next step of one update. */
    public enum Action {

        /** Send the update to {@code targetNodeId}. */
        SEND,

        /** Ask the nodes who the primary is (role queries), then report it to {@link UpdateAttempt#afterDiscovery}. */
        DISCOVER,

        /** The update was confirmed. */
        DONE,

        /** The attempts are used up; the update was never confirmed. */
        GIVE_UP
    }

    public RetryDecision {
        Objects.requireNonNull(action, "action must not be null");
        if ((action == Action.SEND) != (targetNodeId != null)) {
            throw new IllegalArgumentException("a target is given exactly for a send");
        }
        if (targetNodeId != null) {
            EpochRules.requireNodeId("targetNodeId", targetNodeId);
        }
        if (delayMillis < 0) {
            throw new IllegalArgumentException("delayMillis must be >= 0, was " + delayMillis);
        }
        if ((action == Action.DONE || action == Action.GIVE_UP) && delayMillis != 0) {
            throw new IllegalArgumentException("a final decision has no delay");
        }
    }

    public static RetryDecision send(int targetNodeId, long delayMillis) {
        return new RetryDecision(Action.SEND, targetNodeId, delayMillis);
    }

    public static RetryDecision discover(long delayMillis) {
        return new RetryDecision(Action.DISCOVER, null, delayMillis);
    }

    public static RetryDecision done() {
        return new RetryDecision(Action.DONE, null, 0);
    }

    public static RetryDecision giveUp() {
        return new RetryDecision(Action.GIVE_UP, null, 0);
    }

    /** True for {@link Action#DONE} and {@link Action#GIVE_UP}. */
    public boolean isFinal() {
        return action == Action.DONE || action == Action.GIVE_UP;
    }
}
