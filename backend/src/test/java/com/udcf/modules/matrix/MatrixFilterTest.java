package com.udcf.modules.matrix;

import com.udcf.modules.rmi.CallContext;
import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.RemoteFailureClassifier;
import com.udcf.modules.rmi.RemoteFailureKind;
import com.udcf.modules.rmi.RmiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * The MatrixEndpoint's serial filter (E1b's: only CallContext, every array rejected) on a real node. Arrays
 * forged into the CallContext slot are refused by the filter before the method runs (a large one may come
 * back as CONNECTION_LOST, size dependent, as measured in E9b); a String is never shown to the filter and RMI's
 * dispatch refuses it ("argument type mismatch", METHOD_THREW). The forged calls use RMI's own client reference
 * with the real method hash, as E1b and E9b did.
 *
 * <p>Proof that nothing ran, by a barrier: after the refused calls, one valid fetch from node 1 with Lamport
 * time 0; then the node keeps only run 1 (a forged compute of run 99 that had run would have kept it) and its
 * clock moved by exactly 2, what the fetch alone does.</p>
 *
 * <p>Test ports (Track C, E10b): RMI base 28715 (28716-28718); every other base is never bound.</p>
 */
class MatrixFilterTest {

    private static final int BASE = 28715;
    private static final int N = 10;
    private static final long SCATTER = 21;
    private static final long BROADCAST = 22;
    private static final Set<RemoteFailureKind> OVERSIZE_REFUSED =
            EnumSet.of(RemoteFailureKind.ARGUMENTS_REJECTED, RemoteFailureKind.CONNECTION_LOST);

    private MatrixRmiFixture fixture;
    private MatrixEndpoint stub;
    private RemoteRef ref;
    private Method computeBlock;
    private long hash;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new MatrixRmiFixture(BASE, MatrixRmiFixture.RUN);
        fixture.startAll();
        double[] a = MatrixGenerator.a(7, N);
        fixture.collectives.deliver(2, new CallContext(1, 1), SCATTER, 1, RowPartition.rows(a, RowPartition.of(N, 3).get(1)));
        fixture.collectives.deliver(2, new CallContext(1, 1), BROADCAST, 1, MatrixGenerator.b(7, N));
        fixture.matrices.compute(2, new CallContext(1, 1), 1, 1, N, SCATTER, BROADCAST);
        stub = fixture.lookup.lookup(2, MatrixEndpoint.BOUND_NAME, MatrixEndpoint.class);
        ref = ((RemoteObjectInvocationHandler) Proxy.getInvocationHandler(stub)).getRef();
        computeBlock = MatrixEndpoint.class.getMethod("computeBlock", CallContext.class, long.class, int.class, int.class,
                long.class, long.class);
        hash = RmiTestSupport.methodHash(computeBlock);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private Throwable forged(Object context) {
        return catchThrowable(() -> ref.invoke(stub, computeBlock, new Object[] {context, 99L, 1, N, SCATTER, BROADCAST}, hash));
    }

    private void assertNothingRan(long clockBefore) throws Exception {
        BlockReply barrier = fixture.matrices.fetch(2, new CallContext(1, 0), 1, 1);
        assertThat(fixture.matrix(2).keptRunIds()).containsExactly(1L);
        assertThat(barrier.lamportTime()).isEqualTo(clockBefore + 2);
        assertThat(fixture.cluster.node(2).clock().current()).isEqualTo(clockBefore + 2);
    }

    @Test
    @DisplayName("long[], Object[] and CallContext[] in the CallContext slot are refused by the filter, a String by RMI's dispatch; nothing runs")
    void forgedArgumentsAreRefused() throws Exception {
        long clockBefore = fixture.cluster.node(2).clock().current();
        for (Object forged : new Object[] {new long[] {1L}, new Object[] {1.0}, new CallContext[] {new CallContext(1, 0)}}) {
            assertThat(RemoteFailureClassifier.classify(forged(forged), CallStage.CALL)).as(forged.getClass().getName())
                    .isEqualTo(RemoteFailureKind.ARGUMENTS_REJECTED);
        }
        Throwable string = forged("not a context");
        assertThat(string).isInstanceOf(IllegalArgumentException.class).hasMessage("argument type mismatch");
        assertThat(RemoteFailureClassifier.classify(string, CallStage.CALL)).isEqualTo(RemoteFailureKind.METHOD_THREW);

        assertNothingRan(clockBefore);
    }

    @Test
    @DisplayName("an oversized double[] (100001 values) in the CallContext slot is refused before the method runs")
    void oversizedArrayIsRefused() throws Exception {
        long clockBefore = fixture.cluster.node(2).clock().current();
        assertThat(RemoteFailureClassifier.classify(forged(new double[100_001]), CallStage.CALL)).isIn(OVERSIZE_REFUSED);
        assertNothingRan(clockBefore);
    }

    @Test
    @DisplayName("the genuine call through the same forged path passes: the refusals above are the filter and RMI, not the technique")
    void genuineCallThroughTheSamePath() throws Exception {
        Object reply = ref.invoke(stub, computeBlock, new Object[] {new CallContext(1, 1), 2L, 1, N, SCATTER, BROADCAST}, hash);
        assertThat(reply).isInstanceOf(ComputeAck.class);
        assertThat(((ComputeAck) reply).rowCount()).isEqualTo(3);
    }
}
