package com.udcf.modules.election;

@FunctionalInterface
public interface ElectionMessenger {
    void send(int targetNodeId, ElectionMessage message);
}
