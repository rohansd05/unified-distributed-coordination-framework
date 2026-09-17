package com.udcf.replication;

/**
 * One versioned value in the replicated store.
 *
 * <p>The value alone is not enough to replicate safely. Each item also carries the
 * Lamport timestamp and the id of the node that created it, and that pair is what lets
 * any replica decide, on its own and without coordination, which of two competing
 * updates to the same key is the newer one.</p>
 *
 * @param key         the logical name of the data item
 * @param value       the stored value
 * @param lamportTime the writer's logical clock at the moment of the write
 * @param originNode  the id of the node that created this version
 */
public record DataItem(String key, String value, long lamportTime, int originNode) {

    /**
     * Last-writer-wins comparison using the pair (lamportTime, originNode).
     *
     * <p>Wall-clock time is deliberately not used. Two nodes' system clocks can disagree,
     * so a wall-clock comparison could silently let a stale update overwrite a newer one.
     * The Lamport timestamp is derived from actual message causality, and the node id
     * breaks ties deterministically so every replica reaches the same verdict.</p>
     */
    public boolean isNewerThan(DataItem other) {
        if (other == null) {
            return true;
        }
        if (lamportTime != other.lamportTime) {
            return lamportTime > other.lamportTime;
        }
        return originNode > other.originNode;
    }

    /** Serialised form used on the wire. */
    public String encode() {
        return key + ";" + value + ";" + lamportTime + ";" + originNode;
    }

    public static DataItem decode(String raw) {
        String[] p = raw.split(";", 4);
        return new DataItem(p[0], p[1], Long.parseLong(p[2]), Integer.parseInt(p[3]));
    }

    public String shortForm() {
        return key + "=" + value + " (L" + lamportTime + ",N" + originNode + ")";
    }
}
