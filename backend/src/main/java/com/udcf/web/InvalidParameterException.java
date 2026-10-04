package com.udcf.web;

/**
 * Thrown when a request parameter fails a check that Jakarta Validation cannot express,
 * such as a limit bounded by configuration. Mapped to HTTP 400 with the same body shape as
 * annotation-based parameter validation.
 *
 * <p>No dedicated test: a constructor and an accessor only.</p>
 */
public class InvalidParameterException extends RuntimeException {

    private final String parameter;

    public InvalidParameterException(String parameter, String message) {
        super(message);
        this.parameter = parameter;
    }

    public String parameter() {
        return parameter;
    }
}
