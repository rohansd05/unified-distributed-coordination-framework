package com.udcf.modules.mapreduce;

import java.util.Objects;

/**
 * Immutable key-value pair emitted by map tasks and collected across the pipeline.
 */
public record KeyValuePair(String key, String value) {
    public KeyValuePair {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
    }
}
