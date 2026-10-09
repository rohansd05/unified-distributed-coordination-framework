package com.udcf.modules.faulttolerance;

import com.udcf.modules.replication.DataItem;

import java.util.Locale;

/**
 * One system update as the update client sends it, before any primary stamps it.
 *
 * <p>The stamped version, with the Lamport time, origin and epoch the primary gives it, is the
 * shared replication {@link DataItem}; there is no second store or update type (the legacy
 * {@code SystemUpdate} was the stamped form, and {@code UpdateStore} is the shared
 * {@code DataStore}). Key and value follow {@link DataItem}'s protocol limits.</p>
 *
 * <p>Covered by FaultToleranceRecordsTest.</p>
 *
 * @param sequence the client's sequence number, at least 1
 * @param key      the setting being changed
 * @param value    its new value
 */
public record SystemUpdate(int sequence, String key, String value) {

    public SystemUpdate {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be >= 1, was " + sequence);
        }
        new DataItem(key, value, 0, 1, 1);   // validates key and value against the protocol limits
    }

    /** Update number {@code sequence} of the stream: key {@code setting-0001}, value {@code v1} (legacy format). */
    public static SystemUpdate numbered(int sequence) {
        return new SystemUpdate(sequence, String.format(Locale.ROOT, "setting-%04d", sequence), "v" + sequence);
    }
}
