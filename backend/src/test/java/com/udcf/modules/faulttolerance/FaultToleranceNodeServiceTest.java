package com.udcf.modules.faulttolerance;

import com.udcf.core.cluster.ClusterNode;
import com.udcf.modules.election.ElectionNodeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Lifecycle of one node's faulttolerance service on a three-node cluster with real sockets:
 * start, crash, recover, stop, start after a failure, and that no thread outlives a crash or a
 * stop, even one blocked in a role query. Ports: {@link FailoverFixture}.
 */
class FaultToleranceNodeServiceTest {

    private FailoverFixture fixture;

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    private static List<String> liveThreads(String prefix) {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive).map(Thread::getName)
                .filter(name -> name.startsWith(prefix)).toList();
    }

    private FaultToleranceNodeService ensure(ClusterNode node) {
        return node.ensureService(FaultToleranceNodeService.NAME, n -> new FaultToleranceNodeService(n, fixture.failover));
    }

    @Test
    @DisplayName("start fails without the node's election service, registers nothing, and succeeds once it runs")
    void startAfterFailure() {
        fixture = new FailoverFixture(3);
        ClusterNode node = fixture.cluster.node(1);

        assertThatIllegalStateException().isThrownBy(() -> ensure(node))
                .havingCause().withMessageContaining("election service must start first");
        assertThat(FaultToleranceNodeService.find(node)).isEmpty();
        assertThat(liveThreads("udcf-faulttolerance-n1-")).isEmpty();

        ElectionNodeService.on(node, fixture.cluster, FailoverFixture.ELECTION, fixture.bus);
        FaultToleranceNodeService service = ensure(node);
        assertThat(service.isRunning()).isTrue();
        assertThat(service.rejoinState()).isEqualTo(RejoinState.READY);
        assertThat(liveThreads("udcf-faulttolerance-n1-")).isEmpty();   // the worker thread starts with its first task
        assertThat(service.execute("probe", () -> { })).isTrue();
        FailoverFixture.within().until(() -> !liveThreads("udcf-faulttolerance-n1-worker").isEmpty());
    }

    @Test
    @DisplayName("start registers with the shared detector: a crash of the primary is detected through it")
    void startRegistersWithTheSharedDetector() {
        fixture = new FailoverFixture(3);
        fixture.startAndAwaitPrimary(1);

        fixture.cluster.crash(1);

        FailoverFixture.within().until(() -> !fixture.events("FAILURE_DETECTED").isEmpty());
        assertThat(fixture.events("FAILURE_DETECTED").get(0).nodeId()).isIn(2, 3);
        assertThat(fixture.events("FAILURE_DETECTED").get(0).peerId()).isEqualTo(1);
    }

    @Test
    @DisplayName("crash steps the acting primary down, then joins every thread of the service")
    void crashStepsDownAndJoinsThreads() {
        fixture = new FailoverFixture(3);
        fixture.startAndAwaitPrimary(1);
        FaultToleranceNodeService service = fixture.service(1);

        fixture.cluster.crash(1);

        assertThat(service.isRunning()).isFalse();
        assertThat(service.threadsTerminated()).isTrue();
        assertThat(liveThreads("udcf-faulttolerance-n1-")).isEmpty();
        assertThat(fixture.replication(1).isPrimary()).isFalse();
        assertThat(service.wasPrimaryAtCrash()).isTrue();
        assertThat(service.isServing()).isFalse();
        assertThat(service.rejoinState()).isEqualTo(RejoinState.REJOINING);
    }

    @Test
    @DisplayName("recover reopens the service, which rejoins as a backup and never acts as primary meanwhile")
    void recoverRejoinsAsNonPrimary() {
        fixture = new FailoverFixture(3);
        fixture.startAndAwaitPrimary(1);
        fixture.cluster.crash(1);
        fixture.awaitPrimary(2);

        fixture.cluster.recover(1);

        FaultToleranceNodeService service = fixture.service(1);
        assertThat(service.isRunning()).isTrue();
        FailoverFixture.within().until(() -> {
            assertThat(fixture.replication(1).isPrimary()).isFalse();
            return service.rejoinState() == RejoinState.READY;
        });
        assertThat(service.isServing()).isFalse();
        assertThat(service.believedPrimaryId()).isEqualTo(2);
    }

    @Test
    @DisplayName("the service follows Cluster.crash and Cluster.recover (R10)")
    void followsTheClusterNode() {
        fixture = new FailoverFixture(3);
        fixture.startAndAwaitPrimary(1);
        FaultToleranceNodeService backup = fixture.service(3);

        fixture.cluster.crash(3);
        assertThat(backup.isRunning()).isFalse();
        fixture.cluster.recover(3);
        assertThat(backup.isRunning()).isTrue();
        FailoverFixture.within().until(() -> backup.rejoinState() == RejoinState.READY);
    }

    @Test
    @DisplayName("stop twice is fine, joins the threads, and the service can start again")
    void stopIsIdempotent() {
        fixture = new FailoverFixture(3);
        fixture.startAndAwaitPrimary(1);
        FaultToleranceNodeService service = fixture.service(2);

        service.stop();
        assertThatCode(service::stop).doesNotThrowAnyException();

        assertThat(service.isRunning()).isFalse();
        assertThat(service.threadsTerminated()).isTrue();
        assertThat(liveThreads("udcf-faulttolerance-n2-")).isEmpty();
        service.start();
        assertThat(service.isRunning()).isTrue();
    }

    @Test
    @DisplayName("a crash while the role query waits on a silent peer returns within the read timeout")
    void crashDuringBlockedRoleQueryIsBounded() throws Exception {
        fixture = new FailoverFixture(3);
        fixture.cluster.crash(2);   // node 2 never binds its replication port; a silent peer takes it
        try (SilentServer silent = new SilentServer(FailoverFixture.replicationPort(2))) {
            fixture.startAndAwaitPrimary(1);
            fixture.cluster.crash(3);
            fixture.cluster.recover(3);   // node 3 rejoins: its role query asks node 1 and the silent node 2
            FailoverFixture.within().until(() -> silent.accepted() >= 1);

            long start = System.nanoTime();
            fixture.cluster.crash(3);
            long crashMillis = (System.nanoTime() - start) / 1_000_000;

            FaultToleranceNodeService service = fixture.service(3);
            assertThat(crashMillis).isLessThan(2000);
            assertThat(service.threadsTerminated()).isTrue();
            assertThat(liveThreads("udcf-faulttolerance-n3-")).isEmpty();
        }
    }
}
