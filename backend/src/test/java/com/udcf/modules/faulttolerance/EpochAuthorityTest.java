package com.udcf.modules.faulttolerance;

import com.udcf.modules.replication.DataStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Promotion epochs: issued once per election result, always above every epoch known. */
class EpochAuthorityTest {

    private static final long MS = 1_000_000L;

    private final EpochAuthority authority = new EpochAuthority();

    @Test
    @DisplayName("the first promotion is above the initial epoch and replaces nobody")
    void firstPromotionIsAboveInitialEpoch() {
        Promotion first = authority.onLeaderElected(5, 10 * MS).orElseThrow();

        assertThat(first.epoch()).isEqualTo(DataStore.INITIAL_EPOCH + 1).isGreaterThan(DataStore.INITIAL_EPOCH);
        assertThat(first.nodeId()).isEqualTo(5);
        assertThat(first.previousPrimaryId()).isNull();
        assertThat(first.observedAtNanos()).isEqualTo(10 * MS);
        assertThat(authority.current()).contains(first);
    }

    @Test
    @DisplayName("two promotions raise the epoch by two, each recording the primary it replaces")
    void twoPromotionsGivePlusTwo() {
        long start = authority.highestEpoch();
        Promotion first = authority.onLeaderElected(5, 10 * MS).orElseThrow();
        Promotion second = authority.onLeaderElected(4, 20 * MS).orElseThrow();

        assertThat(second.epoch()).isEqualTo(first.epoch() + 1).isEqualTo(start + 2);
        assertThat(second.previousPrimaryId()).isEqualTo(5);
        assertThat(authority.highestEpoch()).isEqualTo(start + 2);
    }

    @Test
    @DisplayName("the same election result applied twice promotes once")
    void sameResultTwicePromotesOnce() {
        Promotion first = authority.onLeaderElected(4, 10 * MS).orElseThrow();

        assertThat(authority.onLeaderElected(4, 10 * MS)).isEmpty();
        assertThat(authority.onLeaderElected(4, 30 * MS)).isEmpty();
        assertThat(authority.current()).contains(first);
        assertThat(authority.highestEpoch()).isEqualTo(first.epoch());
    }

    @Test
    @DisplayName("a result observed before the promotion in force is stale and ignored")
    void staleResultIgnored() {
        Promotion current = authority.onLeaderElected(4, 50 * MS).orElseThrow();

        assertThat(authority.onLeaderElected(3, 40 * MS)).isEmpty();
        assertThat(authority.current()).contains(current);
        assertThat(authority.onLeaderElected(3, 50 * MS)).hasValueSatisfying(p -> assertThat(p.nodeId()).isEqualTo(3));
    }

    @Test
    @DisplayName("an observed epoch makes the next promotion exceed it; a lower one never lowers anything")
    void observedEpochsAreExceeded() {
        assertThat(authority.observeEpoch(7)).isEqualTo(7);
        assertThat(authority.observeEpoch(3)).isEqualTo(7);

        assertThat(authority.onLeaderElected(2, MS).orElseThrow().epoch()).isEqualTo(8);
        assertThat(authority.observeEpoch(4)).isEqualTo(8);
        assertThat(authority.onLeaderElected(3, 2 * MS).orElseThrow().epoch()).isEqualTo(9);
    }

    @Test
    @DisplayName("a promotion replacing a primary known at epoch E always gets an epoch above E")
    void replacingPrimaryAlwaysExceedsItsEpoch() {
        long at = 0;
        int node = 1;
        for (long knownEpoch : new long[] {1, 2, 6, 6, 3, 11, 12}) {
            authority.observeEpoch(knownEpoch);   // the primary being replaced is known at this epoch
            node = node % 5 + 1;
            Promotion promotion = authority.onLeaderElected(node, at += MS).orElseThrow();
            assertThat(promotion.epoch()).isGreaterThan(knownEpoch);
        }
    }

    @Test
    @DisplayName("no promotion epoch ever equals the initial epoch, an observed one or an issued one")
    void promotionEpochsAreFresh() {
        Set<Long> used = new HashSet<>(Set.of(DataStore.INITIAL_EPOCH));
        long at = 0;
        long[] observations = {1, 4, 2, 9, 9, 10};
        for (int i = 0; i < observations.length; i++) {
            authority.observeEpoch(observations[i]);
            used.add(observations[i]);
            Promotion promotion = authority.onLeaderElected(i % 2 == 0 ? 2 : 3, at += MS).orElseThrow();
            assertThat(used).doesNotContain(promotion.epoch());
            used.add(promotion.epoch());
        }
    }

    @Test
    @DisplayName("the equal-epoch rule cannot admit two primaries: issued epochs never collide")
    void equalEpochRuleCannotAdmitTwoPrimaries() {
        // Worst case: no primary ever demotes. Every promoted node stays live as primary at its epoch.
        Map<Integer, Long> actingPrimaries = new TreeMap<>();
        long at = 0;
        int[] elected = {5, 4, 5, 3, 4, 2, 5};
        for (int i = 0; i < elected.length; i++) {
            if (i == 3) {
                authority.observeEpoch(authority.highestEpoch());   // an equal observation must not be reissued
            }
            Promotion p = authority.onLeaderElected(elected[i], at += MS).orElseThrow();
            actingPrimaries.put(p.nodeId(), p.epoch());
        }
        List<NodeRoleSnapshot> snapshot = new ArrayList<>();
        actingPrimaries.forEach((id, epoch) -> snapshot.add(new NodeRoleSnapshot(id, true, FailoverRole.PRIMARY, epoch)));

        SplitBrainReport report = SplitBrainChecker.check(snapshot);
        assertThat(report.violations()).extracting(SplitBrainViolation::kind)
                .doesNotContain(SplitBrainViolation.Kind.DUPLICATE_PRIMARY);

        // The real path: each replaced primary runs its role query and demotes, so the check passes.
        List<NodeRoleSnapshot> healed = new ArrayList<>();
        long highest = authority.highestEpoch();
        int current = authority.current().orElseThrow().nodeId();
        actingPrimaries.forEach((id, epoch) -> {
            if (id == current) {
                healed.add(new NodeRoleSnapshot(id, true, FailoverRole.PRIMARY, epoch));
            } else {
                RejoinDecision d = EpochRules.resolveRejoin(id, FailoverRole.PRIMARY, epoch,
                        List.of(new RoleReport(current, FailoverRole.PRIMARY, highest, null)));
                assertThat(d.action()).isEqualTo(RejoinDecision.Action.DEMOTE_AND_RESYNC);
                healed.add(new NodeRoleSnapshot(id, true, FailoverRole.BACKUP, d.epoch()));
            }
        });
        assertThat(SplitBrainChecker.check(healed).passed()).isTrue();
    }

    @Test
    @DisplayName("concurrent deliveries of the same result produce exactly one promotion")
    void concurrentSameResultPromotesOnce() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Optional<Promotion>>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return authority.onLeaderElected(4, 100 * MS);
                }));
            }
            go.countDown();
            int promotions = 0;
            for (Future<Optional<Promotion>> result : results) {
                if (result.get(10, TimeUnit.SECONDS).isPresent()) {
                    promotions++;
                }
            }
            assertThat(promotions).isEqualTo(1);
            assertThat(authority.highestEpoch()).isEqualTo(DataStore.INITIAL_EPOCH + 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("reset returns to a fresh authority; invalid input is rejected")
    void resetAndValidation() {
        authority.observeEpoch(9);
        authority.onLeaderElected(3, MS);
        authority.reset();

        assertThat(authority.current()).isEmpty();
        assertThat(authority.highestEpoch()).isEqualTo(DataStore.INITIAL_EPOCH);
        assertThat(authority.onLeaderElected(3, 0).orElseThrow().epoch()).isEqualTo(DataStore.INITIAL_EPOCH + 1);
        assertThatThrownBy(() -> authority.onLeaderElected(0, MS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authority.observeEpoch(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
