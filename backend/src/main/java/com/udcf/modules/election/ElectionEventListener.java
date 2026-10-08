package com.udcf.modules.election;

@FunctionalInterface
public interface ElectionEventListener {
    void onEvent(ElectionEvent event);
}
