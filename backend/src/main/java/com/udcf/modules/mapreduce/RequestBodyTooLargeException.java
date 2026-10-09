package com.udcf.modules.mapreduce;

/**
 * Thrown by {@link RunRequestBodyLimit} when a {@code POST /runs} body is larger than
 * {@link MapReduceModuleProperties#requestBodyMaxBytes()}. Unchecked on purpose, so the JSON
 * reader passes it through unchanged. Mapped to HTTP 413 by {@link MapReduceController}.
 *
 * <p>No dedicated test: a constructor and an accessor only.</p>
 */
public class RequestBodyTooLargeException extends RuntimeException {

    private final long limitBytes;

    public RequestBodyTooLargeException(long limitBytes) {
        super("The request body is larger than the limit of " + limitBytes + " bytes");
        this.limitBytes = limitBytes;
    }

    public long limitBytes() {
        return limitBytes;
    }
}
