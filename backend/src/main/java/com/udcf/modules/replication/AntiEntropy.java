package com.udcf.modules.replication;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Anti-entropy: bring a replica up to date by pushing the sender's whole store.
 *
 * <p>The sender does not track which updates a replica missed. It pushes everything, and
 * the replica's own {@link DataStore#apply(DataItem, long)} keeps only what is genuinely
 * newer: items it already holds come back {@link ApplyResult#DUPLICATE}, items it holds a
 * newer version of come back {@link ApplyResult#STALE}. That is why recovery needs no
 * replication log.</p>
 *
 * <p>The fence is the replica's, checked against the <b>sender's</b> epoch for every item:
 * a superseded sender is refused item by item, a sender on a newer epoch raises the
 * replica's epoch with its first item, and old-epoch items from a current sender compete
 * by last-writer-wins, so data written in an earlier term is repaired rather than lost. If
 * the replica's epoch rises above the sender's in the middle of a merge, every later item
 * is {@link ApplyResult#STALE_EPOCH}.</p>
 *
 * <p>Ported from legacy-demos/exp05-replication ({@code ReplicationNode.resync}), as pure
 * logic: the transport (E5b) carries the items, this decides and counts.</p>
 */
public final class AntiEntropy {

    private AntiEntropy() {
    }

    /**
     * Everything the sender holds, sorted by key, taken from a detached snapshot: writes to
     * {@code source} afterwards never change the list and never disturb its iteration.
     *
     * @return an unmodifiable list
     */
    public static List<DataItem> plan(DataStore source) {
        Objects.requireNonNull(source, "source must not be null");
        return List.copyOf(source.snapshot().values());
    }

    /**
     * Applies every pushed item to {@code target} on behalf of a sender on {@code senderEpoch}.
     *
     * <p>The batch is copied and checked before anything is applied: if any item's epoch is
     * above {@code senderEpoch}, nothing is applied.</p>
     *
     * @return the four counts, which always add up to the number pushed
     * @throws IllegalArgumentException if an item's epoch is above {@code senderEpoch}
     */
    public static AntiEntropyResult merge(DataStore target, Collection<DataItem> pushed, long senderEpoch) {
        Objects.requireNonNull(target, "target must not be null");
        List<DataItem> batch = List.copyOf(Objects.requireNonNull(pushed, "pushed must not be null"));
        for (DataItem item : batch) {
            if (item.epoch() > senderEpoch) {
                throw new IllegalArgumentException("item '" + item.key() + "' has epoch " + item.epoch()
                        + ", above the sender's epoch " + senderEpoch + "; nothing was applied");
            }
        }
        int applied = 0;
        int alreadyCurrent = 0;
        int stale = 0;
        int staleEpoch = 0;
        for (DataItem item : batch) {
            switch (target.apply(item, senderEpoch)) {
                case APPLIED -> applied++;
                case DUPLICATE -> alreadyCurrent++;
                case STALE -> stale++;
                case STALE_EPOCH -> staleEpoch++;
            }
        }
        return new AntiEntropyResult(batch.size(), applied, alreadyCurrent, stale, staleEpoch);
    }
}
