package com.udcf.modules.mpi;

import com.udcf.core.cluster.ClusterNode;
import com.udcf.modules.rmi.CallContext;
import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.RemoteFailureClassifier;
import com.udcf.modules.rmi.RemoteFailureKind;
import com.udcf.modules.rmi.RmiExtraExport;
import com.udcf.modules.rmi.RmiProperties;
import com.udcf.modules.rmi.RmiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ObjectInputFilter;
import java.rmi.Remote;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * RmiCollectiveTransport over real RMI between real nodes: success both ways, and the observed
 * failure matrix (each failure is logged as "E9b failure matrix | ..."). Where Windows and Linux
 * differ, the documented set is accepted.
 *
 * <p>Test ports (Track C, E9b): RMI base 28570 (28571-28573).</p>
 */
class RmiCollectiveTransportTest {

    private static final int BASE = 28570;

    private CollectiveRmiFixture fixture;

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    private static CallContext fromNode1(long lamport) {
        return new CallContext(1, lamport);
    }

    private interface Transfer {
        void run() throws TransferException;
    }

    /** The transfer's failure, logged with its classification. */
    private static TransferException failure(String scenario, Transfer transfer) {
        TransferException failed = catchThrowableOfType(transfer::run, TransferException.class);
        assertThat(failed).as(scenario).isNotNull();
        Exception original = failed.original();
        Throwable cause = original.getCause();
        System.out.println("E9b failure matrix | " + scenario + " | " + failed.stage() + " | "
                + RemoteFailureClassifier.classify(original, failed.stage()) + " | " + original.getClass().getName()
                + (cause == null ? "" : " <- " + cause.getClass().getName()));
        return failed;
    }

    private static RemoteFailureKind kind(TransferException failed) {
        return RemoteFailureClassifier.classify(failed.original(), failed.stage());
    }

    @Test
    @DisplayName("LATENCY_BASIS says what a transfer's latency covers: the lookup too, unlike Experiment 1")
    void latencyBasis() {
        assertThat(RmiCollectiveTransport.LATENCY_BASIS).isNotBlank().contains("lookup").contains("Experiment 1");
    }

    @Test
    @DisplayName("deliver and collect reach the target node's own CollectiveEndpoint over RMI")
    void deliverAndCollect() throws Exception {
        fixture = new CollectiveRmiFixture(BASE, CollectiveRmiFixture.LOCAL);
        fixture.startAll();
        double[] values = PayloadGenerator.vector(42, 50);

        ReceiveReceipt receipt = fixture.transport.deliver(2, fromNode1(1), 7, 1, values);
        Contribution contribution = fixture.transport.collect(3, fromNode1(2), 7, 2, 5, 42);

        assertThat(receipt.nodeId()).isEqualTo(2);
        assertThat(receipt.checksum()).isEqualTo(VectorChecksum.of(values));
        assertThat(fixture.logic(2).received(7)).get().isEqualTo(values);
        assertThat(contribution.nodeId()).isEqualTo(3);
        assertThat(contribution.values()).isEqualTo(PayloadGenerator.contribution(3, 5, 42));
    }

    @Test
    @DisplayName("a node that never started RMI: LOOKUP, connection refused")
    void neverStarted() {
        fixture = new CollectiveRmiFixture(BASE, CollectiveRmiFixture.LOCAL);
        fixture.start(1);
        TransferException failed = failure("(a) never started",
                () -> fixture.transport.deliver(3, fromNode1(1), 7, 2, new double[] {1}));
        assertThat(failed.stage()).isEqualTo(CallStage.LOOKUP);
        assertThat(kind(failed)).isEqualTo(RemoteFailureKind.CONNECTION_REFUSED);
    }

    @Test
    @DisplayName("a crashed node with no pooled connection: LOOKUP, connection refused")
    void crashedWithoutPooledConnection() {
        fixture = new CollectiveRmiFixture(BASE, CollectiveRmiFixture.LOCAL);
        fixture.startAll();
        fixture.cluster.crash(2);
        TransferException failed = failure("(b) crashed, no pooled connection",
                () -> fixture.transport.deliver(2, fromNode1(1), 7, 1, new double[] {1}));
        assertThat(failed.stage()).isEqualTo(CallStage.LOOKUP);
        assertThat(kind(failed)).isEqualTo(RemoteFailureKind.CONNECTION_REFUSED);
    }

    @Test
    @DisplayName("a node crashed after a pooled call: LOOKUP, connection lost or refused (the documented set)")
    void crashedAfterPooledCall() throws Exception {
        fixture = new CollectiveRmiFixture(BASE, CollectiveRmiFixture.LOCAL);
        fixture.startAll();
        fixture.transport.deliver(2, fromNode1(1), 7, 1, new double[] {1});
        fixture.cluster.crash(2);
        TransferException failed = failure("(c) crashed after a pooled call",
                () -> fixture.transport.deliver(2, fromNode1(2), 8, 1, new double[] {1}));
        assertThat(failed.stage()).isEqualTo(CallStage.LOOKUP);
        assertThat(kind(failed)).isIn(RmiTestSupport.NODE_GONE);
    }

    @Test
    @DisplayName("an endpoint blocked past the call timeout: CALL, timed out; entered exactly once (no retry)")
    void blockedEndpointTimesOut() {
        RmiProperties shortTimeout = new RmiProperties(1_000_000, 1024, 50, 300, 300);
        fixture = new CollectiveRmiFixture(BASE, CollectiveRmiFixture.LOCAL, shortTimeout);
        BlockingExport blocking = new BlockingExport(fixture.export);
        fixture.start(1);
        fixture.start(2, blocking);
        try {
            TransferException failed = failure("(d) blocked endpoint, call timeout 300 ms",
                    () -> fixture.transport.deliver(2, fromNode1(1), 7, 1, new double[] {1}));
            assertThat(failed.stage()).isEqualTo(CallStage.CALL);
            assertThat(kind(failed)).isEqualTo(RemoteFailureKind.TIMED_OUT);
            assertThat(blocking.entries).hasValue(1);
        } finally {
            blocking.release.countDown();
        }
    }

    @Test
    @DisplayName("a payload the node's filter refuses: CALL, arguments rejected or connection lost (size dependent); nothing ran (barrier)")
    void filterRefusal() throws Exception {
        fixture = new CollectiveRmiFixture(BASE, CollectiveRmiFixture.LOCAL);
        fixture.startAll();
        long clockBefore = fixture.cluster.node(2).clock().current();
        TransferException failed = failure("(e) filter refusal, max-payload-elements + 1 doubles (local 100001)",
                () -> fixture.transport.deliver(2, fromNode1(0), 7, 1, new double[CollectiveRmiFixture.LOCAL.maxPayloadElements() + 1]));
        assertThat(failed.stage()).isEqualTo(CallStage.CALL);
        assertThat(kind(failed)).isIn(RemoteFailureKind.ARGUMENTS_REJECTED, RemoteFailureKind.CONNECTION_LOST);

        ReceiveReceipt barrier = fixture.barrier(2, 8);
        assertThat(fixture.logic(2).storedIds()).containsExactly(8L);
        assertThat(barrier.lamportTime()).isEqualTo(clockBefore + 2);
        assertThat(fixture.cluster.node(2).clock().current()).isEqualTo(clockBefore + 2);
    }

    @Test
    @DisplayName("a node running Experiment 1 only (no CollectiveEndpoint bound): LOOKUP, not bound")
    void notBound() {
        fixture = new CollectiveRmiFixture(BASE, CollectiveRmiFixture.LOCAL);
        fixture.start(1);
        fixture.start(2, new RmiExtraExport[0]);
        TransferException failed = failure("(f) Experiment 1 only, nothing bound under CollectiveEndpoint",
                () -> fixture.transport.deliver(2, fromNode1(1), 7, 1, new double[] {1}));
        assertThat(failed.stage()).isEqualTo(CallStage.LOOKUP);
        assertThat(kind(failed)).isEqualTo(RemoteFailureKind.NOT_BOUND);
    }

    @Test
    @DisplayName("the method's own exception comes back at CALL (method threw), never swallowed")
    void methodThrew() {
        fixture = new CollectiveRmiFixture(BASE, CollectiveRmiFixture.LOCAL);
        fixture.startAll();
        TransferException failed = failure("(g) the method threw (count 0)",
                () -> fixture.transport.collect(2, fromNode1(1), 7, 1, 0, 42));
        assertThat(failed.stage()).isEqualTo(CallStage.CALL);
        assertThat(failed.original()).isInstanceOf(IllegalArgumentException.class);
        assertThat(kind(failed)).isEqualTo(RemoteFailureKind.METHOD_THREW);
    }

    @Test
    @DisplayName("after a recovery the node answers again with a fresh logic: what it held before the crash is gone")
    void recoveryGivesAFreshEndpoint() throws Exception {
        fixture = new CollectiveRmiFixture(BASE, CollectiveRmiFixture.LOCAL);
        fixture.startAll();
        fixture.transport.deliver(2, fromNode1(1), 5, 1, new double[] {1, 2});
        CollectiveEndpointLogic before = fixture.logic(2);

        fixture.cluster.crash(2);
        assertThat(CollectiveEndpointExport.current(fixture.cluster.node(2))).isEmpty();
        RmiTestSupport.drainUntilRefused(() -> fixture.lookup.lookup(2, CollectiveEndpoint.BOUND_NAME, CollectiveEndpoint.class));
        fixture.cluster.recover(2);

        CollectiveEndpointLogic after = fixture.logic(2);
        assertThat(after).isNotSameAs(before);
        assertThat(after.received(5)).isEmpty();
        assertThat(fixture.transport.deliver(2, fromNode1(3), 6, 1, new double[] {3}).nodeId()).isEqualTo(2);
        assertThat(after.received(6)).get().isEqualTo(new double[] {3});
    }

    @Test
    @DisplayName("the transport rejects no root: the caller is the CallContext's sender")
    void rootAgnostic() throws Exception {
        fixture = new CollectiveRmiFixture(BASE, CollectiveRmiFixture.LOCAL);
        fixture.startAll();
        assertThat(fixture.transport.deliver(1, new CallContext(3, 1), 9, 0, new double[] {1}).nodeId()).isEqualTo(1);
        assertThatThrownBy(() -> new RmiCollectiveTransport(null)).isInstanceOf(NullPointerException.class);
    }

    /** Exports a CollectiveEndpoint that waits on a latch before working, and counts how often it was entered. */
    static final class BlockingExport implements RmiExtraExport {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger entries = new AtomicInteger();
        private final CollectiveEndpointExport real;

        BlockingExport(CollectiveEndpointExport real) {
            this.real = real;
        }

        @Override
        public String boundName() {
            return real.boundName();
        }

        @Override
        public Class<? extends Remote> remoteType() {
            return real.remoteType();
        }

        @Override
        public Remote newEndpoint(ClusterNode node) {
            CollectiveEndpointLogic logic = (CollectiveEndpointLogic) real.newEndpoint(node);
            return new CollectiveEndpoint() {
                @Override
                public ReceiveReceipt receive(CallContext context, long collectiveId, int rank, double[] values) {
                    entries.incrementAndGet();
                    awaitRelease();
                    return logic.receive(context, collectiveId, rank, values);
                }

                @Override
                public Contribution contribute(CallContext context, long collectiveId, int rank, int count, long seed) {
                    entries.incrementAndGet();
                    awaitRelease();
                    return logic.contribute(context, collectiveId, rank, count, seed);
                }
            };
        }

        private void awaitRelease() {
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        }

        @Override
        public ObjectInputFilter filter() {
            return real.filter();
        }
    }
}
