package com.udcf.modules.election;

/**
 * What opened an election round.
 *
 * <ul>
 *   <li>{@code MANUAL}: a user started Bully or Ring from a node.</li>
 *   <li>{@code LEADER_FAILURE}: a node's failure detector suspected the leader it knows, and it
 *       started Bully on its own (link L2).</li>
 *   <li>{@code RECOVERY}: a recovered node started Bully, as E4a's Bully does on recovery.</li>
 *   <li>{@code REQUESTED}: another module asked the role provider for a leader while there was
 *       none ({@code RoleProvider.ensureLeader}); Bully runs from the lowest live node.</li>
 * </ul>
 *
 * <p>No dedicated test: a plain enum with no behaviour.</p>
 */
public enum RoundTrigger {
    MANUAL,
    LEADER_FAILURE,
    RECOVERY,
    REQUESTED
}
