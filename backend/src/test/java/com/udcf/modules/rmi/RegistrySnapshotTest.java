package com.udcf.modules.rmi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrySnapshotTest {

    private static final RegistrySnapshot.BoundName NODE = new RegistrySnapshot.BoundName("NodeEndpoint", "jdk.proxy2.$Proxy1");
    private static final RegistrySnapshot.BoundName COLLECTIVE =
            new RegistrySnapshot.BoundName("CollectiveEndpoint", "jdk.proxy2.$Proxy2");

    @Test
    @DisplayName("names are kept sorted by name, in a detached unmodifiable copy")
    void sortedAndDetached() {
        List<RegistrySnapshot.BoundName> input = new ArrayList<>(List.of(NODE, COLLECTIVE));
        RegistrySnapshot snapshot = new RegistrySnapshot(2, 1102, input);
        input.clear();

        assertThat(snapshot.names()).containsExactly(COLLECTIVE, NODE);
        assertThatThrownBy(() -> snapshot.names().add(NODE)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new RegistrySnapshot(2, 1102, List.of()).names()).isEmpty();
    }

    @Test
    @DisplayName("a name bound twice is rejected")
    void duplicatesRejected() {
        RegistrySnapshot.BoundName again = new RegistrySnapshot.BoundName("NodeEndpoint", "Other");
        assertThatThrownBy(() -> new RegistrySnapshot(2, 1102, List.of(NODE, again)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("name bound twice: NodeEndpoint");
    }

    @Test
    @DisplayName("find and contains look a name up exactly")
    void findAndContains() {
        RegistrySnapshot snapshot = new RegistrySnapshot(2, 1102, List.of(NODE));

        assertThat(snapshot.find(NodeEndpoint.BOUND_NAME)).contains(NODE);
        assertThat(snapshot.contains("NodeEndpoint")).isTrue();
        assertThat(snapshot.contains("nodeendpoint")).isFalse();
        assertThat(snapshot.find("CollectiveEndpoint")).isEmpty();
    }

    @Test
    @DisplayName("node id, port, names and entries are validated")
    void validation() {
        assertThatThrownBy(() -> new RegistrySnapshot(0, 1102, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegistrySnapshot(1, 0, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegistrySnapshot(1, 65536, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegistrySnapshot(1, 1101, null)).isInstanceOf(NullPointerException.class);
        List<RegistrySnapshot.BoundName> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> new RegistrySnapshot(1, 1101, withNull)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RegistrySnapshot.BoundName(" ", "Stub")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegistrySnapshot.BoundName("Name", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegistrySnapshot.BoundName(null, "Stub")).isInstanceOf(IllegalArgumentException.class);
    }
}
