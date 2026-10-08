package com.udcf.modules.election;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DefaultLivenessProberTest {

    private TestDoubles.FakeTimer timer;
    private TestDoubles.FakeMessenger messenger;
    private DefaultLivenessProber prober;

    @BeforeEach
    void setUp() {
        timer = new TestDoubles.FakeTimer();
        messenger = new TestDoubles.FakeMessenger();
        prober = new DefaultLivenessProber(1, messenger, timer, 300);
    }

    @Test
    void testAckBeforeTimeout() {
        AtomicReference<Boolean> result = new AtomicReference<>();
        AtomicInteger callCount = new AtomicInteger(0);
        
        prober.probe(2, 10, res -> {
            result.set(res);
            callCount.incrementAndGet();
        });
        
        prober.onProbeAck(2); // Ack arrives
        timer.fire(300);      // Timeout fires later
        
        assertTrue(result.get());
        assertEquals(1, callCount.get()); // Exactly once
    }

    @Test
    void testTimeoutBeforeAck() {
        AtomicReference<Boolean> result = new AtomicReference<>();
        AtomicInteger callCount = new AtomicInteger(0);
        
        prober.probe(2, 10, res -> {
            result.set(res);
            callCount.incrementAndGet();
        });
        
        timer.fire(300);      // Timeout fires first
        prober.onProbeAck(2); // Ack arrives later
        
        assertFalse(result.get());
        assertEquals(1, callCount.get()); // Exactly once
    }
}
