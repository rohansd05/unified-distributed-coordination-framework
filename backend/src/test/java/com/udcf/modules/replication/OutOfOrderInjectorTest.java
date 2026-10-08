package com.udcf.modules.replication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutOfOrderInjectorTest {

    @Test
    @DisplayName("the stale copy is strictly older, with the same key, epoch and origin and the given value")
    void staleCopyStrictlyOlderSameKeyEpochOrigin() {
        DataItem current = new DataItem("balance", "4000", 20, 1, 2);
        DataItem stale = OutOfOrderInjector.staleVersionOf(current, "STALE-999");
        assertThat(stale).isEqualTo(new DataItem("balance", "STALE-999", 19, 1, 2));
        assertThat(current.isNewerThan(stale)).isTrue();
        assertThat(stale.isNewerThan(current)).isFalse();
    }

    @Test
    @DisplayName("delivering the stale copy to a store holding the current version is STALE and changes nothing")
    void appliedStaleCopyIsRejected() {
        DataStore store = new DataStore();
        DataItem current = new DataItem("balance", "4000", 20, 1, 1);
        store.apply(current);
        assertThat(store.apply(OutOfOrderInjector.staleVersionOf(current, "STALE-999"))).isEqualTo(ApplyResult.STALE);
        assertThat(store.get("balance")).contains(current);
    }

    @Test
    @DisplayName("boundary: a current Lamport time of 1 gives a stale copy at 0")
    void lamportOneBoundaryGivesZero() {
        DataItem stale = OutOfOrderInjector.staleVersionOf(new DataItem("k", "v", 1, 3, 1), "old");
        assertThat(stale.lamportTime()).isZero();
        assertThat(new DataItem("k", "v", 1, 3, 1).isNewerThan(stale)).isTrue();
    }

    @Test
    @DisplayName("a current item with Lamport time 0 has no older version and is rejected")
    void lamportZeroRejected() {
        assertThatThrownBy(() -> OutOfOrderInjector.staleVersionOf(new DataItem("k", "v", 0, 3, 1), "old"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no older version");
    }

    @Test
    @DisplayName("a null current item, or an invalid stale value, is rejected")
    void nullAndInvalidRejected() {
        assertThatThrownBy(() -> OutOfOrderInjector.staleVersionOf(null, "old")).isInstanceOf(NullPointerException.class);
        DataItem current = new DataItem("k", "v", 5, 1, 1);
        assertThatThrownBy(() -> OutOfOrderInjector.staleVersionOf(current, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutOfOrderInjector.staleVersionOf(current, "a\nb")).isInstanceOf(IllegalArgumentException.class);
    }
}
