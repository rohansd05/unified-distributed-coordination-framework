package com.udcf.modules.loadbalancing;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A run's crash plan as a result observer: once {@code afterServed} requests have been served,
 * it calls {@code crash} exactly once, on the client thread that served that request. An
 * atomic counter picks that one thread, however many clients run at once. Requests that no
 * worker served do not count.
 *
 * <p>The crash is injected by the module as part of the run. It is a real crash of the node
 * (through {@code Cluster.crash}), but nothing about it is automatic or caused by the
 * balancer. Covered by CrashTriggerTest.</p>
 */
final class CrashTrigger implements Consumer<DispatchResult> {

    private final int afterServed;
    private final BooleanSupplier crash;
    private final Consumer<Boolean> onTriggered;
    private final AtomicInteger served = new AtomicInteger();
    private volatile boolean triggered;
    private volatile boolean crashed;

    /**
     * @param afterServed the served request that triggers the crash, at least 1
     * @param crash       performs the crash; returns true if it crashed the node, false if the
     *                    node was already down
     * @param onTriggered told once, after the crash call, whether it crashed the node
     */
    CrashTrigger(int afterServed, BooleanSupplier crash, Consumer<Boolean> onTriggered) {
        if (afterServed < 1) {
            throw new IllegalArgumentException("afterServed must be >= 1, was " + afterServed);
        }
        this.afterServed = afterServed;
        this.crash = Objects.requireNonNull(crash, "crash must not be null");
        this.onTriggered = Objects.requireNonNull(onTriggered, "onTriggered must not be null");
    }

    @Override
    public void accept(DispatchResult result) {
        if (!result.succeeded() || served.incrementAndGet() != afterServed) {
            return;
        }
        boolean didCrash = crash.getAsBoolean();
        crashed = didCrash;
        triggered = true;
        onTriggered.accept(didCrash);
    }

    /** True once the served count reached {@code afterServed} and the crash was attempted. */
    boolean triggered() {
        return triggered;
    }

    /** True only if the crash call crashed the node. */
    boolean crashed() {
        return crashed;
    }
}
