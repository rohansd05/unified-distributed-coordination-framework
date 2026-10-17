package com.udcf.core.cluster;

/**
 * One announced change of the cluster leader, as {@code LEADER_CHANGED} publishes it.
 *
 * <p>The leader only ever comes from Experiment 4's election; {@code cause} says why it changed.
 * Listeners should treat it as a hint and re-read {@link RoleProvider#leaderId()}, which is
 * always current.</p>
 *
 * <p>No dedicated test: a record; RoleProviderTest checks what it carries.</p>
 *
 * @param leaderId         the new leader, or null if there is none
 * @param previousLeaderId the leader before, or null
 * @param cause            why it changed
 */
public record LeaderChange(Integer leaderId, Integer previousLeaderId, Cause cause) {

    /** Why the leader changed. */
    public enum Cause {
        /** Every live node agreed on a new coordinator in an election. */
        ELECTED,
        /** The leader's node crashed; an election follows. */
        LEADER_CRASHED,
        /** The leader was removed without a crash (a reset). */
        CLEARED
    }
}
