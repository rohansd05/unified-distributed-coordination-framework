package com.udcf.modules.replication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplicationRoleSelectorTest {

    @Test
    @DisplayName("rejects a null or empty live node collection")
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> ReplicationRoleSelector.selectPrimary(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReplicationRoleSelector.selectPrimary(List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must not be empty");
    }

    @Test
    @DisplayName("the primary is the lowest-id live node")
    void selectsLowestLive() {
        assertThat(ReplicationRoleSelector.selectPrimary(List.of(3, 1, 2))).isEqualTo(1);
        assertThat(ReplicationRoleSelector.selectPrimary(Set.of(4, 2, 5))).isEqualTo(2);
    }

    @Test
    @DisplayName("with node 1 crashed, the next lowest live node is the primary")
    void selectsNextLowestWhenNode1Down() {
        assertThat(ReplicationRoleSelector.selectPrimary(List.of(2, 3))).isEqualTo(2);
        assertThat(ReplicationRoleSelector.selectPrimary(List.of(3))).isEqualTo(3);
    }

    @Test
    @DisplayName("backups are every other node, crashed ones included, ascending and distinct")
    void backupsOfExcludesPrimarySortedIncludesCrashed() {
        // Nodes 1-5, node 4 crashed: still a backup.
        assertThat(ReplicationRoleSelector.backupsOf(1, List.of(5, 3, 1, 4, 2, 3))).containsExactly(2, 3, 4, 5);
        assertThat(ReplicationRoleSelector.backupsOf(3, List.of(3))).isEmpty();
    }

    @Test
    @DisplayName("backupsOf rejects a primary that is not one of the nodes")
    void backupsOfRejectsUnknownPrimary() {
        assertThatThrownBy(() -> ReplicationRoleSelector.backupsOf(9, List.of(1, 2)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("primary 9");
        assertThatThrownBy(() -> ReplicationRoleSelector.backupsOf(1, null)).isInstanceOf(NullPointerException.class);
    }
}
