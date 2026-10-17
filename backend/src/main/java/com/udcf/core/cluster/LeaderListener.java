package com.udcf.core.cluster;

/**
 * Told about every leader change, in order, on the role provider's dispatcher thread
 * ({@value RoleProvider#DISPATCHER_THREAD}), never on a socket listener or election thread.
 *
 * <p>Must not block for long: every listener shares the one dispatcher. Must not call
 * {@link RoleProvider#ensureLeader()}.</p>
 */
@FunctionalInterface
public interface LeaderListener {

    void onLeaderChanged(LeaderChange change);
}
