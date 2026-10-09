package com.udcf.modules.faulttolerance;

import com.udcf.modules.replication.WriteResult;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Every update the client was told had succeeded, one entry per key: the ledger data loss is
 * measured against ({@link DataLoss}).
 *
 * <p>Unbounded: it holds every acknowledged key since the last {@link #clear()}, because data
 * loss must check all of them. The module bounds it by bounding the update stream.</p>
 *
 * <p>Ported from the legacy {@code FailoverMetrics.confirmedKeys}, keeping the confirmed
 * version (not just the key) so data loss can tell an older version from the confirmed one.</p>
 *
 * <p>Thread safety: every method is synchronized; none waits or calls out while holding the lock.</p>
 */
public class AcknowledgedLedger {

    private static final Comparator<AcknowledgedUpdate> BY_SEQUENCE =
            Comparator.comparingInt(AcknowledgedUpdate::sequence).thenComparing(AcknowledgedUpdate::key);

    private final Map<String, AcknowledgedUpdate> byKey = new HashMap<>();

    /**
     * Records a write the client was told had succeeded. If the key was acknowledged before,
     * the newer version (by the replication store's last-writer-wins) is kept.
     *
     * @return the entry held for the key after the call
     */
    public synchronized AcknowledgedUpdate record(int sequence, WriteResult result, long acknowledgedAtNanos) {
        AcknowledgedUpdate entry = AcknowledgedUpdate.from(sequence, result, acknowledgedAtNanos);
        return byKey.merge(entry.key(), entry,
                (held, incoming) -> incoming.item().isNewerThan(held.item()) ? incoming : held);
    }

    /** Every entry, detached, in sequence order. */
    public synchronized List<AcknowledgedUpdate> snapshot() {
        return byKey.values().stream().sorted(BY_SEQUENCE).toList();
    }

    public synchronized int size() {
        return byKey.size();
    }

    public synchronized void clear() {
        byKey.clear();
    }

    /** True if {@code key} was acknowledged. */
    public synchronized boolean contains(String key) {
        return byKey.containsKey(Objects.requireNonNull(key, "key must not be null"));
    }
}
