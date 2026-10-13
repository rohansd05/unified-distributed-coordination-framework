package com.udcf.modules.mpi;

import com.udcf.core.clock.LamportClock;
import com.udcf.modules.rmi.CallStage;

import java.rmi.ConnectException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Real {@link CollectiveResult}s for the E9c unit tests, made by E9a's executor over the no-network
 * {@link ScriptedCollectiveTransport} (real endpoint logic per node) on a three-node cluster. No
 * socket, no port.
 */
final class MpiTestResults {

    static final MpiProperties PROPERTIES = CollectiveRmiFixture.LOCAL;
    static final List<Integer> NODES = List.of(1, 2, 3);

    private MpiTestResults() {
    }

    /** A time source that moves 1000 ns on every read. */
    static LongSupplier stepping() {
        AtomicLong now = new AtomicLong();
        return () -> now.addAndGet(1_000);
    }

    static CollectiveResult run(long id, CollectiveOperation operation, int root, int count, Set<Integer> live,
                                ScriptedCollectiveTransport transport, LongSupplier clock) {
        CollectiveRequest request = new CollectiveRequest(id, operation, root, NODES, live, count, 42);
        return new CollectiveExecutor(PROPERTIES, clock).execute(request, new LamportClock(), transport);
    }

    static ScriptedCollectiveTransport transport() {
        return new ScriptedCollectiveTransport(PROPERTIES, 1, 2, 3);
    }

    static CollectiveResult complete(CollectiveOperation operation, int root) {
        return run(1, operation, root, 10, Set.of(1, 2, 3), transport(), stepping());
    }

    /** Node 3's endpoint is refused at the lookup (as a node whose RMI is not running). */
    static CollectiveResult partialLookup(CollectiveOperation operation) {
        ScriptedCollectiveTransport transport = transport()
                .failing(3, CallStage.LOOKUP, new ConnectException("Connection refused to host: 127.0.0.1"));
        return run(1, operation, 1, 10, Set.of(1, 2, 3), transport, stepping());
    }

    /** The deadline passes after node 2's transfer, so node 3's is NOT_ATTEMPTED. */
    static CollectiveResult notAttempted() {
        long late = (PROPERTIES.collectiveDeadlineMillis() + 1) * 1_000_000L;
        return run(1, CollectiveOperation.BROADCAST, 1, 10, Set.of(1, 2, 3), transport(),
                new com.udcf.modules.rmi.ScriptedNanoClock(0, 0, 0, 100, 100, late));
    }

    /** A scatter of 2 values over 3 live nodes: COUNT_BELOW_PARTICIPANTS. */
    static CollectiveResult refused(long id) {
        return run(id, CollectiveOperation.SCATTER, 1, 2, Set.of(1, 2, 3), transport(), stepping());
    }
}
