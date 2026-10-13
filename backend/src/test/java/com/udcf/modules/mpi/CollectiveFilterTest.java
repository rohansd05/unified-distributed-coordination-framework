package com.udcf.modules.mpi;

import com.udcf.modules.rmi.CallContext;
import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.RemoteFailureClassifier;
import com.udcf.modules.rmi.RemoteFailureKind;
import com.udcf.modules.rmi.RmiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.rmi.server.RemoteObjectInvocationHandler;
import java.rmi.server.RemoteRef;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The CollectiveEndpoint's serial filter on a real node, at the local (100000) and the public
 * (10000) max-payload-elements.
 *
 * <p>Exactly the limit passes. One more is refused before the method runs; what the caller sees
 * depends on the payload's size (measured in E9b on Windows): a small refused payload comes back
 * as ARGUMENTS_REJECTED, a large one as CONNECTION_LOST, because the node refuses at the array's
 * header, replies and closes while the caller is still writing. A long[], Object[] or int[] forged
 * into the double[] slot is refused by the filter (ARGUMENTS_REJECTED); a String is never shown to
 * the filter (E1b known issue) and RMI's dispatch refuses it ("argument type mismatch",
 * METHOD_THREW). The forged calls use RMI's own client reference with the real method hash, as
 * E1b's filter test did.</p>
 *
 * <p>Proof that nothing ran, by a barrier: after the refused call, one valid call to the same node
 * (see {@link CollectiveRmiFixture#barrier}); then the store holds only the valid ids and the clock
 * moved by exactly what the valid call alone moves it.</p>
 *
 * <p>Test ports (Track C, E9b): RMI base 28580 (28581-28583).</p>
 */
class CollectiveFilterTest {

    private static final int BASE = 28580;

    /** What a caller may see for a refused oversized payload (size dependent; see the class comment). */
    private static final Set<RemoteFailureKind> OVERSIZE_REFUSED =
            EnumSet.of(RemoteFailureKind.ARGUMENTS_REJECTED, RemoteFailureKind.CONNECTION_LOST);

    private CollectiveRmiFixture fixture;

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    /** After a refused call: a barrier call, then only {@code storedBefore} and the barrier's id are stored, and the clock moved by exactly 2. */
    private void assertNothingRan(int nodeId, long clockBefore, long barrierId, Long... storedBefore) throws Exception {
        ReceiveReceipt barrier = fixture.barrier(nodeId, barrierId);
        Long[] expected = java.util.Arrays.copyOf(storedBefore, storedBefore.length + 1);
        expected[storedBefore.length] = barrierId;
        assertThat(fixture.logic(nodeId).storedIds()).containsExactly(expected);
        assertThat(barrier.lamportTime()).isEqualTo(clockBefore + 2);
        assertThat(fixture.cluster.node(nodeId).clock().current()).isEqualTo(clockBefore + 2);
    }

    private void limitHolds(MpiProperties properties) throws Exception {
        fixture = new CollectiveRmiFixture(BASE, properties);
        fixture.startAll();
        int max = properties.maxPayloadElements();

        ReceiveReceipt receipt = fixture.transport.deliver(2, new CallContext(1, 1), 7, 1, PayloadGenerator.vector(1, max));
        assertThat(receipt.elementCount()).isEqualTo(max);
        assertThat(receipt.checksum()).isEqualTo(VectorChecksum.of(PayloadGenerator.vector(1, max)));

        long clockBefore = fixture.cluster.node(2).clock().current();
        Throwable refused = catchThrowable(() -> fixture.transport.deliver(2, new CallContext(1, 0), 8, 1, new double[max + 1]));
        assertThat(refused).isInstanceOf(TransferException.class);
        TransferException failure = (TransferException) refused;
        assertThat(failure.stage()).isEqualTo(CallStage.CALL);
        assertThat(RemoteFailureClassifier.classify(failure.original(), CallStage.CALL)).isIn(OVERSIZE_REFUSED);

        assertNothingRan(2, clockBefore, 9, 7L);
    }

    @Test
    @DisplayName("local limit: exactly 100000 doubles pass; 100001 are refused before the method runs (barrier proof)")
    void localLimit() throws Exception {
        limitHolds(CollectiveRmiFixture.LOCAL);
    }

    @Test
    @DisplayName("public limit: exactly 10000 doubles pass; 10001 are refused before the method runs (barrier proof)")
    void publicLimit() throws Exception {
        limitHolds(CollectiveRmiFixture.PUBLIC);
    }

    @Test
    @DisplayName("long[], Object[] and int[] forged into the double[] slot are refused by the filter; a String by RMI's dispatch; nothing runs")
    void forgedArgumentsAreRefused() throws Exception {
        fixture = new CollectiveRmiFixture(BASE, CollectiveRmiFixture.LOCAL);
        fixture.startAll();
        CollectiveEndpoint stub = fixture.lookup.lookup(2, CollectiveEndpoint.BOUND_NAME, CollectiveEndpoint.class);
        RemoteRef ref = ((RemoteObjectInvocationHandler) Proxy.getInvocationHandler(stub)).getRef();
        Method receive = CollectiveEndpoint.class.getMethod("receive", CallContext.class, long.class, int.class, double[].class);
        long hash = RmiTestSupport.methodHash(receive);
        long clockBefore = fixture.cluster.node(2).clock().current();

        for (Object forged : new Object[] {new long[] {1L}, new Object[] {1.0}, new int[] {1}}) {
            Throwable thrown = catchThrowable(() -> ref.invoke(stub, receive, new Object[] {new CallContext(1, 0), 7L, 1, forged}, hash));
            assertThat(RemoteFailureClassifier.classify(thrown, CallStage.CALL)).as(forged.getClass().getName())
                    .isEqualTo(RemoteFailureKind.ARGUMENTS_REJECTED);
        }
        Throwable string = catchThrowable(() -> ref.invoke(stub, receive, new Object[] {new CallContext(1, 0), 7L, 1, "not an array"}, hash));
        assertThat(string).isInstanceOf(IllegalArgumentException.class).hasMessage("argument type mismatch");
        assertThat(RemoteFailureClassifier.classify(string, CallStage.CALL)).isEqualTo(RemoteFailureKind.METHOD_THREW);

        assertNothingRan(2, clockBefore, 9);
    }

    @Test
    @DisplayName("the genuine call through the same forged path passes: the refusals above are the filter and RMI, not the technique")
    void genuineCallThroughTheSamePath() throws Exception {
        fixture = new CollectiveRmiFixture(BASE, CollectiveRmiFixture.LOCAL);
        fixture.startAll();
        CollectiveEndpoint stub = fixture.lookup.lookup(2, CollectiveEndpoint.BOUND_NAME, CollectiveEndpoint.class);
        RemoteRef ref = ((RemoteObjectInvocationHandler) Proxy.getInvocationHandler(stub)).getRef();
        Method receive = CollectiveEndpoint.class.getMethod("receive", CallContext.class, long.class, int.class, double[].class);

        Object reply = ref.invoke(stub, receive, new Object[] {new CallContext(1, 1), 7L, 1, new double[] {1, 2}},
                RmiTestSupport.methodHash(receive));
        assertThat(reply).isInstanceOf(ReceiveReceipt.class);
        assertThat(((ReceiveReceipt) reply).elementCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("contribute (no array argument) passes the same filter")
    void contributePasses() throws Exception {
        fixture = new CollectiveRmiFixture(BASE, CollectiveRmiFixture.PUBLIC);
        fixture.startAll();
        Contribution contribution = fixture.transport.collect(3, new CallContext(1, 1), 7, 2, 10_000, 5);
        assertThat(contribution.length()).isEqualTo(10_000);
        assertThat(contribution.checksum()).isEqualTo(VectorChecksum.of(PayloadGenerator.contribution(3, 10_000, 5)));
    }
}
