package com.udcf.modules.clocksync.berkeley;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

/**
 * Module-local role selector for choosing the Berkeley time daemon.
 *
 * <p>Until cluster-wide election roles are wired in Phase 9A, this selector deterministically
 * picks the lowest-id live node to serve as the daemon.</p>
 */
public final class ClockSyncRoleSelector {

    // TODO(L1): replaced by the shared role provider in Phase 9A
    private ClockSyncRoleSelector() {
    }

    /**
     * Chooses the time daemon from the set of active, live node IDs.
     *
     * @param liveNodeIds collection of live node IDs (must not be empty)
     * @return the selected daemon node ID (the lowest live ID)
     */
    public static int selectTimeDaemon(Collection<Integer> liveNodeIds) {
        // TODO(L1): replaced by the shared role provider in Phase 9A
        Objects.requireNonNull(liveNodeIds, "liveNodeIds must not be null");
        if (liveNodeIds.isEmpty()) {
            throw new IllegalArgumentException("liveNodeIds must not be empty");
        }
        return Collections.min(liveNodeIds);
    }
}
