package com.udcf.fault;

/**
 * Wire protocol, carried over TCP as one line per message:
 *
 * <pre>
 * TYPE | senderId | lamportTime | epoch | payload
 * </pre>
 *
 * <p>The <b>epoch</b> field is the important one. It is a counter that increases by one
 * every time a new primary is promoted, and it is what prevents a recovered old primary
 * from carrying on as though nothing happened.</p>
 */
public enum MessageType {

    /** Client asks a node to apply a system update. Only a primary may accept it. */
    CLIENT_WRITE,

    /** Primary pushes an applied update out to a backup. */
    REPLICATE,

    /** Backup confirms it stored the update. */
    ACK,

    /** Refusal because the sender's epoch is older than the receiver's. */
    STALE_EPOCH,

    /** Refusal because the node asked is not the primary; carries who is. */
    NOT_PRIMARY,

    /** Backup to primary liveness check. Silence here is what triggers failover. */
    HEARTBEAT,

    /** Reply to a heartbeat. */
    PONG,

    /** A newly promoted primary announcing itself and its new epoch. */
    NEW_PRIMARY,

    /** Ask a node who it thinks the primary is and what epoch it is on. */
    ROLE_QUERY,

    /** Reply to ROLE_QUERY: role, epoch, primary id. */
    ROLE_REPLY,

    /** A recovering node asking the primary for everything it missed. */
    SYNC_REQUEST,

    /** The primary's full state, sent to a recovering node. */
    SYNC_REPLY
}
