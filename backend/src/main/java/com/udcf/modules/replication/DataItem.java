package com.udcf.modules.replication;

/**
 * One versioned value in the replicated store.
 *
 * <p>The value alone is not enough to replicate safely. Each item also carries the epoch
 * of the primary that accepted it, the Lamport timestamp and the id of the node that
 * created it, and those let any replica decide, on its own and without coordination, which
 * of two competing updates to the same key is the newer one.</p>
 *
 * <p><b>Protocol limits.</b> A key is 1 to {@link #MAX_KEY_LENGTH} characters and not
 * blank; a value is 0 to {@link #MAX_VALUE_LENGTH} characters. Neither may contain an ISO
 * control character (which includes {@code \n}, {@code \r}, {@code \t} and U+0085) or the
 * line and paragraph separators U+2028 and U+2029. The separators {@code ';'} and
 * {@code '~'} the legacy wire format used <b>are</b> allowed, so the TCP transport (E5b)
 * must encode items safely (escaping or Base64) rather than splitting on them. Every
 * violation, including a null key or value, is an {@link IllegalArgumentException}.</p>
 *
 * <p>Ported from legacy-demos/exp05-replication. Differences: the epoch (needed by
 * Experiment 8, so it exists from the start); input validation; no wire encoding here
 * (legacy {@code decode} split on {@code ';'} and corrupted any value containing it).</p>
 *
 * @param key         the logical name of the data item
 * @param value       the stored value
 * @param lamportTime the writer's logical clock at the moment of the write, at least 0
 * @param originNode  the id of the node that created this version, at least 1
 * @param epoch       the term of the primary that accepted the write, at least 1
 */
public record DataItem(String key, String value, long lamportTime, int originNode, long epoch) {

    /** Longest key, in characters (protocol limit). */
    public static final int MAX_KEY_LENGTH = 64;

    /** Longest value, in characters (protocol limit). */
    public static final int MAX_VALUE_LENGTH = 1024;

    public DataItem {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("key must be at most " + MAX_KEY_LENGTH
                    + " characters, was " + key.length());
        }
        requireNoControlCharacters("key", key);
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("value must be at most " + MAX_VALUE_LENGTH
                    + " characters, was " + value.length());
        }
        requireNoControlCharacters("value", value);
        if (lamportTime < 0) {
            throw new IllegalArgumentException("lamportTime must be >= 0, was " + lamportTime);
        }
        if (originNode < 1) {
            throw new IllegalArgumentException("originNode must be >= 1, was " + originNode);
        }
        if (epoch < 1) {
            throw new IllegalArgumentException("epoch must be >= 1, was " + epoch);
        }
    }

    /**
     * Last-writer-wins comparison on (epoch, lamportTime, originNode).
     *
     * <p>A later epoch always wins: a write accepted by a newer primary supersedes anything
     * from an older term. Within an epoch the Lamport timestamp decides, and the node id
     * breaks ties so every replica reaches the same verdict. Wall-clock time is
     * deliberately not used: two nodes' system clocks can disagree, so a wall-clock
     * comparison could silently let a stale update overwrite a newer one.</p>
     *
     * <p>The value is not compared, so an item is never newer than itself or than another
     * item with the same version.</p>
     *
     * @param other the item currently held, or null if the key is absent (always older)
     */
    public boolean isNewerThan(DataItem other) {
        if (other == null) {
            return true;
        }
        if (epoch != other.epoch) {
            return epoch > other.epoch;
        }
        if (lamportTime != other.lamportTime) {
            return lamportTime > other.lamportTime;
        }
        return originNode > other.originNode;
    }

    /** True if both items carry the same (epoch, lamportTime, originNode), whatever the values. */
    public boolean sameVersionAs(DataItem other) {
        return other != null
                && epoch == other.epoch
                && lamportTime == other.lamportTime
                && originNode == other.originNode;
    }

    private static void requireNoControlCharacters(String field, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) || c == ' ' || c == ' ') {
                throw new IllegalArgumentException(field + " must not contain control or line-break"
                        + " characters (U+" + String.format("%04X", (int) c) + " at index " + i + ")");
            }
        }
    }
}
