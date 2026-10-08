package com.udcf.modules.replication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsistencyCheckTest {

    private static DataItem item(String key, String value, long lamport, int origin, long epoch) {
        return new DataItem(key, value, lamport, origin, epoch);
    }

    private static final DataItem A = item("a", "1", 5, 1, 1);
    private static final DataItem B = item("b", "2", 6, 1, 1);

    @Test
    @DisplayName("identical replicas are consistent, and every other replica is listed as compared")
    void identicalReplicasConsistent() {
        Map<String, DataItem> store = Map.of("a", A, "b", B);
        ConsistencyReport r = ConsistencyCheck.compare(1, Map.of(1, store, 3, store, 2, store));
        assertThat(r.consistent()).isTrue();
        assertThat(r.referenceNodeId()).isEqualTo(1);
        assertThat(r.comparedNodeIds()).containsExactly(2, 3);
    }

    @Test
    @DisplayName("a key the replica lacks is MISSING")
    void missingDetected() {
        ConsistencyReport r = ConsistencyCheck.compare(1, Map.of(1, Map.of("a", A, "b", B), 2, Map.of("a", A)));
        assertThat(r.consistent()).isFalse();
        assertThat(r.divergences()).containsExactly(new ReplicaDivergence(2, "b", B, null, DivergenceKind.MISSING));
    }

    @Test
    @DisplayName("an older version on the replica is STALE")
    void staleDetected() {
        DataItem old = item("a", "0", 4, 1, 1);
        ConsistencyReport r = ConsistencyCheck.compare(1, Map.of(1, Map.of("a", A), 2, Map.of("a", old)));
        assertThat(r.divergences()).containsExactly(new ReplicaDivergence(2, "a", A, old, DivergenceKind.STALE));
    }

    @Test
    @DisplayName("a newer version, or a key only the replica holds, is AHEAD")
    void aheadDetectedIncludingExtraKey() {
        DataItem newer = item("a", "9", 9, 2, 1);
        ConsistencyReport r = ConsistencyCheck.compare(1, Map.of(1, Map.of("a", A), 2, Map.of("a", newer, "b", B)));
        assertThat(r.divergences()).containsExactly(
                new ReplicaDivergence(2, "a", A, newer, DivergenceKind.AHEAD),
                new ReplicaDivergence(2, "b", null, B, DivergenceKind.AHEAD));
    }

    @Test
    @DisplayName("the same version with another value is CONFLICT, never STALE or AHEAD")
    void conflictReportedAsConflictNeverStaleOrAhead() {
        DataItem forged = item("a", "forged", 5, 1, 1);
        ConsistencyReport only = ConsistencyCheck.compare(1, Map.of(1, Map.of("a", A), 2, Map.of("a", forged)));
        assertThat(only.divergences()).containsExactly(new ReplicaDivergence(2, "a", A, forged, DivergenceKind.CONFLICT));
        assertThat(only.count(DivergenceKind.MISSING)).isZero();
        assertThat(only.count(DivergenceKind.STALE)).isZero();
        assertThat(only.count(DivergenceKind.AHEAD)).isZero();
    }

    @Test
    @DisplayName("a CONFLICT does not change the MISSING, STALE or AHEAD counts on the same replica")
    void conflictDoesNotChangeOtherCounts() {
        DataItem forged = item("a", "forged", 5, 1, 1);
        DataItem c = item("c", "3", 7, 1, 1);
        DataItem d = item("d", "4", 8, 1, 1);
        Map<String, DataItem> reference = Map.of("a", A, "b", B, "c", c, "d", d);
        Map<String, DataItem> replica = Map.of(
                "a", forged,                     // conflict
                "c", item("c", "old", 1, 1, 1),  // stale
                "d", item("d", "new", 1, 1, 2)); // ahead (newer epoch); b is missing
        ConsistencyReport mixed = ConsistencyCheck.compare(1, Map.of(1, reference, 2, replica));
        assertThat(mixed.count(DivergenceKind.CONFLICT)).isEqualTo(1);
        assertThat(mixed.count(DivergenceKind.MISSING)).isEqualTo(1);
        assertThat(mixed.count(DivergenceKind.STALE)).isEqualTo(1);
        assertThat(mixed.count(DivergenceKind.AHEAD)).isEqualTo(1);
        assertThat(mixed.divergences()).extracting(ReplicaDivergence::key).containsExactly("a", "b", "c", "d");
    }

    @Test
    @DisplayName("the reference replica must be present")
    void referenceMustBePresent() {
        assertThatThrownBy(() -> ConsistencyCheck.compare(1, Map.of(2, Map.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reference node 1");
        assertThatThrownBy(() -> ConsistencyCheck.compare(1, null)).isInstanceOf(NullPointerException.class);
    }
}
