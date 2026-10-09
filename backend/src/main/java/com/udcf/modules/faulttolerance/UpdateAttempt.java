package com.udcf.modules.faulttolerance;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The redirect and retry decisions for one update ({@link UpdateRetryPolicy#begin}).
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Accepted: {@link RetryDecision.Action#DONE}.</li>
 *   <li>Not primary, naming another node: the client follows the redirect at once, unless the
 *       named node is the one that just answered, or a node that already redirected this update
 *       (a redirect loop such as A to B to A). Then, or when no node is named, it discovers the
 *       primary after the retry delay.</li>
 *   <li>Unreachable: discover the primary after the retry delay.</li>
 *   <li>Every send and every discovery uses one attempt. When another one would exceed
 *       {@link UpdateRetryPolicy#maxAttempts()}, the answer is {@link RetryDecision.Action#GIVE_UP},
 *       so no redirect loop can run forever.</li>
 * </ul>
 *
 * <p>Thread safety: <b>not</b> thread safe. One instance belongs to one update on one thread
 * (the update stream's).</p>
 */
public final class UpdateAttempt {

    private final UpdateRetryPolicy policy;
    private final Integer knownPrimaryId;
    private final Set<Integer> redirectedBy = new HashSet<>();

    private int attempts;
    private RetryDecision last;

    UpdateAttempt(UpdateRetryPolicy policy, Integer knownPrimaryId) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        if (knownPrimaryId != null) {
            EpochRules.requireNodeId("knownPrimaryId", knownPrimaryId);
        }
        this.knownPrimaryId = knownPrimaryId;
    }

    /** The first step: send to the known primary, or discover it if none is known. Called once. */
    public RetryDecision first() {
        if (last != null) {
            throw new IllegalStateException("first() was already called");
        }
        return knownPrimaryId != null ? use(RetryDecision.send(knownPrimaryId, 0)) : use(RetryDecision.discover(0));
    }

    /** The next step after the send the previous decision asked for. */
    public RetryDecision after(AttemptOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (last == null || last.action() != RetryDecision.Action.SEND) {
            throw new IllegalStateException("no send is awaiting an outcome (last decision: " + last + ")");
        }
        if (outcome.nodeId() != last.targetNodeId()) {
            throw new IllegalArgumentException("the outcome is from node " + outcome.nodeId()
                    + ", but the update was sent to node " + last.targetNodeId());
        }
        return switch (outcome.kind()) {
            case ACCEPTED -> finish(RetryDecision.done());
            case UNREACHABLE -> use(RetryDecision.discover(policy.retryDelayMillis()));
            case NOT_PRIMARY -> {
                redirectedBy.add(outcome.nodeId());
                Integer hint = outcome.primaryHint();
                yield hint != null && !redirectedBy.contains(hint)
                        ? use(RetryDecision.send(hint, 0))
                        : use(RetryDecision.discover(policy.retryDelayMillis()));
            }
        };
    }

    /** The next step after the discovery the previous decision asked for: the primary found, or empty. */
    public RetryDecision afterDiscovery(Optional<Integer> primaryId) {
        Objects.requireNonNull(primaryId, "primaryId must not be null");
        if (last == null || last.action() != RetryDecision.Action.DISCOVER) {
            throw new IllegalStateException("no discovery is awaiting a result (last decision: " + last + ")");
        }
        return primaryId.isPresent()
                ? use(RetryDecision.send(primaryId.get(), 0))
                : use(RetryDecision.discover(policy.retryDelayMillis()));
    }

    /** Sends and discoveries used so far. */
    public int attempts() {
        return attempts;
    }

    /** The last decision, or null before {@link #first()}. */
    public RetryDecision last() {
        return last;
    }

    private RetryDecision use(RetryDecision next) {
        if (attempts >= policy.maxAttempts()) {
            return finish(RetryDecision.giveUp());
        }
        attempts++;
        last = next;
        return next;
    }

    private RetryDecision finish(RetryDecision decision) {
        last = decision;
        return decision;
    }
}
