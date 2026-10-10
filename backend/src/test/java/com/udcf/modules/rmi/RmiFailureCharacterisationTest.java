package com.udcf.modules.rmi;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.events.ClusterEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a failed remote call really looks like, scenario by scenario, and what the classifier
 * calls it. Each scenario uses its own node, and a fresh {@link RemoteEndpointLookup} (so a fresh
 * client channel), so RMI's JVM-wide connection pools never carry over between scenarios. The
 * observed chain (class, innermost cause, message) and verdict are logged as
 * {@code E1b failure matrix | ...} lines; the track file records them.
 *
 * <p>Scenarios (c) and (d) have two honest outcomes, decided inside RMI: a pooled connection
 * used less than about 5 ms before is reused without a check, so the call fails reading the reply
 * (CONNECTION_LOST); otherwise RMI pings it, finds it dead and opens a new connection, which is
 * refused (CONNECTION_REFUSED). Both are asserted as the allowed set; never a success, a hang or
 * another kind.</p>
 *
 * <p>Test-only ports 28821 to 28826 (RMI base 28820, six nodes; see RmiTestSupport).</p>
 */
@Timeout(60)
class RmiFailureCharacterisationTest {

    private static final Logger log = LoggerFactory.getLogger(RmiFailureCharacterisationTest.class);
    private static final int BASE = 28820;

    private ClusterEventBus bus;
    private Cluster cluster;

    @BeforeEach
    void setUp() {
        bus = RmiTestSupport.bus();
        cluster = RmiTestSupport.cluster(BASE, 6, bus);
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    private RmiCallResult call(EndpointLookup lookup, int target) {
        return RmiTestSupport.invoker(cluster, 1).invoke(lookup, target, RmiCall.ping());
    }

    private static RmiCallResult record(String scenario, RmiCallResult result) {
        log.info("E1b failure matrix | {} | outcome={} stage={} | {} <- {} | {} | verdict={}", scenario,
                result.outcome(), result.failureStage(), result.failureClass(), result.failureCauseClass(),
                result.failureMessage(), result.failureKind());
        return result;
    }

    @Test
    @DisplayName("(a) a node that never started: refused at the lookup")
    void neverStarted() {
        RmiCallResult result = record("(a) node never started", call(RmiTestSupport.remote(cluster), 1));

        assertThat(result.failureStage()).isEqualTo(CallStage.LOOKUP);
        assertThat(result.failureClass()).isEqualTo("java.rmi.ConnectException");
        assertThat(result.failureCauseClass()).isEqualTo("java.net.ConnectException");
        assertThat(result.failureMessage()).startsWith("Connection refused to host: 127.0.0.1;");
        assertThat(result.failureKind()).isEqualTo(RemoteFailureKind.CONNECTION_REFUSED);
    }

    @Test
    @DisplayName("(b) a node that crashed before anyone connected: refused at the lookup")
    void crashedWithoutEarlierConnection() {
        RmiTestSupport.start(cluster, 2);
        cluster.crash(2);

        RmiCallResult result = record("(b) crashed, no earlier connection", call(RmiTestSupport.remote(cluster), 2));

        assertThat(result.failureStage()).isEqualTo(CallStage.LOOKUP);
        assertThat(result.failureClass()).isEqualTo("java.rmi.ConnectException");
        assertThat(result.failureKind()).isEqualTo(RemoteFailureKind.CONNECTION_REFUSED);
    }

    @Test
    @DisplayName("(c) a node that crashed after a successful call (pooled connection): fails visibly at the lookup")
    void crashedAfterASuccessfulCall() {
        RmiTestSupport.start(cluster, 3);
        RemoteEndpointLookup remote = RmiTestSupport.remote(cluster);
        assertThat(call(remote, 3).outcome()).isEqualTo(CallOutcome.SUCCESS);
        cluster.crash(3);

        RmiCallResult result = record("(c) crashed after a successful call", call(remote, 3));

        assertThat(result.outcome()).isEqualTo(CallOutcome.REMOTE_FAILURE);
        assertThat(result.failureStage()).isEqualTo(CallStage.LOOKUP);
        assertThat(result.failureKind()).isIn(RmiTestSupport.NODE_GONE);
    }

    @Test
    @DisplayName("(d) a stub kept from before the crash: fails visibly at the call")
    void staleStubAfterCrash() throws Exception {
        RmiTestSupport.start(cluster, 4);
        NodeEndpoint stub = RmiTestSupport.remote(cluster).lookup(4);
        assertThat(call(RmiTestSupport.fixed(stub), 4).outcome()).isEqualTo(CallOutcome.SUCCESS);
        cluster.crash(4);

        RmiCallResult result = record("(d) stale stub after crash", call(RmiTestSupport.fixed(stub), 4));

        assertThat(result.failureStage()).isEqualTo(CallStage.CALL);
        assertThat(result.failureKind()).isIn(RmiTestSupport.NODE_GONE);
    }

    @Test
    @DisplayName("(e) a stub kept from before the crash, after a recovery: no such object; a fresh lookup works")
    void staleStubAfterRecover() throws Exception {
        RmiTestSupport.start(cluster, 5);
        RemoteEndpointLookup remote = RmiTestSupport.remote(cluster);
        NodeEndpoint stub = remote.lookup(5);
        assertThat(call(RmiTestSupport.fixed(stub), 5).outcome()).isEqualTo(CallOutcome.SUCCESS);
        cluster.crash(5);
        // While down, empty the pooled connections of both channels, so what follows is deterministic.
        RmiTestSupport.drainUntilRefused(() -> stub.ping(new CallContext(1, 0)));
        RmiTestSupport.drainUntilRefused(() -> remote.lookup(5));
        cluster.recover(5);

        RmiCallResult stale = record("(e) stale stub after recover", call(RmiTestSupport.fixed(stub), 5));
        RmiCallResult fresh = record("(e) fresh lookup after recover", call(remote, 5));

        assertThat(stale.failureStage()).isEqualTo(CallStage.CALL);
        assertThat(stale.failureClass()).isEqualTo("java.rmi.NoSuchObjectException");
        assertThat(stale.failureMessage()).isEqualTo("no such object in table");
        assertThat(stale.failureKind()).isEqualTo(RemoteFailureKind.NO_SUCH_OBJECT);
        assertThat(fresh.outcome()).isEqualTo(CallOutcome.SUCCESS);
    }

    @Test
    @DisplayName("(f) a node that does not answer: the call times out after the call timeout, then the node serves again")
    void timeoutAgainstABlockedEndpoint() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        RmiProperties shortTimeout = new RmiProperties(1_000_000, 1024, 50, 500, 300);
        RmiTestSupport.start(cluster, 6, shortTimeout,
                () -> new BlockingEndpoint(NodeEndpointLogic.forNode(cluster.node(6), shortTimeout, System::nanoTime),
                        entered, release));
        RemoteEndpointLookup remote = RmiTestSupport.remote(cluster);
        try {
            RmiCallResult result = record("(f) timeout against a blocked endpoint", call(remote, 6));

            assertThat(entered.await(5, TimeUnit.SECONDS)).as("the call reached the node").isTrue();
            assertThat(result.failureStage()).isEqualTo(CallStage.CALL);
            assertThat(result.failureClass()).isEqualTo("java.rmi.UnmarshalException");
            assertThat(result.failureCauseClass()).isEqualTo("java.net.SocketTimeoutException");
            assertThat(result.failureKind()).isEqualTo(RemoteFailureKind.TIMED_OUT);
            assertThat(Duration.ofNanos(result.timeToFailureNanos()))
                    .isGreaterThanOrEqualTo(Duration.ofMillis(450))
                    .isLessThan(Duration.ofMillis(3000));
        } finally {
            release.countDown();
        }
        assertThat(call(remote, 6).outcome()).as("served again once released").isEqualTo(CallOutcome.SUCCESS);
    }

    /** A NodeEndpoint whose ping waits for a latch; every other method delegates. */
    private static final class BlockingEndpoint implements NodeEndpoint {
        private final NodeEndpoint delegate;
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final java.util.concurrent.atomic.AtomicBoolean blockedOnce = new java.util.concurrent.atomic.AtomicBoolean();

        private BlockingEndpoint(NodeEndpoint delegate, CountDownLatch entered, CountDownLatch release) {
            this.delegate = delegate;
            this.entered = entered;
            this.release = release;
        }

        @Override
        public PingReply ping(CallContext context) throws java.rmi.RemoteException {
            if (blockedOnce.compareAndSet(false, true)) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return delegate.ping(context);
        }

        @Override
        public NodeInfo nodeInfo(CallContext context) throws java.rmi.RemoteException {
            return delegate.nodeInfo(context);
        }

        @Override
        public EchoReply echo(CallContext context, String text) throws java.rmi.RemoteException {
            return delegate.echo(context, text);
        }

        @Override
        public HashResult computeHash(CallContext context, int rounds) throws java.rmi.RemoteException {
            return delegate.computeHash(context, rounds);
        }
    }
}
