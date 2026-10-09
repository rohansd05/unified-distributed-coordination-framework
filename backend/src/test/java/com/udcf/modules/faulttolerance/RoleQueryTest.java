package com.udcf.modules.faulttolerance;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.NodeCapacity;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.modules.replication.ConsistencyModel;
import com.udcf.modules.replication.DataItem;
import com.udcf.modules.replication.ReadReply;
import com.udcf.modules.replication.ReplicationNodeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The role query over the replication channel: how a READ reply becomes a {@link RoleReport},
 * and the real TCP query (parallel, bounded, Lamport-stamped). Ports: {@link FailoverFixture}.
 */
class RoleQueryTest {

    private ClusterEventBus bus;
    private Cluster cluster;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private Cluster cluster(int size) {
        bus = new ClusterEventBus(new EventProperties(1000, 1000), Clock.systemUTC());
        cluster = new Cluster(new ClusterProperties(size, Collections.nCopies(size, NodeCapacity.FAST),
                FailoverFixture.PORTS), bus);
        return cluster;
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        if (cluster != null) {
            cluster.close();
        }
        if (bus != null) {
            bus.close();
        }
    }

    private static DataItem term(int primary, long epoch) {
        return new DataItem(RoleQuery.TERM_KEY, RoleQuery.termValue(primary, epoch), 10, primary, epoch);
    }

    @Test
    @DisplayName("a reply's term record at its store epoch names the primary; the primary reports itself")
    void mapsReadReplyToRoleReport() {
        assertThat(RoleQuery.toReport(new ReadReply(2, 5, 3, Optional.of(term(2, 3)))))
                .isEqualTo(new RoleReport(2, FailoverRole.PRIMARY, 3, 2));
        assertThat(RoleQuery.toReport(new ReadReply(4, 5, 3, Optional.of(term(2, 3)))))
                .isEqualTo(new RoleReport(4, FailoverRole.BACKUP, 3, 2));
    }

    @Test
    @DisplayName("a term record older than the store epoch, or none, names nobody (null, never a guess)")
    void staleOrMissingTermRecordNamesNobody() {
        assertThat(RoleQuery.toReport(new ReadReply(2, 5, 3, Optional.of(term(2, 2)))))
                .isEqualTo(new RoleReport(2, FailoverRole.BACKUP, 3, null));
        assertThat(RoleQuery.toReport(new ReadReply(4, 5, 1, Optional.empty())))
                .isEqualTo(new RoleReport(4, FailoverRole.BACKUP, 1, null));
        assertThat(RoleQuery.termValue(2, 3)).isEqualTo("Node 2 is primary at epoch 3");
    }

    @Test
    @DisplayName("asks every peer over TCP, skips a crashed one, and stamps both clocks (L4)")
    void asksPeersOverTcpAndSkipsUnreachable() throws Exception {
        Cluster c = cluster(3);
        ReplicationNodeService two = ReplicationNodeService.on(c.node(2), c, FailoverFixture.REPLICATION, bus);
        ReplicationNodeService.on(c.node(3), c, FailoverFixture.REPLICATION, bus);
        two.becomePrimary(2);
        two.write(RoleQuery.TERM_KEY, RoleQuery.termValue(2, 2), ConsistencyModel.SYNCHRONOUS, List.of(1, 3));
        c.crash(3);
        long askerBefore = c.node(1).clock().current();
        long peerBefore = c.node(2).clock().current();

        RoleQuery.Answers answers = new RoleQuery(300).ask(1, c.node(1).clock(), List.of(2, 3),
                id -> FailoverFixture.replicationPort(id), executor);

        assertThat(answers.reports()).containsExactly(new RoleReport(2, FailoverRole.PRIMARY, 2, 2));
        assertThat(answers.silent()).containsExactly(3);
        // L4: the peer merged the asker's send time, and the asker merged the peer's reply time.
        assertThat(c.node(2).clock().current()).isGreaterThan(Math.max(peerBefore, askerBefore));
        assertThat(c.node(1).clock().current()).isGreaterThan(c.node(2).clock().current());
        assertThat(answers.describe()).singleElement()
                .satisfies(entry -> assertThat(entry).containsEntry("believedPrimaryId", 2).containsEntry("role", "PRIMARY"));
        assertThatIllegalArgumentException().isThrownBy(() -> new RoleQuery(300).ask(1, c.node(1).clock(), List.of(1),
                FailoverFixture::replicationPort, executor));
    }

    @Test
    @DisplayName("silent peers are asked in parallel: three cost about one read timeout, not three")
    void silentPeersAreBoundedByOneTimeout() throws Exception {
        Cluster c = cluster(4);
        try (SilentServer s2 = new SilentServer(FailoverFixture.replicationPort(2));
             SilentServer s3 = new SilentServer(FailoverFixture.replicationPort(3));
             SilentServer s4 = new SilentServer(FailoverFixture.replicationPort(4))) {
            long start = System.nanoTime();
            RoleQuery.Answers answers = new RoleQuery(300).ask(1, c.node(1).clock(), List.of(2, 3, 4),
                    FailoverFixture::replicationPort, executor);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertThat(answers.reports()).isEmpty();
            assertThat(answers.silent()).containsExactly(2, 3, 4);
            assertThat(elapsedMillis).isGreaterThanOrEqualTo(290).isLessThan(900);
            assertThat(s2.accepted() + s3.accepted() + s4.accepted()).isEqualTo(3);
        }
    }
}
