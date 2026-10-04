package com.udcf.web;

/**
 * How the backend is deployed: the full local system, or the public lite deployment.
 *
 * <p>No dedicated test: a plain enum with no behaviour.</p>
 */
public enum RunMode {
    LOCAL,
    PUBLIC
}
