package com.udcf.modules.rmi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The host-name rule on a private {@link Properties} object, so the JVM's property is never set
 * to a wrong value. That a stub really carries 127.0.0.1 is checked in RmiNodeServiceTest.
 */
class RmiHostNameTest {

    @Test
    @DisplayName("an unset property becomes 127.0.0.1")
    void setsWhenUnset() {
        Properties properties = new Properties();

        RmiHostName.ensureLoopback(properties);

        assertThat(properties.getProperty("java.rmi.server.hostname")).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("calling it again changes nothing")
    void idempotent() {
        Properties properties = new Properties();
        RmiHostName.ensureLoopback(properties);
        RmiHostName.ensureLoopback(properties);

        assertThat(properties).containsOnly(java.util.Map.entry("java.rmi.server.hostname", "127.0.0.1"));
    }

    @Test
    @DisplayName("another value already set fails fast, naming both values, and is kept")
    void conflictingValueFails() {
        Properties properties = new Properties();
        properties.setProperty("java.rmi.server.hostname", "build-host.example");

        assertThatThrownBy(() -> RmiHostName.ensureLoopback(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("java.rmi.server.hostname is already set to 'build-host.example'; "
                        + "Experiment 1 needs '127.0.0.1'");
        assertThat(properties.getProperty("java.rmi.server.hostname")).isEqualTo("build-host.example");
    }

    @Test
    @DisplayName("on the JVM's own properties it leaves 127.0.0.1 in place")
    void systemProperties() {
        RmiHostName.ensureLoopback();
        RmiHostName.ensureLoopback();

        assertThat(System.getProperty("java.rmi.server.hostname")).isEqualTo("127.0.0.1");
    }
}
