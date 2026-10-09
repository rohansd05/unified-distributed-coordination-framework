package com.udcf.modules.faulttolerance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.udcf.modules.faulttolerance.FailoverRole.BACKUP;
import static com.udcf.modules.faulttolerance.FailoverRole.PRIMARY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The split-brain checker on valid and violating snapshots. */
class SplitBrainCheckerTest {

    @Test
    @DisplayName("one live primary at the highest epoch passes")
    void singlePrimaryPasses() {
        SplitBrainReport report = SplitBrainChecker.check(List.of(
                node(1, true, BACKUP, 3), node(4, true, PRIMARY, 3), node(5, false, BACKUP, 2)));

        assertThat(report.passed()).isTrue();
        assertThat(report.highestEpoch()).isEqualTo(3);
        assertThat(report.livePrimaries()).containsExactly(4);
        assertThat(report.violations()).isEmpty();
    }

    @Test
    @DisplayName("no live primary during an outage is not a violation")
    void outagePasses() {
        SplitBrainReport report = SplitBrainChecker.check(List.of(node(1, true, BACKUP, 2), node(5, false, PRIMARY, 2)));

        assertThat(report.passed()).isTrue();
        assertThat(report.livePrimaries()).isEmpty();
    }

    @Test
    @DisplayName("two live primaries at the same epoch are a duplicate")
    void duplicatePrimary() {
        SplitBrainReport report = SplitBrainChecker.check(List.of(
                node(3, true, PRIMARY, 3), node(1, true, BACKUP, 3), node(2, true, PRIMARY, 3)));

        assertThat(report.passed()).isFalse();
        assertThat(report.violations()).containsExactly(
                new SplitBrainViolation(SplitBrainViolation.Kind.DUPLICATE_PRIMARY, List.of(2, 3), 3, 3));
    }

    @Test
    @DisplayName("a live primary below a backup's epoch is a stale primary, and fails the check")
    void stalePrimary() {
        SplitBrainReport report = SplitBrainChecker.check(List.of(node(5, true, PRIMARY, 2), node(1, true, BACKUP, 3)));

        assertThat(report.passed()).isFalse();
        assertThat(report.violations()).containsExactly(
                new SplitBrainViolation(SplitBrainViolation.Kind.STALE_PRIMARY, List.of(5), 2, 3));
    }

    @Test
    @DisplayName("a crashed node still believing it is primary is ignored")
    void crashedStalePrimaryIgnored() {
        SplitBrainReport report = SplitBrainChecker.check(List.of(
                node(5, false, PRIMARY, 2), node(4, true, PRIMARY, 3), node(1, true, BACKUP, 3)));

        assertThat(report.passed()).isTrue();
        assertThat(report.livePrimaries()).containsExactly(4);
    }

    @Test
    @DisplayName("both violations are reported together: duplicates first, then stale primaries")
    void bothViolations() {
        SplitBrainReport report = SplitBrainChecker.check(List.of(
                node(1, true, PRIMARY, 2), node(2, true, PRIMARY, 2), node(3, true, PRIMARY, 4), node(4, true, BACKUP, 4)));

        assertThat(report.violations()).extracting(SplitBrainViolation::kind).containsExactly(
                SplitBrainViolation.Kind.DUPLICATE_PRIMARY,
                SplitBrainViolation.Kind.STALE_PRIMARY,
                SplitBrainViolation.Kind.STALE_PRIMARY);
        assertThat(report.violations().get(1).nodeIds()).containsExactly(1);
        assertThat(report.violations().get(2).nodeIds()).containsExactly(2);
        assertThat(report.livePrimaries()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("an empty or all-crashed snapshot passes with no highest epoch; a node listed twice is rejected")
    void emptyAndDuplicateNodes() {
        assertThat(SplitBrainChecker.check(List.of()).highestEpoch()).isNull();
        assertThat(SplitBrainChecker.check(List.of(node(1, false, PRIMARY, 2))).passed()).isTrue();
        assertThatThrownBy(() -> SplitBrainChecker.check(List.of(node(1, true, BACKUP, 2), node(1, true, BACKUP, 2))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static NodeRoleSnapshot node(int id, boolean alive, FailoverRole role, long epoch) {
        return new NodeRoleSnapshot(id, alive, role, epoch);
    }
}
