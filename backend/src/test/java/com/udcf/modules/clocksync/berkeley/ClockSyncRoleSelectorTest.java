package com.udcf.modules.clocksync.berkeley;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClockSyncRoleSelectorTest {

    @Test
    @DisplayName("rejects null or empty live node collection")
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> ClockSyncRoleSelector.selectTimeDaemon(null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> ClockSyncRoleSelector.selectTimeDaemon(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    @DisplayName("selects lowest-id live node as time daemon")
    void selectsLowestIdLiveNode() {
        assertThat(ClockSyncRoleSelector.selectTimeDaemon(List.of(3, 1, 2))).isEqualTo(1);
        assertThat(ClockSyncRoleSelector.selectTimeDaemon(Set.of(4, 2, 5))).isEqualTo(2);
        assertThat(ClockSyncRoleSelector.selectTimeDaemon(List.of(3))).isEqualTo(3);
    }

    @Test
    @DisplayName("selects next lowest node when node 1 is absent/crashed")
    void selectsNextLowestWhenNode1Down() {
        assertThat(ClockSyncRoleSelector.selectTimeDaemon(List.of(2, 3))).isEqualTo(2);
        assertThat(ClockSyncRoleSelector.selectTimeDaemon(List.of(3))).isEqualTo(3);
    }
}
