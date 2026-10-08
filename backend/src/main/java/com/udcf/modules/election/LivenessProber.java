package com.udcf.modules.election;

import java.util.function.Consumer;

public interface LivenessProber {
    void probe(int targetId, long currentLamport, Consumer<Boolean> callback);
    void onProbeAck(int senderId);
}
