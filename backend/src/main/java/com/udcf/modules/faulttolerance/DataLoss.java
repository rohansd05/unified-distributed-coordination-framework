package com.udcf.modules.faulttolerance;

import com.udcf.modules.replication.ConsistencyModel;
import com.udcf.modules.replication.DataItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Data loss: the updates the client was told had succeeded that the new primary does not hold.
 *
 * <p>An acknowledged update is lost if the new primary's store lacks its key, or holds the key
 * at an older version than the confirmed one (by the replication store's last-writer-wins,
 * {@link DataItem#isNewerThan}). A newer version is not a loss: the update was superseded, not
 * dropped. This is real, silent loss: the client already moved on believing it was saved.</p>
 *
 * <p>Ported from the legacy demo's data integrity check, which compared keys only.</p>
 *
 * <p>Thread safety: stateless. The caller passes detached snapshots.</p>
 */
public final class DataLoss {

    private DataLoss() {
    }

    /**
     * @param acknowledged    the ledger's snapshot ({@link AcknowledgedLedger#snapshot()}); one entry per key
     * @param newPrimaryStore the new primary's store snapshot, or null if there is no primary to check
     */
    public static DataLossReport measure(List<AcknowledgedUpdate> acknowledged, Map<String, DataItem> newPrimaryStore) {
        Objects.requireNonNull(acknowledged, "acknowledged must not be null");
        Set<String> keys = new HashSet<>();
        long simulatedDelay = 0;
        for (AcknowledgedUpdate entry : acknowledged) {
            Objects.requireNonNull(entry, "acknowledged must not contain null");
            if (!keys.add(entry.key())) {
                throw new IllegalArgumentException("key " + entry.key() + " is acknowledged twice");
            }
            simulatedDelay = Math.max(simulatedDelay, entry.simulatedDelayMillis());
        }
        boolean simulated = simulatedDelay > 0;
        if (acknowledged.isEmpty()) {
            return new DataLossReport(0, null, List.of(), null, null, false, 0,
                    DataLossReport.NotAssessed.NOTHING_ACKNOWLEDGED);
        }
        if (newPrimaryStore == null) {
            return new DataLossReport(acknowledged.size(), null, List.of(), null, null, simulated, simulatedDelay,
                    DataLossReport.NotAssessed.NO_PRIMARY_STORE);
        }
        List<AcknowledgedUpdate> ordered = new ArrayList<>(acknowledged);
        ordered.sort(Comparator.comparingInt(AcknowledgedUpdate::sequence).thenComparing(AcknowledgedUpdate::key));
        List<String> lostKeys = new ArrayList<>();
        int lostSynchronous = 0;
        int lostAsynchronous = 0;
        for (AcknowledgedUpdate entry : ordered) {
            DataItem held = newPrimaryStore.get(entry.key());
            if (held == null || entry.item().isNewerThan(held)) {
                lostKeys.add(entry.key());
                if (entry.model() == ConsistencyModel.SYNCHRONOUS) {
                    lostSynchronous++;
                } else {
                    lostAsynchronous++;
                }
            }
        }
        return new DataLossReport(acknowledged.size(), lostKeys.size(), lostKeys, lostSynchronous, lostAsynchronous,
                simulated, simulatedDelay, null);
    }
}
