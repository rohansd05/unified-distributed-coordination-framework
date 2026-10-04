package com.udcf.web;

import com.udcf.core.module.ExperimentModule;
import com.udcf.core.module.ModuleStatus;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test support: one fake module, so controller tests can list a module and make a reset
 * hit a BUSY module.
 *
 * <p>Importing this class gives the test its own Spring context (context D), separate
 * from every context that relies on startup events. All crash and reset tests live there.
 * The fake uses lab number 10; when the real Matrix module arrives (Phase 12) these tests
 * must use a different mechanism or lab number.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestModuleConfig {

    @Bean
    FakeModule testModule() {
        return new FakeModule();
    }

    /** A module whose status a test can set, and whose resets are counted. */
    public static final class FakeModule implements ExperimentModule {

        public static final String ID = "test-module";
        public static final int LAB_NUMBER = 10;

        private volatile ModuleStatus status = ModuleStatus.IDLE;
        private final AtomicInteger resets = new AtomicInteger();

        @Override
        public String id() {
            return ID;
        }

        @Override
        public int labNumber() {
            return LAB_NUMBER;
        }

        @Override
        public String title() {
            return "Test Module";
        }

        @Override
        public ModuleStatus status() {
            return status;
        }

        public void setStatus(ModuleStatus status) {
            this.status = status;
        }

        @Override
        public void reset() {
            resets.incrementAndGet();
        }

        public int resetCount() {
            return resets.get();
        }
    }
}
