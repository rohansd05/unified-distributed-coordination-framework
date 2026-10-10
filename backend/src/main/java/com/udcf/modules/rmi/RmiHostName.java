package com.udcf.modules.rmi;

import java.util.Objects;
import java.util.Properties;

/**
 * Sets {@code java.rmi.server.hostname=127.0.0.1} once, before the first export (docs/HANDOFF.md
 * Section 7). RMI writes this host into every stub, so every stub, and every RMI exception
 * message built from one, names only the loopback address, never the machine's host name.
 *
 * <p>Idempotent and safe to call from every {@code start()} and {@code recover()}, so no test
 * depends on which RMI test runs first in the shared Surefire JVM. If the property already holds
 * another value, it fails fast instead of overwriting it.</p>
 */
final class RmiHostName {

    static final String PROPERTY = "java.rmi.server.hostname";
    static final String LOOPBACK_HOST = "127.0.0.1";

    private RmiHostName() {
    }

    /** Applies the rule to the JVM's system properties. */
    static void ensureLoopback() {
        ensureLoopback(System.getProperties());
    }

    /**
     * Sets {@link #PROPERTY} to {@link #LOOPBACK_HOST} in {@code properties} if unset; does
     * nothing if already set to it.
     *
     * @throws IllegalStateException if it holds another value, naming both
     */
    static synchronized void ensureLoopback(Properties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        String current = properties.getProperty(PROPERTY);
        if (current == null) {
            properties.setProperty(PROPERTY, LOOPBACK_HOST);
        } else if (!current.equals(LOOPBACK_HOST)) {
            throw new IllegalStateException(PROPERTY + " is already set to '" + current
                    + "'; Experiment 1 needs '" + LOOPBACK_HOST + "'");
        }
    }
}
