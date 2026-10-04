package com.udcf.core.cluster;

import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.core.module.ExperimentModule;
import com.udcf.core.module.ModuleBusyException;
import com.udcf.core.module.ModuleRegistry;
import com.udcf.core.module.ModuleStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Restores the whole system to a clean demonstration state.
 *
 * <p>A reset is a clean slate: every node is recovered, every module is reset, every
 * Lamport clock returns to zero and the event history is cleared. Clearing the history is
 * what makes resetting the clocks safe: no remaining event can be ordered against the new
 * timeline. Exactly one event survives, {@code CLUSTER_RESET} at cluster Lamport time 1.</p>
 *
 * <p>Resets are serialised by this service's own lock. A reset is refused, with nothing
 * changed, while any module reports {@link ModuleStatus#BUSY}.</p>
 */
@Component
public class ClusterResetService {

    static final String BUSY_ACTION = "a long-running action";

    private final Cluster cluster;
    private final ClusterEventBus bus;
    private final ModuleRegistry modules;
    private final ReentrantLock lock = new ReentrantLock();

    public ClusterResetService(Cluster cluster, ClusterEventBus bus, ModuleRegistry modules) {
        this.cluster = cluster;
        this.bus = bus;
        this.modules = modules;
    }

    /** @throws ModuleBusyException if any module is busy; nothing is changed in that case */
    public void reset() {
        lock.lock();
        try {
            for (ExperimentModule module : modules.modules()) {
                if (module.status() == ModuleStatus.BUSY) {
                    throw new ModuleBusyException(module.id(), BUSY_ACTION);
                }
            }
            cluster.recoverAll();
            modules.modules().forEach(ExperimentModule::reset);
            cluster.resetClocks();
            bus.clearHistory();
            bus.publish(EventDraft.of(ClusterNode.MODULE, 0, "CLUSTER_RESET", cluster.clusterClock().tick())
                    .withMessage("Cluster reset to a clean state")
                    .withData(Map.of("size", cluster.size())));
        } finally {
            lock.unlock();
        }
    }
}
