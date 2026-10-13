package com.udcf.modules.mpi;

import com.udcf.modules.mpi.dto.RootSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The root rule: requested, then the live leader, then the lowest live node id. Pure; no socket. */
class MpiRoleSelectorTest {

    @Test
    @DisplayName("a requested root wins, even over a live leader")
    void requestedWins() {
        assertThat(MpiRoleSelector.select(2, Optional.of(3), Set.of(1, 2, 3)))
                .contains(new MpiRoleSelector.Choice(2, RootSource.REQUESTED));
    }

    @Test
    @DisplayName("without a request, a live leader is the root")
    void liveLeader() {
        assertThat(MpiRoleSelector.select(null, Optional.of(3), Set.of(1, 2, 3)))
                .contains(new MpiRoleSelector.Choice(3, RootSource.LEADER));
    }

    @Test
    @DisplayName("a leader outside the live snapshot is ignored: the lowest live id is the root")
    void leaderNotLive() {
        assertThat(MpiRoleSelector.select(null, Optional.of(1), Set.of(2, 3)))
                .contains(new MpiRoleSelector.Choice(2, RootSource.LOWEST_LIVE_ID));
    }

    @Test
    @DisplayName("without a request or a leader, the lowest live id is the root, whatever the input order")
    void lowestLive() {
        assertThat(MpiRoleSelector.select(null, Optional.empty(), List.of(5, 3, 4)))
                .contains(new MpiRoleSelector.Choice(3, RootSource.LOWEST_LIVE_ID));
    }

    @Test
    @DisplayName("nothing requested and no live node: no root")
    void noLiveNode() {
        assertThat(MpiRoleSelector.select(null, Optional.empty(), Set.of())).isEmpty();
    }
}
