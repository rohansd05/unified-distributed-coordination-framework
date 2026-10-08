package com.udcf.modules.election;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BullyAlgorithmTest {

    private TestDoubles.FakeTimer timer;
    private TestDoubles.FakeNanoClock nanoClock;
    private TestDoubles.FakeClock wallClock;
    private TestDoubles.FakeMessenger messenger;
    private TestDoubles.FakeEventListener listener;
    private ElectionConfig config;
    private BullyAlgorithm node1;
    private BullyAlgorithm node2;
    private BullyAlgorithm node3;

    @BeforeEach
    void setUp() {
        timer = new TestDoubles.FakeTimer();
        nanoClock = new TestDoubles.FakeNanoClock();
        wallClock = new TestDoubles.FakeClock();
        messenger = new TestDoubles.FakeMessenger();
        listener = new TestDoubles.FakeEventListener();
        config = new ElectionConfig(900, 2200, 300, 1000);

        List<Integer> allNodes = List.of(1, 2, 3);
        node1 = new BullyAlgorithm(1, allNodes, config, messenger, listener, timer, nanoClock, wallClock);
        node2 = new BullyAlgorithm(2, allNodes, config, messenger, listener, timer, nanoClock, wallClock);
        node3 = new BullyAlgorithm(3, allNodes, config, messenger, listener, timer, nanoClock, wallClock);
    }

    @Test
    void testLegacyLowerIdCoordinator() {
        // Node 3 is highest, but receives COORDINATOR from Node 1 (lower id)
        node3.onReceive(new ElectionMessage(ElectionMessageType.COORDINATOR, 1, 10, "1"));
        assertEquals(1, node3.getCoordinatorId());
        assertTrue(listener.events.stream().anyMatch(e -> e.type() == ElectionEventType.COORDINATOR_ACCEPTED && e.peerId() == 1));
    }

    @Test
    void testLateOkIgnored() {
        node1.startElection(); // starts OK timer
        timer.fire(config.okTimeoutMs()); // timeout fires, becomes coordinator
        assertEquals(1, node1.getCoordinatorId());
        
        listener.clear();
        // now receives a late OK
        node1.onReceive(new ElectionMessage(ElectionMessageType.OK, 2, 5, ""));
        assertTrue(listener.events.stream().anyMatch(e -> e.type() == ElectionEventType.LATE_MESSAGE));
    }

    @Test
    void testStaleTimers() {
        node1.startElection();
        // receive OK to make the OK timer stale
        node1.onReceive(new ElectionMessage(ElectionMessageType.OK, 2, 5, ""));
        
        // fire the FIRST ok timer (which is now stale because generation incremented)
        timer.tasks.get(0).action.run(); // manually run the stale timer
        assertNull(node1.getCoordinatorId()); // should do nothing
    }

    @Test
    void testCrashClearsState() {
        node1.startElection();
        node1.crash();
        assertNull(node1.getCoordinatorId());
        // timer fires, but crashed
        timer.fire(config.okTimeoutMs());
        assertNull(node1.getCoordinatorId());
        
        messenger.clear();
        node1.recover();
        assertTrue(messenger.sent.size() > 0);
    }
    
    @Test
    void testHighestNodeRecovers() {
        node3.crash();
        node2.startElection();
        timer.fire(config.okTimeoutMs());
        assertEquals(2, node2.getCoordinatorId());
        
        node3.recover(); // calls startElection
        assertTrue(messenger.sent.size() > 0); // node 3 sends ELECTION to nobody?
        // wait, node 3 has no higher nodes. So it becomes coordinator immediately.
        assertEquals(3, node3.getCoordinatorId());
    }
    
    @Test
    void testLamportUsage() {
        node1.onReceive(new ElectionMessage(ElectionMessageType.ELECTION, 2, 100, ""));
        // node1 receives 100 -> updates to 100, ticks for OK (101), ticks for ELECTION_START (102), ticks for sending ELECTION to 2 (103), ticks for ELECTION to 3 (104).
        assertEquals(101, messenger.sent.get(0).lamportTime()); // OK message
        assertTrue(listener.events.get(0).lamportTime() > 100);
    }
    
    @Test
    void testLegacyMessageSequence() {
        // Node 1 receives ELECTION from Node 2.
        // Node 1 replies OK and starts its own election, sending ELECTION to 2 and 3.
        node1.onReceive(new ElectionMessage(ElectionMessageType.ELECTION, 2, 10, ""));
        assertEquals(3, messenger.sent.size());
        assertEquals(ElectionMessageType.OK, messenger.sent.get(0).type());
        assertEquals(2, messenger.targets.get(0));
        // Then it sends ELECTION to 2 and 3
        assertEquals(ElectionMessageType.ELECTION, messenger.sent.get(1).type());
        assertEquals(ElectionMessageType.ELECTION, messenger.sent.get(2).type());
    }

    @Test
    void testNoOkMeansSelfPromotion() {
        node2.startElection();
        // Fire OK timeout
        timer.fire(config.okTimeoutMs());
        assertEquals(2, node2.getCoordinatorId());
        // It should broadcast COORDINATOR to 1 and 3
        long coordMessages = messenger.sent.stream().filter(m -> m.type() == ElectionMessageType.COORDINATOR).count();
        assertEquals(2, coordMessages);
    }

    @Test
    void testOkMeansStandDownAndCoordinatorTimeoutRestarts() {
        node1.startElection();
        node1.onReceive(new ElectionMessage(ElectionMessageType.OK, 2, 5, ""));
        // Now waiting for COORDINATOR. OK timer firing does nothing.
        timer.fire(config.okTimeoutMs());
        assertNull(node1.getCoordinatorId());
        
        // Coordinator timeout fires -> restarts election
        messenger.clear();
        timer.fire(config.coordinatorTimeoutMs());
        assertTrue(messenger.sent.size() > 0);
        assertEquals(ElectionMessageType.ELECTION, messenger.sent.get(0).type());
    }

    @Test
    void testDuplicateCoordinator() {
        node1.onReceive(new ElectionMessage(ElectionMessageType.COORDINATOR, 3, 10, "3"));
        node1.onReceive(new ElectionMessage(ElectionMessageType.COORDINATOR, 3, 11, "3"));
        assertEquals(3, node1.getCoordinatorId());
        // One event per receive
        long acceptedEvents = listener.events.stream().filter(e -> e.type() == ElectionEventType.COORDINATOR_ACCEPTED).count();
        assertEquals(2, acceptedEvents);
    }

    @Test
    void testUnknownSenderIgnored() {
        node1.onReceive(new ElectionMessage(ElectionMessageType.PROBE, 2, 10, ""));
        long unknownEvents = listener.events.stream().filter(e -> e.type() == ElectionEventType.UNKNOWN_SENDER).count();
        assertEquals(1, unknownEvents);
    }

    @Test
    void testConcurrentStartElection() throws InterruptedException {
        int threads = 10;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try { latch.await(); } catch (InterruptedException e) {}
                node1.startElection();
                done.countDown();
            }).start();
        }
        latch.countDown();
        done.await();
        
        // Should only emit one ELECTION_START event
        long starts = listener.events.stream().filter(e -> e.type() == ElectionEventType.ELECTION_START).count();
        assertEquals(1, starts);
    }

    @Test
    void testOutputOrderPreserved() {
        node1.startElection();
        // The actions should be: event ELECTION_START, then send ELECTION to 2, send ELECTION to 3, then timer schedule.
        // Let's verify event was emitted BEFORE messages were sent (or roughly same time but we can check order in a customized way)
        // We know messenger and listener keep order. We can check their sizes at specific points if we mocked it,
        // but here we just check if it's correct.
        assertEquals(1, listener.events.size());
        assertEquals(ElectionEventType.ELECTION_START, listener.events.get(0).type());
        assertEquals(2, messenger.sent.size());
    }

    @Test
    void testHighestNodeStartsAndWins() {
        BullyAlgorithm node4 = new BullyAlgorithm(4, List.of(1, 2, 3, 4), config, messenger, listener, timer, nanoClock, wallClock);
        node4.startElection();
        assertEquals(4, node4.getCoordinatorId());
        assertEquals(3, messenger.sent.size());
        assertTrue(messenger.sent.stream().allMatch(m -> m.type() == ElectionMessageType.COORDINATOR));
    }

    @Test
    void testLowerIdCoordinatorQuirk() {
        BullyAlgorithm node3 = new BullyAlgorithm(3, List.of(1, 2, 3, 4), config, messenger, listener, timer, nanoClock, wallClock);
        node3.onReceive(new ElectionMessage(ElectionMessageType.COORDINATOR, 2, 10, ""));
        assertEquals(2, node3.getCoordinatorId());
    }

    @Test
    void testInProgressFlagReleased() {
        node1.startElection();
        timer.fire(config.okTimeoutMs()); // Should be okTimeoutMs because startElection sets okTimeout
        messenger.clear();
        node1.startElection();
        assertEquals(2, messenger.sent.size());
        
        node1.crash();
        messenger.clear();
        node1.recover();
        assertEquals(2, messenger.sent.size());
        
        node1.onReceive(new ElectionMessage(ElectionMessageType.COORDINATOR, 4, 10, ""));
        messenger.clear();
        node1.startElection();
        assertEquals(2, messenger.sent.size());
        
        // Exception releases it
        node1.onReceive(new ElectionMessage(ElectionMessageType.COORDINATOR, 4, 10, ""));
        messenger.throwException = true;
        assertThrows(RuntimeException.class, () -> node1.startElection());
        messenger.throwException = false;
        messenger.clear();
        node1.startElection();
        assertEquals(2, messenger.sent.size());
    }

    @Test
    void testTimerAfterCrashDoesNothing() {
        // OK timer after crash does nothing
        node1.startElection();
        node1.crash();
        messenger.clear();
        timer.fire(config.okTimeoutMs());
        assertEquals(0, messenger.sent.size());

        // Coordinator timer after crash does nothing
        node2.startElection();
        node2.onReceive(new ElectionMessage(ElectionMessageType.OK, 3, 10, ""));
        node2.crash();
        messenger.clear();
        timer.fire(config.coordinatorTimeoutMs());
        assertEquals(0, messenger.sent.size());
    }

    @Test
    void testOldOkTimeoutFiredAfterNewElectionStartedDoesNothing() {
        node1.startElection();
        Runnable oldOkTimeout = timer.tasks.get(timer.tasks.size() - 1).action;

        // Node receives OK, coordinator timeout fires to restart election (new election generation)
        node1.onReceive(new ElectionMessage(ElectionMessageType.OK, 2, 5, ""));
        timer.fire(config.coordinatorTimeoutMs());

        // Stale OK timer from the prior election should do nothing
        messenger.clear();
        oldOkTimeout.run();
        assertNull(node1.getCoordinatorId());
        assertEquals(0, messenger.sent.size());
    }

    @Test
    void testCrashBetweenCollectingAndSendingSendsNothingFurther() {
        BullyAlgorithm[] nodeRef = new BullyAlgorithm[1];
        List<ElectionMessage> sent = new ArrayList<>();
        ElectionMessenger crashingMessenger = (target, msg) -> {
            sent.add(msg);
            if (target == 2) {
                nodeRef[0].crash();
            }
        };
        nodeRef[0] = new BullyAlgorithm(1, List.of(1, 2, 3), config, crashingMessenger, listener, timer, nanoClock, wallClock);
        nodeRef[0].startElection();

        // Sent to 2, then crashed, so sending to 3 was skipped
        assertEquals(1, sent.size());
        assertTrue(nodeRef[0].isCrashed());
    }

    @Test
    void testNoEventForEveryMessage() {
        node1.startElection();
        assertEquals(1, listener.events.size());
        assertEquals(ElectionEventType.ELECTION_START, listener.events.get(0).type());
    }
}
