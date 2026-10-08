package com.udcf.modules.election;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

public class BullyAlgorithm implements ElectionParticipant {

    private final int nodeId;
    private final List<Integer> higherNodes;
    private final List<Integer> allNodes;
    private final ElectionConfig config;
    private final ElectionMessenger messenger;
    private final ElectionEventListener eventListener;
    private final ElectionTimer timer;
    private final Clock wallClock;

    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean crashed = new AtomicBoolean(false);
    private final AtomicBoolean electionInProgress = new AtomicBoolean(false);

    private long lamportTime = 0;
    private Integer coordinatorId = null;
    private long electionGeneration = 0;

    public BullyAlgorithm(
            int nodeId,
            List<Integer> allNodes,
            ElectionConfig config,
            ElectionMessenger messenger,
            ElectionEventListener eventListener,
            ElectionTimer timer,
            LongSupplier nanoClock,
            Clock wallClock) {
        this.nodeId = nodeId;
        this.allNodes = new ArrayList<>(allNodes);
        this.higherNodes = new ArrayList<>();
        for (int id : allNodes) {
            if (id > nodeId) {
                this.higherNodes.add(id);
            }
        }
        this.config = config;
        this.messenger = messenger;
        this.eventListener = eventListener;
        this.timer = timer;
        this.wallClock = wallClock;
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
        startElection();
    }

    public void startElection() {
        if (isCrashed()) return;
        if (!electionInProgress.compareAndSet(false, true)) {
            return;
        }

        List<Runnable> actions = new ArrayList<>();
        lock.lock();
        try {
            electionGeneration++;
            final long currentGen = electionGeneration;
            lamportTime++;
            final long lTime = lamportTime;
            actions.add(() -> emitEvent(ElectionEventType.ELECTION_START, -1, lTime, "Started election"));

            if (higherNodes.isEmpty()) {
                becomeCoordinator(actions);
            } else {
                for (int higherNode : higherNodes) {
                    lamportTime++;
                    final long sendTime = lamportTime;
                    actions.add(() -> messenger.send(higherNode, new ElectionMessage(ElectionMessageType.ELECTION, nodeId, sendTime, "")));
                }
                actions.add(() -> timer.schedule(() -> handleOkTimeout(currentGen), config.okTimeoutMs()));
            }
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
        boolean startedHere = false;
        lock.lock();
        try {
            lamportTime = Math.max(lamportTime, msg.lamportTime());

            switch (msg.type()) {
                case ELECTION -> {
                    lamportTime++;
                    final long sendTime = lamportTime;
                    actions.add(() -> messenger.send(msg.senderId(), new ElectionMessage(ElectionMessageType.OK, nodeId, sendTime, "")));
                    if (electionInProgress.compareAndSet(false, true)) {
                        startedHere = true;
                        electionGeneration++;
                        final long currentGen = electionGeneration;
                        lamportTime++;
                        final long lTime = lamportTime;
                        actions.add(() -> emitEvent(ElectionEventType.ELECTION_RESTART, msg.senderId(), lTime, "Started election due to received ELECTION"));
                        for (int higherNode : higherNodes) {
                            lamportTime++;
                            final long sTime = lamportTime;
                            actions.add(() -> messenger.send(higherNode, new ElectionMessage(ElectionMessageType.ELECTION, nodeId, sTime, "")));
                        }
                        actions.add(() -> timer.schedule(() -> handleOkTimeout(currentGen), config.okTimeoutMs()));
                    }
                }
                case OK -> {
                    if (!electionInProgress.get()) {
                        lamportTime++;
                        final long lTime = lamportTime;
                        actions.add(() -> emitEvent(ElectionEventType.LATE_MESSAGE, msg.senderId(), lTime, "Ignored late OK"));
                        break;
                    }
                    electionGeneration++; // invalidate previous timers
                    final long currentGen = electionGeneration;
                    lamportTime++;
                    final long lTime = lamportTime;
                    actions.add(() -> emitEvent(ElectionEventType.OK_RECEIVED, msg.senderId(), lTime, "Received OK, waiting for COORDINATOR"));
                    actions.add(() -> timer.schedule(() -> handleCoordinatorTimeout(currentGen), config.coordinatorTimeoutMs()));
                }
                case COORDINATOR -> {
                    coordinatorId = msg.senderId(); // legacy quirk: unconditionally accept
                    electionInProgress.set(false);
                    electionGeneration++; // cancel pending timeouts
                    lamportTime++;
                    final long lTime = lamportTime;
                    actions.add(() -> emitEvent(ElectionEventType.COORDINATOR_ACCEPTED, msg.senderId(), lTime, "Accepted Node " + msg.senderId() + " as COORDINATOR"));
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
        try {
            executeActions(actions, false);
        } catch (Exception e) {
            if (startedHere) {
                electionInProgress.set(false);
            }
            throw e;
        }
    }

    private void becomeCoordinator(List<Runnable> actions) {
        coordinatorId = nodeId;
        lamportTime++;
        final long lTime = lamportTime;
        actions.add(() -> emitEvent(ElectionEventType.ELECTED, nodeId, lTime, "Elected as coordinator"));
        for (int targetNode : allNodes) {
            if (targetNode == nodeId) continue;
            lamportTime++;
            final long sTime = lamportTime;
            actions.add(() -> messenger.send(targetNode, new ElectionMessage(ElectionMessageType.COORDINATOR, nodeId, sTime, String.valueOf(nodeId))));
        }
        electionInProgress.set(false);
    }

    private void handleOkTimeout(long generation) {
        if (isCrashed()) return;
        List<Runnable> actions = new ArrayList<>();
        lock.lock();
        try {
            if (generation != electionGeneration || !electionInProgress.get()) return;
            becomeCoordinator(actions);
        } finally {
            lock.unlock();
        }
        executeActions(actions, false);
    }

    private void handleCoordinatorTimeout(long generation) {
        if (isCrashed()) return;
        List<Runnable> actions = new ArrayList<>();
        lock.lock();
        try {
            if (generation != electionGeneration || !electionInProgress.get()) return;
            electionInProgress.set(false);
            lamportTime++;
            final long lTime = lamportTime;
            actions.add(() -> emitEvent(ElectionEventType.ELECTION_TIMEOUT, -1, lTime, "Coordinator timeout"));
        } finally {
            lock.unlock();
        }
        executeActions(actions, false);
        startElection(); // retry
    }

    private void executeActions(List<Runnable> actions, boolean isCrashEvent) {
        for (Runnable action : actions) {
            if (!isCrashEvent && isCrashed()) {
                break; // A node that crashes between collecting and sending sends nothing further
            }
            action.run();
        }
    }

    private void emitEvent(ElectionEventType type, int peerId, long lTime, String desc) {
        eventListener.onEvent(new ElectionEvent(nodeId, type, lTime, peerId, desc, wallClock.instant()));
    }
}
