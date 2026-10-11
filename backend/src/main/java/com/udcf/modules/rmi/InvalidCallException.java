package com.udcf.modules.rmi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thrown when a call's method and arguments do not fit together or break a configured limit.
 * Carries every field message at once; {@link RmiController} maps it to HTTP 400 in the same shape
 * as the shared handler's validation errors ({@code errors: {field: message}}).
 *
 * <p>No dedicated test: a constructor and an accessor only.</p>
 */
public class InvalidCallException extends RuntimeException {

    private final Map<String, String> errors;

    public InvalidCallException(Map<String, String> errors) {
        super("Invalid request body: " + String.join(", ", errors.keySet()));
        if (errors.isEmpty()) {
            throw new IllegalArgumentException("errors must not be empty");
        }
        this.errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
    }

    /** Field name to message, in the order the fields were checked. */
    public Map<String, String> errors() {
        return errors;
    }
}
