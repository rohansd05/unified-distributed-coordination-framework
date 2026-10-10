package com.udcf.modules.rmi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.rmi.ConnectException;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validation, factories and summaries of the Experiment 1 value records. */
class RmiRecordsTest {

    private static final String DIGEST = "0123456789abcdef".repeat(4);

    @Test
    @DisplayName("a call context comes from a cluster node (id >= 1) with a non-negative Lamport time")
    void callContextValidation() {
        assertThat(new CallContext(1, 0).senderNodeId()).isEqualTo(1);
        assertThatThrownBy(() -> new CallContext(0, 5)).hasMessage("senderNodeId must be >= 1, was 0");
        assertThatThrownBy(() -> new CallContext(1, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("replies validate the node id, Lamport time and their own fields")
    void replyValidation() {
        assertThatThrownBy(() -> new PingReply(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PingReply(1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EchoReply(1, 1, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NodeInfo(1, 1, 0, "FAST", 0, "21")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NodeInfo(1, 1, 1101, "FAST", -1, "21")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NodeInfo(1, 1, 1101, null, 0, "21")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HashResult(1, 1, 0, "s", DIGEST, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HashResult(1, 1, 1, "s", DIGEST.toUpperCase(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HashResult(1, 1, 1, "s", DIGEST.substring(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HashResult(1, 1, 1, "s", DIGEST, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new HashResult(1, 1, 1, "s", DIGEST, 0).digestHex()).isEqualTo(DIGEST);
    }

    @Test
    @DisplayName("exactly four reply types may cross RMI")
    void replyTypesAreSealed() {
        assertThat(EndpointReply.class.isSealed()).isTrue();
        assertThat(Arrays.stream(EndpointReply.class.getPermittedSubclasses()).map(Class::getSimpleName))
                .containsExactlyInAnyOrder("PingReply", "NodeInfo", "EchoReply", "HashResult");
    }

    @Test
    @DisplayName("a call has exactly the argument its method takes")
    void callShapes() {
        assertThat(RmiCall.ping()).isEqualTo(new RmiCall(RmiMethod.PING, null, null));
        assertThat(RmiCall.computeHash(9).rounds()).isEqualTo(9);
        assertThatThrownBy(() -> new RmiCall(null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RmiCall(RmiMethod.ECHO, null, null)).hasMessage("ECHO needs a text");
        assertThatThrownBy(() -> new RmiCall(RmiMethod.PING, "x", null)).hasMessage("PING takes no text");
        assertThatThrownBy(() -> new RmiCall(RmiMethod.COMPUTE_HASH, null, null)).hasMessage("COMPUTE_HASH needs rounds");
        assertThatThrownBy(() -> new RmiCall(RmiMethod.ECHO, "x", 3)).hasMessage("ECHO takes no rounds");

        CallContext context = new CallContext(1, 2);
        assertThat(RmiCall.nodeInfo().arguments(context)).containsExactly(context);
        assertThat(RmiCall.echo("x").arguments(context)).containsExactly(context, "x");
        assertThat(RmiCall.computeHash(9).arguments(context)).containsExactly(context, 9);
    }

    @Test
    @DisplayName("summaries are one line: newline and tab escaped, a pipe kept")
    void summariesAreSingleLine() {
        RmiCall echo = RmiCall.echo("a\nb\t|c");

        assertThat(echo.argumentSummary()).isEqualTo("text=\"a\\nb\\t|c\" (6 characters)");
        assertThat(RmiCall.resultSummary(new EchoReply(2, 3, "a\nb\t|c"))).isEqualTo("\"a\\nb\\t|c\" (6 characters)");
        assertThat(RmiCall.ping().argumentSummary()).isEqualTo("no arguments");
        assertThat(RmiCall.computeHash(7).argumentSummary()).isEqualTo("rounds=7");
        assertThat(RmiCall.resultSummary(new PingReply(2, 3))).isEqualTo("reply from node 2");
        assertThat(RmiCall.resultSummary(new NodeInfo(2, 3, 1102, "MEDIUM", 40, "21.0.12")))
                .isEqualTo("node 2, registry port 1102, MEDIUM, up 40 ms, Java 21.0.12");
        assertThat(RmiCall.resultSummary(new HashResult(2, 3, 5, "s", DIGEST, 9)))
                .isEqualTo("digest " + DIGEST + " after 5 rounds");

        RmiCallResult result = RmiCallResult.success(echo, 2, 1, CallPath.REMOTE, new EchoReply(2, 3, "a\nb\t|c"),
                1, 2, 3L, 4L, "Stub", 1, 4);
        assertThat(DisplayText.isSingleLine(result.argumentSummary())).isTrue();
        assertThat(DisplayText.isSingleLine(result.resultSummary())).isTrue();
    }

    @Test
    @DisplayName("success results: the reply comes from the target, and sizes exist on the REMOTE path only")
    void successRules() {
        PingReply reply = new PingReply(2, 3);
        assertThatThrownBy(() -> RmiCallResult.success(RmiCall.ping(), 3, 1, CallPath.REMOTE, reply, 1, 2, 3L, 4L,
                "Stub", 1, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RmiCallResult.success(RmiCall.ping(), 2, 1, CallPath.LOCAL, reply, 1, 2, 3L, 4L,
                "Stub", 1, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RmiCallResult.success(RmiCall.ping(), 2, 1, CallPath.REMOTE, reply, 1, 2, null, null,
                "Stub", 1, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RmiCallResult.success(RmiCall.ping(), 2, 0, CallPath.REMOTE, reply, 1, 2, 3L, 4L,
                "Stub", 1, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThat(RmiCallResult.success(RmiCall.ping(), 2, 1, CallPath.LOCAL, reply, 1, 2, null, null, "Stub", 1, 4)
                .replyLamport()).isEqualTo(3);
    }

    @Test
    @DisplayName("failure results: REMOTE only; every failure has a time to failure and no round trip; a lookup time only after a completed lookup")
    void failureRules() {
        ConnectException refused = new ConnectException("refused");
        RmiCallResult atCall = RmiCallResult.callFailure(RmiCall.ping(), 2, 1, 10, 250, 80, "Stub", 1, refused, 300);

        assertThat(atCall.roundTripNanos()).isNull();
        assertThat(atCall.timeToFailureNanos()).isEqualTo(250);
        assertThat(atCall.failureMessage()).isEqualTo("refused");
        assertThat(atCall.failureCauseClass()).isNull();

        assertThat(atCall.lookupNanos()).isEqualTo(10);

        RmiCallResult atLookup = RmiCallResult.lookupFailure(RmiCall.ping(), 2, 1, 10, refused, 300);
        assertThat(atLookup.timeToFailureNanos()).isEqualTo(10);
        assertThat(atLookup.lookupNanos()).isNull();
        assertThat(atLookup.roundTripNanos()).isNull();

        List<Object> base = Arrays.asList(atCall.method(), 2, 1, CallPath.LOCAL, "no arguments", null, null, 10L, null,
                250L, 80L, null, "Stub", CallOutcome.REMOTE_FAILURE, CallStage.CALL, RemoteFailureKind.CONNECTION_REFUSED,
                "java.rmi.ConnectException", "refused", null, 1L, null, null);
        assertThatThrownBy(() -> build(base)).hasMessage("only a REMOTE call can end in REMOTE_FAILURE");

        List<Object> withRoundTrip = Arrays.asList(base.toArray());
        withRoundTrip.set(3, CallPath.REMOTE);
        withRoundTrip.set(8, 250L);
        assertThatThrownBy(() -> build(withRoundTrip)).isInstanceOf(IllegalArgumentException.class);

        List<Object> multiLine = Arrays.asList(base.toArray());
        multiLine.set(3, CallPath.REMOTE);
        multiLine.set(17, "line one\nline two");
        assertThatThrownBy(() -> build(multiLine))
                .hasMessage("failureMessage must be a single line without control characters");

        List<Object> noTimeToFailure = Arrays.asList(base.toArray());
        noTimeToFailure.set(3, CallPath.REMOTE);
        noTimeToFailure.set(9, null);
        assertThatThrownBy(() -> build(noTimeToFailure))
                .hasMessage("a failure needs its stage, kind, class and time to failure");

        List<Object> lookupStageWithLookupTime = Arrays.asList(base.toArray());
        lookupStageWithLookupTime.set(3, CallPath.REMOTE);
        lookupStageWithLookupTime.set(14, CallStage.LOOKUP);
        lookupStageWithLookupTime.set(10, null);
        lookupStageWithLookupTime.set(12, null);
        lookupStageWithLookupTime.set(19, null);
        assertThatThrownBy(() -> build(lookupStageWithLookupTime)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lookup time");
    }

    private static RmiCallResult build(List<Object> v) {
        return new RmiCallResult((RmiMethod) v.get(0), (int) v.get(1), (int) v.get(2), (CallPath) v.get(3),
                (String) v.get(4), (String) v.get(5), (EndpointReply) v.get(6), (Long) v.get(7), (Long) v.get(8),
                (Long) v.get(9), (Long) v.get(10), (Long) v.get(11), (String) v.get(12), (CallOutcome) v.get(13),
                (CallStage) v.get(14), (RemoteFailureKind) v.get(15), (String) v.get(16), (String) v.get(17),
                (String) v.get(18), (Long) v.get(19), (Long) v.get(20), (Long) v.get(21));
    }
}
