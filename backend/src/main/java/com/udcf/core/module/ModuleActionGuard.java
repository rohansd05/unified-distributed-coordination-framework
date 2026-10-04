package com.udcf.core.module;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Allows one long-running action at a time on a module (docs/HANDOFF.md 6.6). A second
 * {@link #begin} while busy throws {@link ModuleBusyException}, which the web layer maps to
 * HTTP 409.
 *
 * <pre>{@code
 * try (ModuleActionGuard.ActionTicket ticket = guard.begin("election")) {
 *     ...
 * }
 * }</pre>
 */
public class ModuleActionGuard {

    private final String moduleId;
    private ActionTicket current;   // guarded by this

    public ModuleActionGuard(String moduleId) {
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId must not be null");
    }

    /** @throws ModuleBusyException if another action is in progress */
    public synchronized ActionTicket begin(String action) {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
        if (current != null) {
            throw new ModuleBusyException(moduleId, current.action);
        }
        current = new ActionTicket(action);
        return current;
    }

    public synchronized boolean isBusy() {
        return current != null;
    }

    public synchronized Optional<String> currentAction() {
        return current == null ? Optional.empty() : Optional.of(current.action);
    }

    private synchronized void release(ActionTicket ticket) {
        if (current == ticket) {
            current = null;
        }
    }

    /** Proof that an action holds the guard; closing it releases the guard once. */
    public final class ActionTicket implements AutoCloseable {

        private final String action;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private ActionTicket(String action) {
            this.action = action;
        }

        public String action() {
            return action;
        }

        /** Releases the guard. Idempotent; a stale ticket never releases a newer action. */
        @Override
        public void close() {
            if (open.compareAndSet(true, false)) {
                release(this);
            }
        }
    }
}
