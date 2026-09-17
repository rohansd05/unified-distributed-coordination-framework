package com.udcf.replication;

/**
 * Wire protocol for replication traffic, carried over TCP.
 *
 * <p>Every message is one line of text:</p>
 * <pre>
 *   TYPE | senderId | lamportTime | payload
 * </pre>
 *
 * <p>TCP is used here rather than the UDP of Experiment 4, and the choice is
 * deliberate. Replication needs the acknowledgement to be real: the primary must know
 * with certainty whether a backup stored the update. TCP's guaranteed, ordered,
 * connection-oriented delivery provides that, and a refused connection to a crashed
 * backup surfaces immediately as an exception rather than as silence.</p>
 */
public enum MessageType {

    /** Primary to backup: store this data item. */
    REPLICATE,

    /** Backup to primary: the item was stored (or rejected as stale). */
    ACK,

    /** Read a key from whichever replica is asked. */
    READ,

    /** Reply to READ, carrying the value that replica currently holds. */
    READ_REPLY,

    /** Dump a replica's entire store, used to compare replicas at the end of a run. */
    DUMP,

    /** Reply to DUMP. */
    DUMP_REPLY
}
