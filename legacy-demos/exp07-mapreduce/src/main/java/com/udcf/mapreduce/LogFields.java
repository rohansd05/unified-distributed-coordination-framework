package com.udcf.mapreduce;

/**
 * Tiny parser for the framework's event-log format:
 *
 * <pre>
 * 2026-09-18 14:33:28.151 | node=2 | category=RECV | latency=10.48 | message received
 * </pre>
 *
 * <p>Kept deliberately forgiving: a field that is missing returns null and the mapper
 * skips that record. A single malformed line should never fail an entire job.</p>
 */
public final class LogFields {

    private LogFields() { }

    public static String field(String line, String key) {
        if (line == null) {
            return null;
        }
        for (String part : line.split("\\|")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(key + "=")) {
                return trimmed.substring(key.length() + 1).trim();
            }
        }
        return null;
    }
}
