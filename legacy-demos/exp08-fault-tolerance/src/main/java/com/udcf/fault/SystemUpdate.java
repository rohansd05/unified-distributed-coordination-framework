package com.udcf.fault;

/**
 * One system update — a configuration change applied to the cluster's shared state.
 *
 * <p>Each update carries the epoch of the primary that accepted it as well as a version.
 * The epoch is what lets a backup refuse an update from a primary that has already been
 * replaced, and the version is what resolves ordinary conflicts.</p>
 *
 * @param sequence   the client's sequence number for this update
 * @param key        the setting being changed
 * @param value      the new value
 * @param version    Lamport timestamp assigned by the primary that accepted it
 * @param originNode the primary that accepted it
 * @param epoch      the primary's term number at the time of acceptance
 */
public record SystemUpdate(int sequence, String key, String value,
                           long version, int originNode, long epoch) {

    public boolean isNewerThan(SystemUpdate other) {
        if (other == null) {
            return true;
        }
        if (epoch != other.epoch) {
            return epoch > other.epoch;       // a later primary always wins
        }
        if (version != other.version) {
            return version > other.version;
        }
        return originNode > other.originNode; // deterministic tie-break
    }

    public String encode() {
        return sequence + ";" + key + ";" + value + ";" + version + ";" + originNode + ";" + epoch;
    }

    public static SystemUpdate decode(String raw) {
        String[] p = raw.split(";", 6);
        return new SystemUpdate(Integer.parseInt(p[0]), p[1], p[2],
                Long.parseLong(p[3]), Integer.parseInt(p[4]), Long.parseLong(p[5]));
    }

    public String shortForm() {
        return key + "=" + value + " (e" + epoch + ",v" + version + ")";
    }
}
