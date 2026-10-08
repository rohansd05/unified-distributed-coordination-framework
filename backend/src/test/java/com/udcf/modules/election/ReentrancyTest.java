package com.udcf.modules.election;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ReentrancyTest {

    private TestDoubles.FakeTimer timer;
    private TestDoubles.FakeClock wallClock;
    private ElectionConfig config;

    @BeforeEach
    void setup() {
        timer = new TestDoubles.FakeTimer();
        wallClock = new TestDoubles.FakeClock();
        config = new ElectionConfig(100, 200, 50, 1000);
    }

    @Test
    void testReentrancyAndCrashDuringExecution() {
        List<ElectionMessage> sent = new ArrayList<>();
        List<Integer> targets = new ArrayList<>();
        
        BullyAlgorithm[] nodeRef = new BullyAlgorithm[1];
        
        ElectionMessenger reentrantMessenger = new ElectionMessenger() {
            private boolean crashedIt = false;
            
            @Override
            public void send(int targetNodeId, ElectionMessage message) {
                sent.add(message);
                targets.add(targetNodeId);
                
                if (message.type() == ElectionMessageType.ELECTION && targetNodeId == 2) {
                    nodeRef[0].startElection();
                }
                
                if (message.type() == ElectionMessageType.ELECTION && targetNodeId == 3 && !crashedIt) {
                    crashedIt = true;
                    nodeRef[0].crash();
                }
            }
        };

        TestDoubles.FakeEventListener listener = new TestDoubles.FakeEventListener();
        TestDoubles.FakeNanoClock nanoClock = new TestDoubles.FakeNanoClock();
        nodeRef[0] = new BullyAlgorithm(1, List.of(1, 2, 3, 4), config, reentrantMessenger, listener, timer, nanoClock, wallClock);
        
        nodeRef[0].startElection();

        assertEquals(2, sent.size());
        assertEquals(2, targets.get(0));
        assertEquals(3, targets.get(1));
        
        assertTrue(nodeRef[0].isCrashed());
    }

    @Test
    void testRingReentrancyNoDeadlock() {
        List<ElectionMessage> sent = new ArrayList<>();
        RingAlgorithm[] nodeRef = new RingAlgorithm[1];

        ElectionMessenger reentrantMessenger = new ElectionMessenger() {
            @Override
            public void send(int targetNodeId, ElectionMessage message) {
                sent.add(message);
                if (message.type() == ElectionMessageType.PROBE) {
                    // Reentrantly deliver ACK directly inside send!
                    nodeRef[0].onReceive(new ElectionMessage(ElectionMessageType.PROBE_ACK, targetNodeId, 100, ""));
                }
            }
        };

        TestDoubles.FakeEventListener listener = new TestDoubles.FakeEventListener();
        LivenessProber prober = new DefaultLivenessProber(1, reentrantMessenger, timer, 300);
        nodeRef[0] = new RingAlgorithm(1, List.of(1, 2, 3), config, reentrantMessenger, listener, timer, wallClock, prober);

        nodeRef[0].startElection();
        
        // It sends PROBE to 2, gets PROBE_ACK reentrantly, then sends RING_ELECTION to 2
        assertEquals(2, sent.size());
        assertEquals(ElectionMessageType.PROBE, sent.get(0).type());
        assertEquals(ElectionMessageType.RING_ELECTION, sent.get(1).type());
    }
}
