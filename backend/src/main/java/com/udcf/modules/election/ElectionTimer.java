package com.udcf.modules.election;

@FunctionalInterface
public interface ElectionTimer {
    Cancellable schedule(Runnable action, long delayMillis);

    @FunctionalInterface
    interface Cancellable {
        void cancel();
    }
}
