package com.udcf.modules.replication;

/**
 * How one push of one item to one backup ended.
 *
 * <p>No dedicated test: an enum without behaviour (R6).</p>
 */
public enum PushStatus {

    /** The backup replied; its {@link ApplyResult} and the measured round trip are known. */
    ACKED,

    /**
     * The backup could not be reached or did not answer properly: connection refused (a
     * crashed backup), timeout (a silent one), closed without a reply, or an ERROR reply.
     * Counted as a backup failure in {@link ReplicationStats}; never a stale result.
     */
    FAILED,

    /**
     * The sender went down before the push was sent (for example a pending asynchronous push
     * dropped by a crash). Nothing reached the backup. Not a backup failure.
     */
    NOT_SENT,

    /**
     * The sender went down while the push was in flight, so no reply was received; the
     * backup may or may not have applied it. Not a backup failure.
     */
    ABANDONED
}
