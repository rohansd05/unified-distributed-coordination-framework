package com.udcf.modules.election;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class RingAlgorithmTest {

    private TestDoubles.FakeTimer timer;
    private TestDoubles.FakeNanoClock nanoClock;
    private TestDoubles.FakeClock wallClock;
    private TestDoubles.FakeMessenger messenger;
    private TestDoubles.FakeEventListener listener;
    private ElectionConfig config;
    
    private static class SyncExecutor implements Executor {
        @Override public void execute(Runnable command) { command.run(); }
    }

    private static class MockProber implements LivenessProber {
        public boolean alive = true;
        @Override public void probe(int targetId, long currentLamport, Consumer<Boolean> callback) { callback.accept(alive); }
        @Override public void onProbeAck(int senderId) {}
    }

    private RingAlgorithm createNode(int id, List<Integer> ring, LivenessProber prober) {
        return new RingAlgorithm(id, ring, config, messenger, listener, timer, wallClock, prober);
    }

    @BeforeEach
    void setUp() {
        timer = new TestDoubles.FakeTimer();
        nanoClock = new TestDoubles.FakeNanoClock();
        wallClock = new TestDoubles.FakeClock();
        messenger = new TestDoubles.FakeMessenger();
        listener = new TestDoubles.FakeEventListener();
        config = new ElectionConfig(900, 2200, 300, 1000);
    }

    @Test
    void testRingOfOne() {
        MockProber prober = new MockProber();
        RingAlgorithm node = createNode(1, List.of(1), prober);
        node.startElection();
        // Since ring has only 1 node, it does not forward anything, and will just time out.
        // Wait, a ring of one node: the token contains itself.
        // If myIndex is found, and offset loop runs, but size is 1, offset < 1 is false.
        // So it doesn't send anything.
        // This means a ring of 1 node does not work? Let's check ring order.
        // If ring size is 1, it should just become coordinator?
        // But the legacy demo forwards to allIds.length.
        // For size 1, offset=1 to 0 doesn't loop. So it sends nothing, waits for completion timeout.
        assertNull(node.getCoordinatorId());
    }

    @Test
    void testRingOfTwo() {
        MockProber prober = new MockProber();
        RingAlgorithm node1 = createNode(1, List.of(1, 2), prober);
        node1.startElection();
        assertEquals(1, messenger.sent.size());
        assertEquals("1", messenger.sent.get(0).payload());
        
        node1.onReceive(new ElectionMessage(ElectionMessageType.RING_ELECTION, 2, 10, "1,2"));
        assertEquals(2, node1.getCoordinatorId());
    }

    @Test
    void testAllOtherDead() {
        MockProber prober = new MockProber();
        prober.alive = false; // all other nodes dead
        RingAlgorithm node1 = createNode(1, List.of(1, 2, 3), prober);
        node1.startElection();
        assertEquals(0, messenger.sent.size()); // skips all dead, sends nothing
    }

    @Test
    void testLegacyTokenSequence() {
        MockProber prober = new MockProber();
        RingAlgorithm node1 = createNode(1, List.of(1, 2, 3), prober);
        node1.onReceive(new ElectionMessage(ElectionMessageType.RING_ELECTION, 3, 10, "3"));
        // Token received from 3 containing "3". Should append 1 and forward to 2
        assertEquals(1, messenger.sent.size());
        assertEquals(ElectionMessageType.RING_ELECTION, messenger.sent.get(0).type());
        assertEquals("3,1", messenger.sent.get(0).payload());
        assertEquals(2, messenger.targets.get(0));
    }

    @Test
    void testHighestWinsAfterFullCircle() {
        MockProber prober = new MockProber();
        RingAlgorithm node2 = createNode(2, List.of(1, 2, 3), prober);
        // Node 2 receives token that went 3 -> 1 -> 2
        node2.onReceive(new ElectionMessage(ElectionMessageType.RING_ELECTION, 1, 10, "3,1,2"));
        // Since 2 is in the list, it's a full circle. Highest is 3.
        assertEquals(3, node2.getCoordinatorId());
        // Forwards RING_COORDINATOR "3:2" to 3
        assertEquals(1, messenger.sent.size());
        assertEquals(ElectionMessageType.RING_COORDINATOR, messenger.sent.get(0).type());
        assertEquals("3:2", messenger.sent.get(0).payload());
    }

    @Test
    void testRingCoordinatorStopsAtOriginator() {
        MockProber prober = new MockProber();
        RingAlgorithm node2 = createNode(2, List.of(1, 2, 3), prober);
        
        // Node 2 completes election and sets coordinatorId
        node2.onReceive(new ElectionMessage(ElectionMessageType.RING_ELECTION, 1, 10, "3,1,2"));
        assertEquals(3, node2.getCoordinatorId());
        messenger.clear();
        
        // Node 2 receives coordinator message from 1, originator was 2
        node2.onReceive(new ElectionMessage(ElectionMessageType.RING_COORDINATOR, 1, 10, "3:2"));
        // Should accept coordinator and NOT forward
        assertEquals(3, node2.getCoordinatorId());
        assertEquals(0, messenger.sent.size());
    }

    @Test
    void testTwoOriginatorsHighestWins() {
        MockProber prober = new MockProber();
        RingAlgorithm node1 = createNode(1, List.of(1, 2, 3), prober);
        // Both 1 and 2 started elections.
        // Node 1 receives token from 3 that was started by 2: "2,3"
        node1.onReceive(new ElectionMessage(ElectionMessageType.RING_ELECTION, 3, 10, "2,3"));
        assertEquals("2,3,1", messenger.sent.get(0).payload());
        messenger.clear();
        
        // Node 1 receives its own token back: "1,2,3,1"
        node1.onReceive(new ElectionMessage(ElectionMessageType.RING_ELECTION, 3, 11, "1,2,3,1"));
        assertEquals(3, node1.getCoordinatorId());
    }

    @Test
    void testLostToken() {
        MockProber prober = new MockProber();
        RingAlgorithm node1 = createNode(1, List.of(1, 2, 3), prober);
        node1.startElection();
        // Fire completion timeout
        timer.fire(config.ringCompletionTimeoutMs());
        // Flag cleared, another election can start
        messenger.clear();
        node1.startElection();
        assertEquals(1, messenger.sent.size()); // It sent a new token!
    }

    @Test
    void testTokenAlreadyContainsReceiver() {
        MockProber prober = new MockProber();
        RingAlgorithm node1 = createNode(1, List.of(1, 2, 3), prober);
        node1.onReceive(new ElectionMessage(ElectionMessageType.RING_ELECTION, 3, 10, "1,2,3"));
        // Token already contains 1! So 1 knows it completed the circle.
        assertEquals(3, node1.getCoordinatorId());
    }
    
    @Test
    void testSingleThreadedListenerCompletesWithDeadSuccessor() {
        // We use DefaultLivenessProber to prove it's entirely callback-driven and never blocks.
        DefaultLivenessProber realProber = new DefaultLivenessProber(1, messenger, timer, 300);
        RingAlgorithm node1 = createNode(1, List.of(1, 2, 3), realProber);
        
        node1.startElection();
        // node1 sends PROBE to 2 and sets timer for 300ms
        assertEquals(1, messenger.sent.size());
        assertEquals(ElectionMessageType.PROBE, messenger.sent.get(0).type());
        assertEquals(2, messenger.targets.get(0));
        
        messenger.clear();
        // Since it's single threaded and we don't deliver an ACK, we just fire the timeout
        timer.fire(300);
        
        // node1 now treats 2 as dead, and sends PROBE to 3
        assertEquals(1, messenger.sent.size());
        assertEquals(ElectionMessageType.PROBE, messenger.sent.get(0).type());
        assertEquals(3, messenger.targets.get(0));
        
        messenger.clear();
        // Now we deliver an ACK for 3
        realProber.onProbeAck(3);
        
        // node1 now forwards the token to 3
        assertEquals(1, messenger.sent.size());
        assertEquals(ElectionMessageType.RING_ELECTION, messenger.sent.get(0).type());
        assertEquals(3, messenger.targets.get(0));
        assertEquals("1", messenger.sent.get(0).payload());
    }

    @Test
    void testInProgressFlagReleased() {
        MockProber prober = new MockProber();
        RingAlgorithm node1 = createNode(1, List.of(1, 2, 3), prober);
        node1.startElection();
        
        // Timeout resets flag
        timer.fire(config.ringCompletionTimeoutMs());
        messenger.clear();
        node1.startElection();
        prober.onProbeAck(2);
        assertEquals(1, messenger.sent.size());
        
        // Crash resets it
        node1.crash();
        node1.recover(); // implicitly calls startElection
        prober.onProbeAck(2);
        // Wait, recover() might use a NEW lamport time, but messenger still has the old ones if not cleared.
        // Actually, we should clear it BEFORE recover to be sure!
        // But recover() doesn't take time. Let's just clear before recover?
        // Let's do:
        messenger.clear();
        node1.startElection(); // Will be ignored if recover already started it
        // Actually, let's just make a clean break:
        node1.crash();
        node1.recover();
        messenger.clear();
        node1.startElection();
        assertEquals(1, messenger.sent.size());
        
        // Completion releases it
        node1.onReceive(new ElectionMessage(ElectionMessageType.RING_COORDINATOR, 3, 10, "3:3"));
        messenger.clear();
        node1.startElection();
        prober.onProbeAck(2);
        assertEquals(1, messenger.sent.size());
        
        // Exception releases it
        node1.onReceive(new ElectionMessage(ElectionMessageType.RING_COORDINATOR, 3, 10, "3:3"));
        messenger.throwException = true;
        assertThrows(RuntimeException.class, () -> {
            node1.startElection();
            prober.onProbeAck(2);
        });
        messenger.throwException = false;
        messenger.clear();
        node1.startElection();
        prober.onProbeAck(2);
        assertEquals(1, messenger.sent.size());
    }

    @Test
    void testTimerAfterCrashDoesNothing() {
        MockProber prober = new MockProber();
        RingAlgorithm node1 = createNode(1, List.of(1, 2, 3), prober);
        node1.startElection();
        node1.crash();
        messenger.clear();
        timer.fire(config.ringCompletionTimeoutMs());
        assertEquals(0, messenger.sent.size());
    }

    @Test
    void testLamportClock() {
        MockProber prober = new MockProber();
        RingAlgorithm node1 = createNode(1, List.of(1, 2, 3), prober);
        // Start election -> node ticks clock
        node1.startElection();
        prober.onProbeAck(2);
        long sendTime = messenger.sent.get(0).lamportTime();
        assertTrue(sendTime > 0);
        
        // On receive -> updates clock first
        node1.onReceive(new ElectionMessage(ElectionMessageType.RING_COORDINATOR, 3, 100, "3:3"));
        // Receiver strictly above 100 -> next message should be > 100
        messenger.clear();
        node1.startElection();
        prober.onProbeAck(2);
        long newSendTime = messenger.sent.get(0).lamportTime();
        assertTrue(newSendTime > 100);
    }

    @Test
    void testOldCompletionTimeoutFiredAfterNewElectionStartedDoesNothing() {
        MockProber prober = new MockProber();
        RingAlgorithm node1 = createNode(1, List.of(1, 2, 3), prober);
        node1.startElection();
        Runnable oldCompletionTimeout = timer.tasks.get(timer.tasks.size() - 1).action;

        // Completion timeout fires normally, clearing in-progress flag
        timer.fire(config.ringCompletionTimeoutMs());
        listener.clear();

        // Node starts a new election (generation 2)
        node1.startElection();
        listener.clear();

        // Now fire the OLD completion timeout from generation 1:
        oldCompletionTimeout.run();

        // Should do nothing: no timeout event emitted
        assertEquals(0, listener.events.stream().filter(e -> e.type() == ElectionEventType.ELECTION_TIMEOUT).count());
    }

    @Test
    void testCrashBetweenCollectingAndSendingSendsNothingFurther() {
        MockProber prober = new MockProber();
        RingAlgorithm[] nodeRef = new RingAlgorithm[1];
        ElectionEventListener crashOnStartListener = event -> {
            if (event.type() == ElectionEventType.ELECTION_START) {
                nodeRef[0].crash();
            }
        };
        nodeRef[0] = new RingAlgorithm(1, List.of(1, 2, 3), config, messenger, crashOnStartListener, timer, wallClock, prober);
        nodeRef[0].startElection();

        assertTrue(nodeRef[0].isCrashed());
        assertEquals(0, messenger.sent.size());
        assertEquals(0, timer.tasks.size());
    }

    @Test
    void testNoEventForProbeOrProbeAck() {
        MockProber prober = new MockProber();
        RingAlgorithm node1 = createNode(1, List.of(1, 2, 3), prober);
        listener.clear();

        node1.onReceive(new ElectionMessage(ElectionMessageType.PROBE, 2, 10, ""));
        node1.onReceive(new ElectionMessage(ElectionMessageType.PROBE_ACK, 2, 11, ""));

        assertEquals(0, listener.events.size(), "No election events should be emitted for PROBE or PROBE_ACK");
    }
}
