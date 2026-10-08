package com.udcf.modules.election;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pure Ring election algorithm.
 *
 * <p><strong>Important Concurrency Rule:</strong> The original deadlock occurred because 
 * probes blocked the listener thread. This algorithm is now fully callback-driven.
 * No thread ever blocks waiting for a probe, so the executor is no longer needed.</p>
 */
public class RingAlgorithm implements ElectionParticipant {

    private final int nodeId;
    private final List<Integer> ringOrder;
    private final ElectionConfig config;
    private final ElectionMessenger messenger;
    private final ElectionEventListener eventListener;
    private final ElectionTimer timer;
    private final Clock wallClock;
    private final LivenessProber prober;

    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean crashed = new AtomicBoolean(false);
    private final AtomicBoolean electionInProgress = new AtomicBoolean(false);

    private long lamportTime = 0;
    private Integer coordinatorId = null;
    private long electionGeneration = 0;

    /**
     * @param prober Used to verify successor liveness via asynchronous callbacks.
     */
    public RingAlgorithm(
            int nodeId,
            List<Integer> ringOrder,
            ElectionConfig config,
            ElectionMessenger messenger,
            ElectionEventListener eventListener,
            ElectionTimer timer,
            Clock wallClock,
            LivenessProber prober) {
        this.nodeId = nodeId;
        this.ringOrder = new ArrayList<>(ringOrder);
        this.config = config;
        this.messenger = messenger;
        this.eventListener = eventListener;
        this.timer = timer;
        this.wallClock = wallClock;
        this.prober = prober;
    }

    @Override
    public int getNodeId() {
        return nodeId;
    }

    @Override
    public boolean isCrashed() {
        return crashed.get();
    }

    @Override
    public Integer getCoordinatorId() {
        return coordinatorId;
    }

    public void crash() {
        crashed.set(true);
        List<Runnable> actions = new ArrayList<>();
        lock.lock();
        try {
            coordinatorId = null;
            electionInProgress.set(false);
            electionGeneration++;
            lamportTime++;
            final long lTime = lamportTime;
            actions.add(() -> emitEvent(ElectionEventType.CRASH, -1, lTime, "Node crashed"));
        } finally {
            lock.unlock();
        }
        executeActions(actions, true);
    }

    public void recover() {
        crashed.set(false);
        List<Runnable> actions = new ArrayList<>();
        lock.lock();
        try {
            electionGeneration++;
            lamportTime++;
            final long lTime = lamportTime;
            actions.add(() -> emitEvent(ElectionEventType.RECOVER, -1, lTime, "Node recovered"));
        } finally {
            lock.unlock();
        }
        executeActions(actions, false);
    }

    public void startElection() {
        if (isCrashed()) return;
        if (!electionInProgress.compareAndSet(false, true)) {
            return;
        }

        List<Runnable> actions = new ArrayList<>();
        long currentGen;
        lock.lock();
        try {
            electionGeneration++;
            currentGen = electionGeneration;
            lamportTime++;
            final long lTime = lamportTime;
            actions.add(() -> emitEvent(ElectionEventType.ELECTION_START, -1, lTime, "Started ring election"));

            String payload = String.valueOf(nodeId);
            actions.add(() -> offloadForwardRing(ElectionMessageType.RING_ELECTION, payload, currentGen));
            actions.add(() -> timer.schedule(() -> handleCompletionTimeout(currentGen), config.ringCompletionTimeoutMs()));
        } finally {
            lock.unlock();
        }
        try {
            executeActions(actions, false);
        } catch (Exception e) {
            electionInProgress.set(false);
            throw e;
        }
    }

    public void onReceive(ElectionMessage msg) {
        if (isCrashed()) return;
        List<Runnable> actions = new ArrayList<>();
        lock.lock();
        try {
            lamportTime = Math.max(lamportTime, msg.lamportTime());

            switch (msg.type()) {
                case RING_ELECTION -> {
                    List<Integer> token = parseToken(msg.payload());
                    if (token.contains(nodeId)) {
                        int winner = token.stream().mapToInt(Integer::intValue).max().orElse(nodeId);
                        coordinatorId = winner;
                        electionInProgress.set(false);
                        electionGeneration++;
                        lamportTime++;
                        final long lTime = lamportTime;
                        actions.add(() -> emitEvent(ElectionEventType.ELECTED, -1, lTime, "Highest ID " + winner + " wins"));

                        final long currentGen = electionGeneration;
                        actions.add(() -> offloadForwardRing(ElectionMessageType.RING_COORDINATOR, winner + ":" + nodeId, currentGen));
                    } else {
                        token.add(nodeId);
                        String forwarded = joinToken(token);
                        final long currentGen = electionGeneration;
                        actions.add(() -> offloadForwardRing(ElectionMessageType.RING_ELECTION, forwarded, currentGen));
                    }
                }
                case RING_COORDINATOR -> {
                    String[] halves = msg.payload().split(":");
                    int winner = Integer.parseInt(halves[0]);
                    int originator = Integer.parseInt(halves[1]);

                    if (originator == nodeId) {
                        electionInProgress.set(false);
                        lamportTime++;
                        final long lTime = lamportTime;
                        actions.add(() -> emitEvent(ElectionEventType.COORDINATOR_ACCEPTED, winner, lTime, "Result token has circulated the whole ring"));
                        break;
                    }

                    coordinatorId = winner;
                    electionInProgress.set(false);
                    lamportTime++;
                    final long lTime = lamportTime;
                    actions.add(() -> emitEvent(ElectionEventType.COORDINATOR_ACCEPTED, winner, lTime, "Accepted Node " + winner + " as COORDINATOR"));

                    final long currentGen = electionGeneration;
                    actions.add(() -> offloadForwardRing(ElectionMessageType.RING_COORDINATOR, msg.payload(), currentGen));
                }
                case PROBE -> {
                    lamportTime++;
                    final long sendTime = lamportTime;
                    actions.add(() -> messenger.send(msg.senderId(), new ElectionMessage(ElectionMessageType.PROBE_ACK, nodeId, sendTime, "")));
                }
                case PROBE_ACK -> {
                    prober.onProbeAck(msg.senderId());
                }
                default -> {
                    lamportTime++;
                    final long lTime = lamportTime;
                    actions.add(() -> emitEvent(ElectionEventType.UNKNOWN_SENDER, msg.senderId(), lTime, "Unknown message type: " + msg.type()));
                }
            }
        } finally {
            lock.unlock();
        }
        executeActions(actions, false);
    }

    private void offloadForwardRing(ElectionMessageType type, String payload, long currentGen) {
        if (isCrashed()) return;

        int myIndex = ringOrder.indexOf(nodeId);
        if (myIndex == -1) return;

        tryNextCandidate(type, payload, currentGen, myIndex, 1);
    }

    private void tryNextCandidate(ElectionMessageType type, String payload, long currentGen, int myIndex, int offset) {
        if (offset >= ringOrder.size()) {
            return; // No one left to try
        }
        
        long currentLamport;
        lock.lock();
        try {
            if (isCrashed()) return;
            lamportTime++;
            currentLamport = lamportTime;
        } finally {
            lock.unlock();
        }

        int candidate = ringOrder.get((myIndex + offset) % ringOrder.size());

        prober.probe(candidate, currentLamport, (alive) -> {
            List<Runnable> actions = new ArrayList<>();
            lock.lock();
            try {
                if (isCrashed()) return;
                lamportTime++;
                final long lTime = lamportTime;
                
                if (alive) {
                    actions.add(() -> messenger.send(candidate, new ElectionMessage(type, nodeId, lTime, payload)));
                    if (type == ElectionMessageType.RING_ELECTION) {
                        actions.add(() -> emitEvent(ElectionEventType.TOKEN_FORWARDED, candidate, lTime, "Forwarded token to " + candidate));
                    }
                } else {
                    actions.add(() -> emitEvent(ElectionEventType.DEAD_NODE_SKIPPED, candidate, lTime, "Skipped dead node " + candidate));
                }
            } finally {
                lock.unlock();
            }
            executeActions(actions, false);

            if (!alive) {
                // recursively try the next candidate
                tryNextCandidate(type, payload, currentGen, myIndex, offset + 1);
            }
        });
    }

    private void handleCompletionTimeout(long generation) {
        if (isCrashed()) return;
        List<Runnable> actions = new ArrayList<>();
        lock.lock();
        try {
            if (generation != electionGeneration || !electionInProgress.get()) return;
            electionInProgress.set(false);
            lamportTime++;
            final long lTime = lamportTime;
            actions.add(() -> emitEvent(ElectionEventType.ELECTION_TIMEOUT, -1, lTime, "Ring completion timeout"));
        } finally {
            lock.unlock();
        }
        executeActions(actions, false);
    }

    private void executeActions(List<Runnable> actions, boolean isCrashEvent) {
        for (Runnable action : actions) {
            if (!isCrashEvent && isCrashed()) {
                break;
            }
            action.run();
        }
    }

    private void emitEvent(ElectionEventType type, int peerId, long lTime, String desc) {
        eventListener.onEvent(new ElectionEvent(nodeId, type, lTime, peerId, desc, wallClock.instant()));
    }

    private List<Integer> parseToken(String payload) {
        List<Integer> token = new ArrayList<>();
        for (String part : payload.split(",")) {
            if (!part.isBlank()) {
                token.add(Integer.parseInt(part.trim()));
            }
        }
        return token;
    }

    private String joinToken(List<Integer> token) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < token.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(token.get(i));
        }
        return sb.toString();
    }
}
