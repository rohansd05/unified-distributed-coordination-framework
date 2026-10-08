package com.udcf.modules.election;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Redesigned callback-driven liveness prober.
 * 
 * <p><strong>Deadlock Prevention:</strong> The original legacy demo suffered from a deadlock 
 * because the listener thread blocked waiting for a PROBE_ACK that only it could deliver. 
 * This design prevents that by using asynchronous callbacks: {@code probe} sends the message 
 * and schedules a timeout, returning immediately. No thread ever waits or blocks. When the 
 * ACK arrives or the timer fires, the callback is executed exactly once.</p>
 */
public class DefaultLivenessProber implements LivenessProber {

    private final int nodeId;
    private final ElectionMessenger messenger;
    private final ElectionTimer timer;
    private final long timeoutMs;
    
    private final ConcurrentHashMap<Integer, Consumer<Boolean>> pendingProbes = new ConcurrentHashMap<>();

    public DefaultLivenessProber(int nodeId, ElectionMessenger messenger, ElectionTimer timer, long timeoutMs) {
        this.nodeId = nodeId;
        this.messenger = messenger;
        this.timer = timer;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public void onProbeAck(int senderId) {
        Consumer<Boolean> callback = pendingProbes.remove(senderId);
        if (callback != null) {
            callback.accept(true);
        }
    }

    @Override
    public void probe(int targetId, long currentLamport, Consumer<Boolean> callback) {
        pendingProbes.put(targetId, callback);
        
        messenger.send(targetId, new ElectionMessage(ElectionMessageType.PROBE, nodeId, currentLamport, ""));
        
        timer.schedule(() -> {
            Consumer<Boolean> pending = pendingProbes.remove(targetId);
            if (pending != null) {
                pending.accept(false);
            }
        }, timeoutMs);
    }
}
