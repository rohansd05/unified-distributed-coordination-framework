package com.udcf.core.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test double: a NodeService that binds nothing, records every lifecycle call, and can be
 * told to fail on start, crash, recover or stop.
 */
final class FakeNodeService implements NodeService {

    private final String name;
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean running;

    volatile boolean failStart;
    volatile boolean failCrash;
    volatile boolean failRecover;
    volatile boolean failStop;

    FakeNodeService(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void start() {
        calls.add("start");
        if (failStart) {
            throw new IllegalStateException("start failed (fake)");
        }
        running = true;
    }

    @Override
    public void crash() {
        calls.add("crash");
        if (failCrash) {
            throw new IllegalStateException("crash failed (fake)");
        }
        running = false;
    }

    @Override
    public void recover() {
        calls.add("recover");
        if (failRecover) {
            throw new IllegalStateException("recover failed (fake)");
        }
        running = true;
    }

    @Override
    public void stop() {
        calls.add("stop");
        running = false;
        if (failStop) {
            throw new IllegalStateException("stop failed (fake)");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Every lifecycle call so far, in order. */
    List<String> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    long count(String call) {
        return calls().stream().filter(call::equals).count();
    }
}
