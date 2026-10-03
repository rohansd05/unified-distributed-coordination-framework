package com.udcf.threadpool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NamedThreadFactoryTest {

    @Test
    @DisplayName("numbers threads sequentially from the configured prefix")
    void numbersThreadsSequentially() {
        NamedThreadFactory factory = new NamedThreadFactory("test-worker-");

        Thread first = factory.newThread(() -> { });
        Thread second = factory.newThread(() -> { });

        assertThat(first.getName()).isEqualTo("test-worker-1");
        assertThat(second.getName()).isEqualTo("test-worker-2");
    }

    @Test
    @DisplayName("creates non-daemon threads so in-flight work is not dropped on shutdown")
    void createsNonDaemonThreads() {
        Thread thread = new NamedThreadFactory("test-").newThread(() -> { });

        assertThat(thread.isDaemon()).isFalse();
        assertThat(thread.getPriority()).isEqualTo(Thread.NORM_PRIORITY);
    }

    @Test
    @DisplayName("falls back to a default prefix when none is configured")
    void fallsBackToDefaultPrefix() {
        NamedThreadFactory factory = new NamedThreadFactory(null);

        assertThat(factory.getPrefix()).isEqualTo("udcf-worker-");
        assertThat(factory.newThread(() -> { }).getName()).startsWith("udcf-worker-");
    }
}
