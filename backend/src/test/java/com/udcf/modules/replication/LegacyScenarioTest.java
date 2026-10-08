package com.udcf.modules.replication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The legacy Experiment 5 demo (legacy-demos/exp05-replication, ReplicationDemo phases 1-5
 * and its final consistency check), replayed on the pure classes, with the legacy
 * behaviour written out by hand in each test.
 *
 * <p>Node 1 is the primary, nodes 2 and 3 are backups, as in the legacy demo. Lamport
 * values are illustrative and increase with every write, as the primary's clock did; the
 * exact legacy values depend on how many messages each phase sent. The one fixed value the
 * legacy demo used, the injected {@code balance=STALE-999 (L5, N1)}, is kept exactly.
 * Everything runs on epoch 1, as Experiment 5 alone does.</p>
 */
class LegacyScenarioTest {

    private static final int PRIMARY = 1;
    private static final int BACKUP_A = 2;
    private static final int BACKUP_B = 3;

    private final DataStore primary = new DataStore();
    private final DataStore backupA = new DataStore();
    private final DataStore backupB = new DataStore();

    private static DataItem write(String key, String value, long lamport) {
        return new DataItem(key, value, lamport, PRIMARY, 1);
    }

    /** Legacy SYNCHRONOUS write: apply on the primary, then on every reachable backup. */
    private DataItem syncWrite(String key, String value, long lamport, DataStore... reachable) {
        DataItem item = write(key, value, lamport);
        primary.apply(item);
        for (DataStore backup : reachable) {
            assertThat(backup.apply(item)).isEqualTo(ApplyResult.APPLIED);
        }
        return item;
    }

    private ConsistencyReport check() {
        Map<Integer, Map<String, DataItem>> replicas = new TreeMap<>();
        replicas.put(PRIMARY, primary.snapshot());
        replicas.put(BACKUP_A, backupA.snapshot());
        replicas.put(BACKUP_B, backupB.snapshot());
        return ConsistencyCheck.compare(PRIMARY, replicas);
    }

    /** Phases 1-3 of the legacy demo: node 3 ends two updates behind. */
    private void runPhasesOneToThree() {
        syncWrite("balance", "1000", 1, backupA, backupB);
        syncWrite("owner", "alice", 10, backupA, backupB);
        syncWrite("status", "active", 19, backupA, backupB);
        syncWrite("balance", "2000", 28, backupA, backupB);       // async, after it lands
        syncWrite("balance", "3000", 37, backupA);                 // node 3 crashed
        syncWrite("status", "frozen", 42, backupA);
    }

    @Test
    @DisplayName("L1 legacy tie-break: equal Lamport times, the higher origin node wins on every replica")
    void tieBreakHigherOriginWins() {
        // Legacy DataItem.isNewerThan: lamportTime first, then originNode > other.originNode.
        DataItem fromNode1 = new DataItem("k", "from-1", 7, 1, 1);
        DataItem fromNode3 = new DataItem("k", "from-3", 7, 3, 1);
        DataStore firstThenThird = new DataStore();
        firstThenThird.apply(fromNode1);
        firstThenThird.apply(fromNode3);
        DataStore thirdThenFirst = new DataStore();
        thirdThenFirst.apply(fromNode3);
        assertThat(thirdThenFirst.apply(fromNode1)).isEqualTo(ApplyResult.STALE);
        assertThat(firstThenThird.get("k")).contains(fromNode3);
        assertThat(thirdThenFirst.get("k")).contains(fromNode3);
    }

    @Test
    @DisplayName("L2 legacy identical item: not stored (legacy also counted it as rejected); now DUPLICATE, not stale")
    void identicalItemNotStoredNowDuplicateNotStale() {
        // Legacy: isNewerThan(identical) is false, apply returns false, rejected++ and the
        // primary's replicateTo called recordStaleRejection(). Deliberate difference: same
        // "not stored", but classified DUPLICATE and never counted as stale.
        DataItem item = write("owner", "alice", 10);
        backupB.apply(item);
        ReplicationStats stats = new ReplicationStats(BACKUP_B);

        ApplyResult again = backupB.apply(item);
        stats.recordAck(again, 0.4, Instant.parse("2026-10-08T10:00:00Z"));

        assertThat(again).isEqualTo(ApplyResult.DUPLICATE);
        assertThat(backupB.get("owner")).contains(item);
        assertThat(backupB.staleCount()).isZero();
        assertThat(stats.snapshot().staleRejections()).isZero();
        assertThat(stats.snapshot().duplicates()).isEqualTo(1);
    }

    @Test
    @DisplayName("L3 legacy phase 1: synchronous writes of balance, owner and status reach all three replicas")
    void phase1SyncWritesReachEveryReplica() {
        syncWrite("balance", "1000", 1, backupA, backupB);
        syncWrite("owner", "alice", 10, backupA, backupB);
        syncWrite("status", "active", 19, backupA, backupB);
        for (DataStore replica : new DataStore[] {primary, backupA, backupB}) {
            assertThat(replica.get("balance")).map(DataItem::value).contains("1000");
            assertThat(replica.size()).isEqualTo(3);
        }
        assertThat(check().consistent()).isTrue();
    }

    @Test
    @DisplayName("L4 legacy phase 2: an async write leaves both backups stale until the delayed push lands, then they converge")
    void phase2AsyncBackupStaleThenConverges() {
        syncWrite("balance", "1000", 1, backupA, backupB);
        DataItem async = write("balance", "2000", 28);
        primary.apply(async);   // the client is released here

        ConsistencyReport window = check();
        assertThat(window.count(DivergenceKind.STALE)).isEqualTo(2);
        assertThat(backupA.get("balance")).map(DataItem::value).contains("1000");

        backupA.apply(async);   // the delayed (simulated, 450 ms in E5b) replication lands
        backupB.apply(async);
        assertThat(check().consistent()).isTrue();
    }

    @Test
    @DisplayName("L5 legacy phase 3: node 3 is down for balance=3000 and status=frozen, and diverges")
    void phase3CrashedBackupDiverges() {
        runPhasesOneToThree();
        ConsistencyReport r = check();
        assertThat(r.divergences()).extracting(ReplicaDivergence::nodeId, ReplicaDivergence::key, ReplicaDivergence::kind)
                .containsExactly(
                        tuple(BACKUP_B, "balance", DivergenceKind.STALE),
                        tuple(BACKUP_B, "status", DivergenceKind.STALE));
    }

    @Test
    @DisplayName("L6 legacy phase 4: the primary pushes its whole store; only the two missed updates land")
    void phase4AntiEntropyPushesWholeStoreOnlyMissedLand() {
        runPhasesOneToThree();
        // Legacy resync pushed all 3 items; owner was refused as "not newer" and counted as a
        // stale rejection. Here it is alreadyCurrent; balance and status land.
        AntiEntropyResult r = AntiEntropy.merge(backupB, AntiEntropy.plan(primary), 1);
        assertThat(r).isEqualTo(new AntiEntropyResult(3, 2, 1, 0, 0));
        assertThat(backupB.get("balance")).map(DataItem::value).contains("3000");
        assertThat(backupB.get("status")).map(DataItem::value).contains("frozen");
        assertThat(check().consistent()).isTrue();
    }

    @Test
    @DisplayName("L7 legacy phase 5: after balance=4000, the injected balance=STALE-999 (L5, N1) is rejected")
    void phase5OutOfOrderInjectionRejected() {
        runPhasesOneToThree();
        AntiEntropy.merge(backupB, AntiEntropy.plan(primary), 1);
        DataItem current = syncWrite("balance", "4000", 51, backupA, backupB);

        DataItem legacyStale = new DataItem("balance", "STALE-999", 5, PRIMARY, 1);
        assertThat(backupA.apply(legacyStale)).isEqualTo(ApplyResult.STALE);
        assertThat(backupA.apply(OutOfOrderInjector.staleVersionOf(current, "STALE-999")))
                .isEqualTo(ApplyResult.STALE);
        assertThat(backupA.get("balance")).contains(current);
    }

    @Test
    @DisplayName("L8 legacy final check: after all five phases every replica holds the same items")
    void finalConsistencyCheckPasses() {
        runPhasesOneToThree();
        AntiEntropy.merge(backupB, AntiEntropy.plan(primary), 1);
        syncWrite("balance", "4000", 51, backupA, backupB);
        backupA.apply(new DataItem("balance", "STALE-999", 5, PRIMARY, 1));

        ConsistencyReport r = check();
        assertThat(r.consistent()).isTrue();
        assertThat(r.comparedNodeIds()).containsExactly(BACKUP_A, BACKUP_B);
        assertThat(primary.snapshot()).isEqualTo(backupA.snapshot()).isEqualTo(backupB.snapshot());
        assertThat(primary.snapshot().keySet()).containsExactly("balance", "owner", "status");
    }
}
