package com.udcf.modules.mapreduce;

/**
 * Parser for the UDCF pipe-delimited event log format:
 *
 * <pre>
 * 2026-09-18 14:33:28.151 | node=2 | category=RECV | latency=10.48 | message received
 * </pre>
 *
 * <p>Deliberately forgiving: missing fields return {@code null} so mappers can safely
 * skip irregular records without failing the entire batch.</p>
 */
public final class LogFields {

    private LogFields() { }

    /**
     * Extracts the value for {@code key} from a pipe-delimited log entry, or {@code null}
     * if the key is not present.
     */
    public static String field(String line, String key) {
        if (line == null || key == null) {
            return null;
        }
        String prefix = key + "=";
        for (String part : line.split("\\|")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return null;
    }
}
