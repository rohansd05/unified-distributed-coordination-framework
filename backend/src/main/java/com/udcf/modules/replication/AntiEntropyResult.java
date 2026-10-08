package com.udcf.modules.replication;

/**
 * Outcome of one anti-entropy merge into one replica.
 *
 * <p>No dedicated test: a record whose only behaviour, the count check, is covered by
 * {@code AntiEntropyTest} (R6).</p>
 *
 * @param pushed         items the sender pushed
 * @param applied        items the replica was missing or held an older version of, now stored
 * @param alreadyCurrent items the replica already held exactly ({@link ApplyResult#DUPLICATE})
 * @param stale          items the replica held a newer version of ({@link ApplyResult#STALE})
 * @param staleEpoch     items refused because the sender was superseded ({@link ApplyResult#STALE_EPOCH})
 */
public record AntiEntropyResult(int pushed, int applied, int alreadyCurrent, int stale, int staleEpoch) {

    public AntiEntropyResult {
        if (pushed < 0 || applied < 0 || alreadyCurrent < 0 || stale < 0 || staleEpoch < 0) {
            throw new IllegalArgumentException("counts must be >= 0");
        }
        if ((long) applied + alreadyCurrent + stale + staleEpoch != pushed) {
            throw new IllegalArgumentException("applied + alreadyCurrent + stale + staleEpoch ("
                    + ((long) applied + alreadyCurrent + stale + staleEpoch) + ") must equal pushed (" + pushed + ")");
        }
    }
}
