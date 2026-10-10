package com.udcf.modules.rmi;

import com.udcf.core.clock.LamportClock;
import com.udcf.core.cluster.NodeCapacity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.EOFException;
import java.io.NotSerializableException;
import java.lang.reflect.RecordComponent;
import java.net.SocketTimeoutException;
import java.rmi.ConnectException;
import java.rmi.MarshalException;
import java.rmi.NoSuchObjectException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.ServerException;
import java.rmi.UnmarshalException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RmiInvoker with fake lookups and endpoints: no registry, no socket. */
class RmiInvokerTest {

    private static final RmiProperties PROPERTIES = new RmiProperties(1000, 16, 10, 3000, 300);

    private final LamportClock senderClock = new LamportClock();
    private final List<String> log = new ArrayList<>();

    private RmiInvoker invoker(long... nanoReadings) {
        return invoker(PROPERTIES, nanoReadings);
    }

    private RmiInvoker invoker(RmiProperties properties, long... nanoReadings) {
        return new RmiInvoker(1, senderClock, properties, new CallTimer(new ScriptedNanoClock(nanoReadings)));
    }

    private static NodeEndpointLogic node2() {
        return new NodeEndpointLogic(2, 1102, NodeCapacity.MEDIUM, new LamportClock(), System::nanoTime, "21",
                PROPERTIES);
    }

    @Test
    @DisplayName("a remote success: measured lookup and round trip, serialization sizes, stub class, Lamport send and merge")
    void remoteSuccess() {
        NodeEndpointLogic node2 = node2();

        RmiCallResult r = invoker(0, 400, 1_000, 6_000).invoke(lookup(CallPath.REMOTE, node2), 2, RmiCall.computeHash(5));

        assertThat(r.outcome()).isEqualTo(CallOutcome.SUCCESS);
        assertThat(r.path()).isEqualTo(CallPath.REMOTE);
        assertThat(r.method()).isEqualTo(RmiMethod.COMPUTE_HASH);
        assertThat(r.targetNodeId()).isEqualTo(2);
        assertThat(r.senderNodeId()).isEqualTo(1);
        assertThat(r.lookupNanos()).isEqualTo(400);
        assertThat(r.roundTripNanos()).isEqualTo(5_000);
        assertThat(r.timeToFailureNanos()).isNull();
        assertThat(r.argumentBytes()).isEqualTo(PayloadSizer.sizeOf(new CallContext(1, 1), 5));
        assertThat(r.resultBytes()).isEqualTo(PayloadSizer.sizeOf(r.reply()));
        assertThat(r.stubClassName()).isEqualTo(NodeEndpointLogic.class.getName());
        assertThat(r.argumentSummary()).isEqualTo("rounds=5");
        assertThat(r.resultSummary()).isEqualTo("digest " + ((HashResult) r.reply()).digestHex() + " after 5 rounds");
        // sender ticks to 1 and sends; node 2 receives max(0, 1) + 1 = 2 and replies at 3; sender merges max(1, 3) + 1 = 4
        assertThat(r.senderLamportAtSend()).isEqualTo(1);
        assertThat(r.replyLamport()).isEqualTo(3);
        assertThat(r.senderLamportAfterReply()).isEqualTo(4);
        assertThat(senderClock.current()).isEqualTo(4);
        assertThat(r.failureStage()).isNull();
        assertThat(r.failureKind()).isNull();
        assertThat(r.failureClass()).isNull();
        assertThat(r.failureMessage()).isNull();
        assertThat(r.failureCauseClass()).isNull();
    }

    @Test
    @DisplayName("a local success marshals nothing, so its sizes are null; Lamport rules still apply")
    void localSuccess() {
        RmiCallResult r = invoker(0, 10, 20, 70).invoke(lookup(CallPath.LOCAL, node2()), 2, RmiCall.echo("hi"));

        assertThat(r.path()).isEqualTo(CallPath.LOCAL);
        assertThat(r.succeeded()).isTrue();
        assertThat(r.roundTripNanos()).isEqualTo(50);
        assertThat(r.argumentBytes()).isNull();
        assertThat(r.resultBytes()).isNull();
        assertThat(r.resultSummary()).isEqualTo("\"hi\" (2 characters)");
        assertThat(r.senderLamportAfterReply()).isEqualTo(4);
    }

    @Test
    @DisplayName("a failed lookup: no lookup time, the method is never called, the clock does not tick; only the time to failure is set")
    void lookupFailure() {
        ConnectException refused = new ConnectException("Connection refused to host: 127.0.0.1",
                new java.net.ConnectException("Connection refused: connect"));
        FakeLookup lookup = new FakeLookup(CallPath.REMOTE, null, refused, log);

        RmiCallResult r = invoker(0, 700).invoke(lookup, 2, RmiCall.ping());

        assertThat(r.outcome()).isEqualTo(CallOutcome.REMOTE_FAILURE);
        assertThat(r.failureStage()).isEqualTo(CallStage.LOOKUP);
        assertThat(r.failureKind()).isEqualTo(RemoteFailureKind.CONNECTION_REFUSED);
        assertThat(r.lookupNanos()).as("the lookup did not complete").isNull();
        assertThat(r.roundTripNanos()).isNull();
        assertThat(r.timeToFailureNanos()).isEqualTo(700);
        assertThat(r.argumentBytes()).isNull();
        assertThat(r.stubClassName()).isNull();
        assertThat(r.senderLamportAtSend()).isNull();
        assertThat(senderClock.current()).isZero();
        assertThat(r.failureClass()).isEqualTo("java.rmi.ConnectException");
        assertThat(r.failureCauseClass()).isEqualTo("java.net.ConnectException");
        assertThat(r.failureMessage()).isEqualTo("Connection refused to host: 127.0.0.1; nested exception is: "
                + "\\n\\tjava.net.ConnectException: Connection refused: connect");
    }

    static Stream<Arguments> callFailures() {
        return Stream.of(
                Arguments.of(new ConnectException("Connection refused"), RemoteFailureKind.CONNECTION_REFUSED),
                Arguments.of(new UnmarshalException("Error unmarshaling return header",
                        new SocketTimeoutException("Read timed out")), RemoteFailureKind.TIMED_OUT),
                Arguments.of(new NoSuchObjectException("no such object in table"), RemoteFailureKind.NO_SUCH_OBJECT),
                Arguments.of(new MarshalException("error marshalling arguments", new NotSerializableException("x")),
                        RemoteFailureKind.MARSHAL_FAILED),
                Arguments.of(new UnmarshalException("error unmarshalling return", new EOFException()),
                        RemoteFailureKind.UNMARSHAL_FAILED),
                Arguments.of(new ServerException("RemoteException occurred in server thread", new RemoteException("x")),
                        RemoteFailureKind.METHOD_THREW),
                Arguments.of(new IllegalArgumentException("rejected by the node"), RemoteFailureKind.METHOD_THREW),
                Arguments.of(new RemoteException("something else"), RemoteFailureKind.OTHER));
    }

    @ParameterizedTest(name = "{1}: {0}")
    @MethodSource("callFailures")
    @DisplayName("a failed call: classified, no round trip, the measured time to failure, no reply merged")
    void callFailure(Exception thrown, RemoteFailureKind expected) {
        ThrowingEndpoint endpoint = new ThrowingEndpoint(thrown);

        RmiCallResult r = invoker(0, 400, 1_000, 3_500).invoke(lookup(CallPath.REMOTE, endpoint), 2, RmiCall.ping());

        assertThat(r.failureStage()).isEqualTo(CallStage.CALL);
        assertThat(r.failureKind()).isEqualTo(expected);
        assertThat(r.failureClass()).isEqualTo(thrown.getClass().getName());
        assertThat(r.lookupNanos()).isEqualTo(400);
        assertThat(r.roundTripNanos()).as("nothing came back: no round trip").isNull();
        assertThat(r.timeToFailureNanos()).isEqualTo(2_500);
        assertThat(r.argumentBytes()).isEqualTo(PayloadSizer.sizeOf(new CallContext(1, 1)));
        assertThat(r.resultBytes()).isNull();
        assertThat(r.stubClassName()).isEqualTo(ThrowingEndpoint.class.getName());
        assertThat(r.senderLamportAtSend()).isEqualTo(1);
        assertThat(r.replyLamport()).isNull();
        assertThat(r.senderLamportAfterReply()).isNull();
        assertThat(senderClock.current()).as("no reply to merge").isEqualTo(1);
        assertThat(DisplayText.isSingleLine(r.failureMessage())).isTrue();
    }

    @Test
    @DisplayName("null, never 0: exactly the measured numbers are set for each outcome, each non-zero")
    void nullNeverZero() {
        RmiCallResult lookupFailed = invoker(5, 705).invoke(
                new FakeLookup(CallPath.REMOTE, null, new NotBoundException("NodeEndpoint"), log), 2, RmiCall.ping());
        RmiCallResult callFailed = invoker(5, 405, 1_005, 3_505).invoke(
                lookup(CallPath.REMOTE, new ThrowingEndpoint(new ConnectException("x"))), 2, RmiCall.ping());
        RmiCallResult remoteOk = invoker(5, 405, 1_005, 3_505).invoke(lookup(CallPath.REMOTE, node2()), 2,
                RmiCall.ping());
        RmiCallResult localOk = invoker(5, 405, 1_005, 3_505).invoke(lookup(CallPath.LOCAL, node2()), 2,
                RmiCall.ping());

        assertThat(setNumbers(lookupFailed)).containsExactly("timeToFailureNanos");
        assertThat(setNumbers(callFailed))
                .containsExactly("argumentBytes", "lookupNanos", "senderLamportAtSend", "timeToFailureNanos");
        assertThat(setNumbers(remoteOk)).containsExactly("argumentBytes", "lookupNanos", "replyLamport",
                "resultBytes", "roundTripNanos", "senderLamportAfterReply", "senderLamportAtSend");
        assertThat(setNumbers(localOk)).containsExactly("lookupNanos", "replyLamport", "roundTripNanos",
                "senderLamportAfterReply", "senderLamportAtSend");
    }

    @Test
    @DisplayName("the failure message is the exception's own message, one line, cut to max-failure-message-length with a marker")
    void failureMessageIsBounded() {
        RmiProperties small = new RmiProperties(1000, 16, 10, 3000, 40);
        RmiCallResult longOne = invoker(small, 0).invoke(
                lookup(CallPath.REMOTE, new ThrowingEndpoint(new RemoteException("x".repeat(100)))), 2, RmiCall.ping());
        RmiCallResult shortOne = invoker(small, 0).invoke(
                lookup(CallPath.REMOTE, new ThrowingEndpoint(new RemoteException("nope"))), 2, RmiCall.ping());
        RmiCallResult multiLine = invoker(small, 0).invoke(
                lookup(CallPath.REMOTE, new ThrowingEndpoint(new RemoteException("a\nb\tc|d"))), 2, RmiCall.ping());
        RmiCallResult noMessage = invoker(small, 0).invoke(
                lookup(CallPath.REMOTE, new ThrowingEndpoint(new RemoteException())), 2, RmiCall.ping());

        assertThat(longOne.failureMessage()).hasSize(40).isEqualTo("x".repeat(39) + DisplayText.CUT_MARKER);
        assertThat(shortOne.failureMessage()).isEqualTo("nope");
        assertThat(multiLine.failureMessage()).isEqualTo("a\\nb\\tc|d");
        assertThat(noMessage.failureMessage()).isNull();
        for (RmiCallResult r : List.of(longOne, shortOne, multiLine)) {
            assertThat(r.failureMessage()).doesNotContain("at com.udcf").doesNotContain("RmiInvokerTest");
        }
    }

    @Test
    @DisplayName("a call over the configured limits is rejected before any lookup, and the clock does not move")
    void invalidCallRejectedBeforeLookup() {
        FakeLookup lookup = lookup(CallPath.REMOTE, node2());
        RmiInvoker invoker = invoker(0);

        assertThatThrownBy(() -> invoker.invoke(lookup, 2, RmiCall.computeHash(1001)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("rounds must be 1-1000, was 1001");
        assertThatThrownBy(() -> invoker.invoke(lookup, 2, RmiCall.echo("x".repeat(17))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> invoker.invoke(lookup, 0, RmiCall.ping())).isInstanceOf(IllegalArgumentException.class);
        assertThat(lookup.lookups).isZero();
        assertThat(senderClock.current()).isZero();
    }

    @Test
    @DisplayName("on the LOCAL path nothing is classified: failures are rethrown")
    void localPathRethrows() {
        IllegalStateException down = new IllegalStateException("node 2 is down");
        assertThatThrownBy(() -> invoker(0).invoke(new FakeLookup(CallPath.LOCAL, null, down, log), 2, RmiCall.ping()))
                .isSameAs(down);

        RemoteException checked = new RemoteException("impossible locally");
        assertThatThrownBy(() -> invoker(0).invoke(lookup(CallPath.LOCAL, new ThrowingEndpoint(checked)), 2,
                RmiCall.ping()))
                .isInstanceOf(IllegalStateException.class).hasCause(checked);
    }

    @Test
    @DisplayName("the caller must be a cluster node (id >= 1) with its own clock")
    void senderMustBeANode() {
        CallTimer timer = new CallTimer(new ScriptedNanoClock(0));
        for (int id : new int[] {0, -1}) {
            assertThatThrownBy(() -> new RmiInvoker(id, senderClock, PROPERTIES, timer))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("senderNodeId must be >= 1 (a cluster node with its own clock), was " + id);
        }
        assertThatThrownBy(() -> new RmiInvoker(1, null, PROPERTIES, timer)).isInstanceOf(NullPointerException.class);
        assertThat(new RmiInvoker(3, senderClock, PROPERTIES, timer).senderNodeId()).isEqualTo(3);
    }

    @Test
    @DisplayName("compare makes exactly one local call, then one remote call, on the same object: same digest")
    void compareRunsLocalThenRemoteOnce() {
        NodeEndpointLogic node2 = node2();
        RecordingEndpoint local = new RecordingEndpoint("LOCAL", node2, log);
        RecordingEndpoint remote = new RecordingEndpoint("REMOTE", node2, log);

        LocalRemoteComparison c = invoker(0, 10, 20, 520, 1_000, 1_100, 1_200, 9_200).compare(
                new FakeLookup(CallPath.LOCAL, local, null, log), new FakeLookup(CallPath.REMOTE, remote, null, log),
                2, RmiCall.computeHash(50));

        assertThat(log).containsExactly("LOCAL lookup 2", "LOCAL computeHash", "REMOTE lookup 2", "REMOTE computeHash");
        assertThat(c.localNanos()).isEqualTo(500);
        assertThat(c.remoteNanos()).isEqualTo(8_000);
        assertThat(c.overheadNanos()).isEqualTo(7_500);
        assertThat(c.ratio()).isEqualTo(16.0);
        assertThat(c.digestsMatch()).isTrue();
    }

    @Test
    @DisplayName("compare needs a LOCAL and a REMOTE lookup")
    void compareRejectsWrongPaths() {
        FakeLookup remote = lookup(CallPath.REMOTE, node2());
        assertThatThrownBy(() -> invoker(0).compare(remote, remote, 2, RmiCall.ping()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a lookup without an endpoint, or a reply from another node, is a programming error")
    void protocolFaults() {
        assertThatThrownBy(() -> invoker(0).invoke(lookup(CallPath.REMOTE, null), 2, RmiCall.ping()))
                .isInstanceOf(IllegalStateException.class).hasMessage("the lookup returned no endpoint for node 2");
        assertThatThrownBy(() -> invoker(0).invoke(lookup(CallPath.REMOTE, node2()), 3, RmiCall.ping()))
                .isInstanceOf(IllegalStateException.class).hasMessage("node 3 answered as node 2");
    }

    /** Names of the RmiCallResult number fields that are set, sorted. */
    private static Set<String> setNumbers(RmiCallResult result) {
        Set<String> set = new TreeSet<>();
        for (RecordComponent component : RmiCallResult.class.getRecordComponents()) {
            if (!Number.class.isAssignableFrom(component.getType())) {
                continue;
            }
            try {
                Object value = component.getAccessor().invoke(result);
                if (value != null) {
                    assertThat(((Number) value).longValue()).as(component.getName() + " is set, so it is measured")
                            .isNotZero();
                    set.add(component.getName());
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
        return set;
    }

    private FakeLookup lookup(CallPath path, NodeEndpoint endpoint) {
        return new FakeLookup(path, endpoint, null, log);
    }

    /** Returns the endpoint, or throws the failure; logs every lookup. */
    static final class FakeLookup implements EndpointLookup {

        private final CallPath path;
        private final NodeEndpoint endpoint;
        private final Exception failure;
        private final List<String> log;
        int lookups;

        FakeLookup(CallPath path, NodeEndpoint endpoint, Exception failure, List<String> log) {
            this.path = path;
            this.endpoint = endpoint;
            this.failure = failure;
            this.log = log;
        }

        @Override
        public NodeEndpoint lookup(int nodeId) throws RemoteException, NotBoundException {
            lookups++;
            log.add(path + " lookup " + nodeId);
            if (failure instanceof RemoteException e) {
                throw e;
            }
            if (failure instanceof NotBoundException e) {
                throw e;
            }
            if (failure instanceof RuntimeException e) {
                throw e;
            }
            return endpoint;
        }

        @Override
        public CallPath path() {
            return path;
        }
    }

    /** Every method throws the given exception (a RemoteException or a RuntimeException). */
    static final class ThrowingEndpoint implements NodeEndpoint {

        private final Exception failure;

        ThrowingEndpoint(Exception failure) {
            this.failure = failure;
        }

        private <T> T fail() throws RemoteException {
            if (failure instanceof RemoteException e) {
                throw e;
            }
            throw (RuntimeException) failure;
        }

        @Override
        public PingReply ping(CallContext context) throws RemoteException {
            return fail();
        }

        @Override
        public NodeInfo nodeInfo(CallContext context) throws RemoteException {
            return fail();
        }

        @Override
        public EchoReply echo(CallContext context, String text) throws RemoteException {
            return fail();
        }

        @Override
        public HashResult computeHash(CallContext context, int rounds) throws RemoteException {
            return fail();
        }
    }

    /** Delegates to the real logic and logs each call, so tests can see order and count. */
    static final class RecordingEndpoint implements NodeEndpoint {

        private final String label;
        private final NodeEndpoint target;
        private final List<String> log;

        RecordingEndpoint(String label, NodeEndpoint target, List<String> log) {
            this.label = label;
            this.target = target;
            this.log = log;
        }

        @Override
        public PingReply ping(CallContext context) throws RemoteException {
            log.add(label + " ping");
            return target.ping(context);
        }

        @Override
        public NodeInfo nodeInfo(CallContext context) throws RemoteException {
            log.add(label + " nodeInfo");
            return target.nodeInfo(context);
        }

        @Override
        public EchoReply echo(CallContext context, String text) throws RemoteException {
            log.add(label + " echo");
            return target.echo(context, text);
        }

        @Override
        public HashResult computeHash(CallContext context, int rounds) throws RemoteException {
            log.add(label + " computeHash");
            return target.computeHash(context, rounds);
        }
    }
}
