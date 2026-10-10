package com.udcf.modules.rmi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.rmi.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalRemoteComparisonTest {

    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);

    static RmiCallResult ok(CallPath path, RmiCall call, EndpointReply reply, long roundTripNanos) {
        boolean remote = path == CallPath.REMOTE;
        return RmiCallResult.success(call, reply.nodeId(), 1, path, reply, 100, roundTripNanos,
                remote ? 80L : null, remote ? 90L : null, "Stub", 1, reply.lamportTime() + 1);
    }

    private static RmiCallResult ping(CallPath path, long roundTripNanos) {
        return ok(path, RmiCall.ping(), new PingReply(2, 5), roundTripNanos);
    }

    private static RmiCallResult hash(CallPath path, String digest) {
        return ok(path, RmiCall.computeHash(10), new HashResult(2, 5, 10, "s", digest, 7), 1_000);
    }

    @Test
    @DisplayName("both sides measured: overhead is remote minus local, ratio is remote over local")
    void bothMeasured() {
        LocalRemoteComparison c = LocalRemoteComparison.of(ping(CallPath.LOCAL, 1_000), ping(CallPath.REMOTE, 51_000));

        assertThat(c.method()).isEqualTo(RmiMethod.PING);
        assertThat(c.targetNodeId()).isEqualTo(2);
        assertThat(c.localNanos()).isEqualTo(1_000);
        assertThat(c.remoteNanos()).isEqualTo(51_000);
        assertThat(c.overheadNanos()).isEqualTo(50_000);
        assertThat(c.ratio()).isEqualTo(51.0);
        assertThat(c.digestsMatch()).isNull();
    }

    @Test
    @DisplayName("a remote side faster than the local one gives a negative overhead and a ratio below 1, unclamped")
    void remoteFasterIsNotClamped() {
        LocalRemoteComparison c = LocalRemoteComparison.of(ping(CallPath.LOCAL, 80_000), ping(CallPath.REMOTE, 20_000));

        assertThat(c.overheadNanos()).isEqualTo(-60_000);
        assertThat(c.ratio()).isEqualTo(0.25).isLessThan(1.0);
    }

    @Test
    @DisplayName("a failed remote side leaves its time, the overhead and the ratio null")
    void failedSideIsNull() {
        RmiCallResult failed = RmiCallResult.callFailure(RmiCall.ping(), 2, 1, 100, 300, 80, "Stub", 1,
                new ConnectException("refused"), 50);

        LocalRemoteComparison c = LocalRemoteComparison.of(ping(CallPath.LOCAL, 1_000), failed);

        assertThat(c.localNanos()).isEqualTo(1_000);
        assertThat(c.remoteNanos()).isNull();
        assertThat(c.overheadNanos()).isNull();
        assertThat(c.ratio()).isNull();
    }

    @Test
    @DisplayName("a local time of 0 makes the ratio undefined (null), not infinite")
    void zeroLocalTime() {
        LocalRemoteComparison c = LocalRemoteComparison.of(ping(CallPath.LOCAL, 0), ping(CallPath.REMOTE, 4_000));

        assertThat(c.overheadNanos()).isEqualTo(4_000);
        assertThat(c.ratio()).isNull();
    }

    @Test
    @DisplayName("digestsMatch compares the two computeHash digests")
    void digestsMatch() {
        assertThat(LocalRemoteComparison.of(hash(CallPath.LOCAL, DIGEST_A), hash(CallPath.REMOTE, DIGEST_A))
                .digestsMatch()).isTrue();
        assertThat(LocalRemoteComparison.of(hash(CallPath.LOCAL, DIGEST_A), hash(CallPath.REMOTE, DIGEST_B))
                .digestsMatch()).isFalse();
    }

    @Test
    @DisplayName("the two sides must be LOCAL and REMOTE, with the same method and node")
    void mismatchesRejected() {
        assertThatThrownBy(() -> LocalRemoteComparison.of(ping(CallPath.REMOTE, 1), ping(CallPath.REMOTE, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalRemoteComparison.of(ping(CallPath.LOCAL, 1), hash(CallPath.REMOTE, DIGEST_A)))
                .isInstanceOf(IllegalArgumentException.class);
        RmiCallResult otherNode = ok(CallPath.REMOTE, RmiCall.ping(), new PingReply(3, 5), 1);
        assertThatThrownBy(() -> LocalRemoteComparison.of(ping(CallPath.LOCAL, 1), otherNode))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalRemoteComparison(RmiMethod.PING, 0, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
