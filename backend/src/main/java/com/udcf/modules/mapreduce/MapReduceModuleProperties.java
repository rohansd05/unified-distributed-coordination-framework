package com.udcf.modules.mapreduce;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration of the Experiment 7 module and its API (step E7c), bound from
 * {@code udcf.mapreduce.*} beside {@link MapReduceProperties} (the E7b transport settings on
 * the same prefix; each record binds only its own keys). Values come from YAML only, with no
 * code defaults. Registered by {@code @ConfigurationPropertiesScan}, like every other
 * properties class.
 *
 * <p><b>Sizing rule.</b> Every task must fit in one wire line of at most
 * {@code max-request-bytes}. With one live worker a single split is the whole input, Base64 on
 * the wire adds a third, and a map reply or reduce partition can reach about 3.7 times the
 * input (many distinct four-character words: every key is Base64-encoded inside the payload,
 * then the payload again on the wire). So {@code upload-max-bytes} must be at most a quarter of
 * {@code max-request-bytes}; {@link #requireFitsWire} enforces it at startup.</p>
 *
 * @param uploadMaxBytes            largest uploaded file, and the byte cap of the event-log input
 * @param resultRowsMax             result rows kept in a run's report; the rest are counted, not listed
 * @param runHistorySize            runs kept for {@code GET /runs}, newest first
 * @param requestBodyAllowanceBytes bytes allowed in a {@code POST /runs} body beyond the Base64
 *                                  size of the upload cap (for the other JSON fields)
 */
@Validated
@ConfigurationProperties("udcf.mapreduce")
public record MapReduceModuleProperties(
        @Min(1) int uploadMaxBytes,
        @Min(1) int resultRowsMax,
        @Min(1) int runHistorySize,
        @Min(0) int requestBodyAllowanceBytes
) {

    /** The upload cap may be at most {@code max-request-bytes} divided by this (see the sizing rule). */
    public static final int MAX_REQUEST_BYTES_DIVISOR = 4;

    public MapReduceModuleProperties {
        if (uploadMaxBytes < 1) {
            throw new IllegalArgumentException("uploadMaxBytes must be >= 1, was " + uploadMaxBytes);
        }
        if (resultRowsMax < 1) {
            throw new IllegalArgumentException("resultRowsMax must be >= 1, was " + resultRowsMax);
        }
        if (runHistorySize < 1) {
            throw new IllegalArgumentException("runHistorySize must be >= 1, was " + runHistorySize);
        }
        if (requestBodyAllowanceBytes < 0) {
            throw new IllegalArgumentException(
                    "requestBodyAllowanceBytes must be >= 0, was " + requestBodyAllowanceBytes);
        }
    }

    /**
     * Fails fast unless {@code uploadMaxBytes <= maxRequestBytes / 4}.
     *
     * @throws IllegalStateException with the two values and the reason
     */
    public void requireFitsWire(MapReduceProperties wire) {
        if ((long) uploadMaxBytes * MAX_REQUEST_BYTES_DIVISOR > wire.maxRequestBytes()) {
            throw new IllegalStateException("udcf.mapreduce.upload-max-bytes (" + uploadMaxBytes
                    + ") must be at most a quarter of udcf.mapreduce.max-request-bytes ("
                    + wire.maxRequestBytes() + "), so that the largest input split and its "
                    + "intermediate data fit in one wire line");
        }
    }

    /** Length of the upload cap in Base64 characters, padding included. */
    public long uploadBase64Chars() {
        return 4L * ((uploadMaxBytes + 2L) / 3L);
    }

    /** Largest accepted {@code POST /runs} body: the Base64 size of the cap plus the allowance. */
    public long requestBodyMaxBytes() {
        return uploadBase64Chars() + requestBodyAllowanceBytes;
    }
}
