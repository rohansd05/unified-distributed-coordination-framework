package com.udcf.modules.matrix;

import com.udcf.core.clock.LamportClock;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.modules.matrix.InMemoryMatrixNodes.Step;
import com.udcf.modules.mpi.CollectiveOutcome;
import com.udcf.modules.mpi.MpiProperties;
import com.udcf.modules.mpi.TransferRecord;
import com.udcf.modules.mpi.TransferStatus;
import com.udcf.modules.mpi.VectorChecksum;
import com.udcf.modules.rmi.CallContext;
import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.RemoteFailureKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runner against an in-memory cluster of real node logics (no sockets): exact products, every failure
 * of the E10b failure matrix as data, the null rules, the order of the timed parts, the run deadline, and
 * the concurrency of the compute phase (proved with a latch, never with a sleep).
 */
class ParallelMatrixExecutorTest {

    private static final MpiProperties MPI = new MpiProperties(100_000, 10_000, 20, 8, 300);
    private static final MatrixLimits LIMITS = new MatrixLimits(new MatrixProperties(2, List.of(50, 100, 200, 316)), MPI);
    private static final MatrixRunProperties RUN = new MatrixRunProperties(1, 2000, 4);
    private static final MatrixRunBudget BUDGET = new MatrixRunBudget(1000, 3000, 10_000, 2000, 1);
    private static final long SEED = 42;
    private static final long STEP = 1000;
    private static final long DEADLINE_NANOS = 10_000 * 1_000_000L;
    private static final List<Integer> THREE = List.of(1, 2, 3);

    private final MatrixDispatchPool pool = new MatrixDispatchPool(5);

    @AfterEach
    void closePool() {
        pool.close();
    }

    /** A thread-safe clock that advances STEP on every read, can jump, and counts its reads. */
    static class SteppingClock implements LongSupplier {
        private final AtomicLong now = new AtomicLong();
        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public long getAsLong() {
            reads.incrementAndGet();
            return now.getAndAdd(STEP);
        }

        void jump(long nanos) {
            now.addAndGet(nanos);
        }

        int reads() {
            return reads.get();
        }
    }

    /** A stepping clock that also records which method of the runner read it (the closest runner frame). */
    static final class LabelledClock extends SteppingClock {
        private static final String RUN_CLASS = ParallelMatrixExecutor.class.getName() + "$Run";
        final List<String> labels = new CopyOnWriteArrayList<>();

        @Override
        public long getAsLong() {
            labels.add(StackWalker.getInstance().walk(frames -> frames.filter(f -> f.getClassName().equals(RUN_CLASS))
                    .findFirst().map(StackWalker.StackFrame::getMethodName).orElse("?")));
            return super.getAsLong();
        }
    }

    private ParallelMatrixExecutor executor(LongSupplier clock) {
        return executor(RUN, BUDGET, clock);
    }

    private ParallelMatrixExecutor executor(MatrixRunProperties run, MatrixRunBudget budget, LongSupplier clock) {
        return new ParallelMatrixExecutor(LIMITS, run, MPI, budget, pool, clock);
    }

    private static InMemoryMatrixNodes nodes(int... ids) {
        return new InMemoryMatrixNodes(MPI, RUN, ids);
    }

    private static MatrixRunResult run(ParallelMatrixExecutor executor, InMemoryMatrixNodes nodes, int n, int root,
                                       List<Integer> participants, Set<Integer> live) {
        return executor.run(new MatrixRunRequest(1, n, SEED, root, participants, live), nodes.clock(root), nodes, nodes,
                nodes.executor(root));
    }

    /** n = 10 over nodes 1, 2, 3 (rows 4, 3, 3), root 1. */
    private MatrixRunResult runThree(InMemoryMatrixNodes nodes, LongSupplier clock) {
        return run(executor(clock), nodes, 10, 1, THREE, Set.copyOf(THREE));
    }

    private static double[] product(int n) {
        return MatrixMultiply.multiply(MatrixGenerator.a(SEED, n), MatrixGenerator.b(SEED, n), n);
    }

    private static final Exception REFUSED = new java.rmi.ConnectException("Connection refused",
            new java.net.ConnectException("Connection refused"));
    private static final Exception LOST = new java.rmi.UnmarshalException("lost", new java.net.SocketException("reset"));

    /** The null rules after PARTIAL or FAILED: C unknown, passed never true. */
    private static void assertIncomplete(MatrixRunResult result, int missing) {
        MatrixVerification verification = result.verification();
        assertThat(verification.blocksMissing()).isEqualTo(missing);
        assertThat(verification.parallelChecksum()).isNull();
        assertThat(verification.checksumsMatch()).isNull();
        assertThat(verification.passed()).isNotEqualTo(Boolean.TRUE);
    }

    // ------------------------------------------------------------------ complete runs

    @Test
    @DisplayName("COMPLETE and bit for bit equal to the sequential product for n in {2, 7, 16, 50}, p from 1 to 4, root first or last")
    void completeRunsAreExact() {
        for (int n : new int[] {2, 7, 16, 50}) {
            for (int p = 1; p <= Math.min(n, 4); p++) {
                List<Integer> participants = IntStream.rangeClosed(1, p).boxed().toList();
                for (int root : p == 1 ? List.of(1) : List.of(1, p)) {
                    String label = "n=" + n + " p=" + p + " root=" + root;
                    InMemoryMatrixNodes nodes = nodes(1, 2, 3, 4);
                    MatrixRunResult result = run(executor(new SteppingClock()), nodes, n, root, participants, Set.copyOf(participants));

                    String expected = VectorChecksum.of(product(n));
                    assertThat(result.outcome()).as(label).isEqualTo(CollectiveOutcome.COMPLETE);
                    assertThat(result.verification().passed()).as(label).isTrue();
                    assertThat(result.verification().elementsCompared()).as(label).isEqualTo((long) n * n);
                    assertThat(result.verification().mismatchedElements()).as(label).isZero();
                    assertThat(result.verification().parallelChecksum()).as(label).isEqualTo(expected);
                    assertThat(result.verification().sequentialChecksum()).as(label).isEqualTo(expected);
                    assertThat(result.rootRank()).as(label).isEqualTo(participants.indexOf(root));
                    assertThat(result.rowBlocks()).as(label).isEqualTo(RowPartition.of(n, p));
                    assertThat(result.computes()).as(label).allMatch(c -> c.status() == ComputeRecord.Status.DONE);
                    for (TransferRecord fetch : result.gather()) {
                        boolean own = fetch.rank() == result.rootRank();
                        assertThat(fetch.status()).as(label).isEqualTo(own ? TransferStatus.LOCAL : TransferStatus.DELIVERED);
                        assertThat(fetch.verified()).as(label).isTrue();
                    }
                    assertThat(result.inputsDelivered()).as(label).isEqualTo(p == 1 ? null : Boolean.TRUE);
                    MatrixTiming timing = result.timing();
                    assertThat(timing.speedup()).as(label).isNotNull();
                    assertThat(timing.computeBoundSpeedup()).as(label).isNotNull();
                    assertThat(timing.gatherNanos() == null).as(label).isEqualTo(p == 1);
                    assertThat(timing.scatterNanos() == null).as(label).isEqualTo(p == 1);
                    assertThat(nodes.calls(Step.COMPUTE)).as(label).hasSize(p - 1);
                }
            }
        }
    }

    @Test
    @DisplayName("Lamport: the root ticks before every compute and fetch and merges each reply; the nodes update on receipt")
    void lamportOnEveryCall() {
        InMemoryMatrixNodes nodes = nodes(1, 2, 3);
        MatrixRunResult result = runThree(nodes, new SteppingClock());

        for (ComputeRecord compute : result.computes().subList(1, 3)) {
            assertThat(compute.replyLamport()).isGreaterThan(compute.senderLamportAtSend());
            assertThat(compute.lamportAfterReply()).isGreaterThan(compute.replyLamport());
        }
        for (TransferRecord fetch : result.gather().subList(1, 3)) {
            assertThat(fetch.replyLamport()).isGreaterThan(fetch.senderLamportAtSend());
            assertThat(fetch.lamportAfterReply()).isGreaterThan(fetch.replyLamport());
            assertThat(nodes.clock(fetch.fromNodeId()).current()).isEqualTo(fetch.replyLamport());
        }
        long last = result.gather().get(2).lamportAfterReply();
        assertThat(nodes.clock(1).current()).isEqualTo(last);
    }

    @Test
    @DisplayName("the root's clock loses no update when several threads tick and merge at once (LamportClock is atomic)")
    void concurrentMergesLoseNothing() throws Exception {
        LamportClock clock = new LamportClock();
        long largest = 1_000_000;
        clock.update(largest);   // the largest merged value first, so every later operation adds exactly 1
        int threads = 5;
        int rounds = 2000;
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Void>> done = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            done.add(pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < rounds; i++) {
                    clock.tick();
                    clock.update(i);
                }
                return null;
            }));
        }
        start.countDown();
        CompletableFuture.allOf(done.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);

        long ticks = (long) threads * rounds;
        long updates = (long) threads * rounds;
        assertThat(clock.current()).isEqualTo(largest + 1 + ticks + updates);
        assertThat(clock.current()).isGreaterThanOrEqualTo(largest + ticks);
    }

    // ------------------------------------------------------------------ concurrency and timing

    @Test
    @DisplayName("the computes run concurrently: each (the root's too) waits at one latch for all p, which a sequential dispatcher never passes")
    void computesAreConcurrent() {
        int p = 4;
        CountDownLatch together = new CountDownLatch(p);
        List<Boolean> passed = new CopyOnWriteArrayList<>();
        InMemoryMatrixNodes.Hook meet = () -> {
            together.countDown();
            passed.add(together.await(10, TimeUnit.SECONDS));
        };
        InMemoryMatrixNodes nodes = nodes(1, 2, 3, 4).before(Step.COMPUTE, 2, meet).before(Step.COMPUTE, 3, meet)
                .before(Step.COMPUTE, 4, meet);
        nodes.executing(1, task -> {
            try {
                meet.run();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return InMemoryMatrixNodes.DIRECT.submit(task);
        });
        List<Integer> all = List.of(1, 2, 3, 4);

        MatrixRunResult result = run(executor(new SteppingClock()), nodes, 20, 1, all, Set.copyOf(all));

        assertThat(passed).hasSize(p).containsOnly(true);
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        assertThat(result.verification().passed()).isTrue();
    }

    @Test
    @DisplayName("order: generation and sequential (one step each) before the scatter; collectives, then the compute phase between two reads, then the fetches")
    void orderOfTimedParts() {
        LabelledClock clock = new LabelledClock();
        MatrixRunResult result = runThree(nodes(1, 2, 3), clock);
        List<String> labels = clock.labels;

        assertThat(result.payloadGenerationNanos()).isEqualTo(STEP);
        assertThat(result.timing().sequentialNanos()).isEqualTo(STEP);
        assertThat(labels.subList(0, 5)).containsOnly("run");   // generation 2, sequential 2, parallel start 1
        assertThat(labels.get(5)).isEqualTo("collective");
        int dispatch = labels.indexOf("deadlinePassed");
        assertThat(labels.lastIndexOf("collective")).isLessThan(dispatch);
        assertThat(labels.get(dispatch + 1)).isEqualTo("run");   // compute start
        Set<String> compute = Set.of("computeRemote", "computeOwn");
        int lastCompute = IntStream.range(0, labels.size()).filter(i -> compute.contains(labels.get(i))).max().orElseThrow();
        assertThat(labels.subList(dispatch + 2, lastCompute + 1)).allMatch(compute::contains);
        assertThat(labels.get(lastCompute + 1)).isEqualTo("run");   // compute end
        assertThat(labels.subList(lastCompute + 2, labels.size())).containsOnly("run", "fetch").contains("fetch");
        MatrixTiming timing = result.timing();
        assertThat(timing.parallelTotalNanos()).isGreaterThanOrEqualTo(
                timing.scatterNanos() + timing.broadcastNanos() + timing.computePhaseNanos() + timing.gatherNanos());
        assertThat(timing.rankComputes().get(1).computeNanos()).isEqualTo(InMemoryMatrixNodes.NODE_STEP);
    }

    @Test
    @DisplayName("warm-up: the count is reported, and the untimed products read no clock (same reads with 0 and 3)")
    void warmupIsUntimed() {
        SteppingClock none = new SteppingClock();
        SteppingClock three = new SteppingClock();
        MatrixRunResult zero = run(executor(new MatrixRunProperties(0, 2000, 4), BUDGET, none), nodes(1, 2, 3), 10, 1, THREE,
                Set.copyOf(THREE));
        MatrixRunResult warm = run(executor(new MatrixRunProperties(3, 2000, 4), BUDGET, three), nodes(1, 2, 3), 10, 1, THREE,
                Set.copyOf(THREE));

        assertThat(zero.timing().warmupProducts()).isZero();
        assertThat(warm.timing().warmupProducts()).isEqualTo(3);
        assertThat(three.reads()).isEqualTo(none.reads());
    }

    // ------------------------------------------------------------------ refusals

    @Test
    @DisplayName("refusals are data: size, live participants, root not live or not a participant (ROOT_UNAVAILABLE); nothing generated, sent or timed")
    void refusals() {
        List<Integer> four = List.of(1, 2, 3, 4);
        record Case(int n, int root, List<Integer> live, MatrixRefusal.Reason reason) {
        }
        for (Case c : List.of(
                new Case(317, 1, four, MatrixRefusal.Reason.SIZE_ABOVE_MAXIMUM),
                new Case(1, 1, four, MatrixRefusal.Reason.SIZE_BELOW_MINIMUM),
                new Case(10, 1, List.of(), MatrixRefusal.Reason.NO_LIVE_PARTICIPANT),
                new Case(3, 1, four, MatrixRefusal.Reason.SIZE_BELOW_PARTICIPANTS),
                new Case(10, 5, four, MatrixRefusal.Reason.ROOT_UNAVAILABLE),
                new Case(10, 4, List.of(1, 2, 3), MatrixRefusal.Reason.ROOT_UNAVAILABLE))) {
            SteppingClock clock = new SteppingClock();
            InMemoryMatrixNodes nodes = nodes(1, 2, 3, 4, 5);
            MatrixRunResult result = run(executor(clock), nodes, c.n(), c.root(), four, Set.copyOf(c.live()));

            assertThat(result.outcome()).as(c.toString()).isEqualTo(CollectiveOutcome.REFUSED);
            assertThat(result.refusal().reason()).as(c.toString()).isEqualTo(c.reason());
            assertThat(result.timing()).isNull();
            assertThat(result.verification()).isNull();
            assertThat(result.scatter()).isNull();
            assertThat(result.payloadGenerationNanos()).isNull();
            assertThat(result.computes()).isEmpty();
            assertThat(nodes.calls()).isEmpty();
            assertThat(clock.reads()).isZero();
        }
        MatrixRunResult rootDown = run(executor(new SteppingClock()), nodes(1, 2, 3, 4), 10, 4, four, Set.of(1, 2, 3));
        assertThat(rootDown.skippedNodeIds()).containsExactly(4);
        assertThat(rootDown.refusal().message()).isEqualTo("The root, node 4, is down.");
    }

    // ------------------------------------------------------------------ the failure matrix

    @Test
    @DisplayName("node 3 unreachable before the scatter: its input fails, its compute and fetch are NOT_ATTEMPTED; PARTIAL")
    void scatterFails() {
        InMemoryMatrixNodes nodes = nodes(1, 2, 3).failing(Step.DELIVER, 3, CallStage.LOOKUP, REFUSED);
        MatrixRunResult result = runThree(nodes, new SteppingClock());

        assertThat(result.scatter().transfers().get(2).status()).isEqualTo(TransferStatus.FAILED);
        ComputeRecord compute = result.computes().get(2);
        assertThat(compute.status()).isEqualTo(ComputeRecord.Status.NOT_ATTEMPTED);
        assertThat(compute.notAttemptedReason()).isEqualTo("Its input did not arrive verified: its rows of A failed, B failed.");
        assertThat(result.gather().get(2).status()).isEqualTo(TransferStatus.NOT_ATTEMPTED);
        assertThat(nodes.calls(Step.COMPUTE)).extracting(InMemoryMatrixNodes.Call::nodeId).containsExactly(2);
        assertThat(nodes.calls(Step.FETCH)).extracting(InMemoryMatrixNodes.Call::nodeId).containsExactly(2);
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
        assertIncomplete(result, 1);
        assertThat(result.inputsDelivered()).isNull();
        assertThat(result.timing().rankComputes().get(2).computeNanos()).isNull();
        assertThat(result.timing().speedup()).isNotNull();
    }

    @Test
    @DisplayName("crash between scatter and compute: the compute fails at LOOKUP (CONNECTION_REFUSED), nothing was sent; PARTIAL")
    void computeLookupFails() {
        MatrixRunResult result = runThree(nodes(1, 2, 3).failing(Step.COMPUTE, 3, CallStage.LOOKUP, REFUSED), new SteppingClock());

        ComputeRecord compute = result.computes().get(2);
        assertThat(compute.status()).isEqualTo(ComputeRecord.Status.FAILED);
        assertThat(compute.failure().stage()).isEqualTo(CallStage.LOOKUP);
        assertThat(compute.failure().kind()).isEqualTo(RemoteFailureKind.CONNECTION_REFUSED);
        assertThat(compute.senderLamportAtSend()).isNull();
        assertThat(compute.nodeReason()).isNull();
        assertThat(compute.timeToFailureNanos()).isNotNull();
        assertThat(result.gather().get(2).notAttemptedReason()).isEqualTo("There is no block to fetch: its compute ended FAILED.");
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
        assertIncomplete(result, 1);
    }

    @Test
    @DisplayName("crash during the compute: the call breaks (CONNECTION_LOST), it was sent; PARTIAL")
    void computeCallBreaks() {
        MatrixRunResult result = runThree(nodes(1, 2, 3).failing(Step.COMPUTE, 3, CallStage.CALL, LOST), new SteppingClock());

        ComputeRecord compute = result.computes().get(2);
        assertThat(compute.failure().kind()).isEqualTo(RemoteFailureKind.CONNECTION_LOST);
        assertThat(compute.senderLamportAtSend()).isNotNull();
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
    }

    @Test
    @DisplayName("crash after the compute, before the fetch: the compute time is kept (it ran), the block is a hole; PARTIAL")
    void fetchFails() {
        MatrixRunResult result = runThree(nodes(1, 2, 3).failing(Step.FETCH, 3, CallStage.LOOKUP, REFUSED), new SteppingClock());

        assertThat(result.computes().get(2).status()).isEqualTo(ComputeRecord.Status.DONE);
        assertThat(result.timing().rankComputes().get(2).computeNanos()).isEqualTo(InMemoryMatrixNodes.NODE_STEP);
        assertThat(result.timing().computeBoundSpeedup()).isNotNull();
        TransferRecord fetch = result.gather().get(2);
        assertThat(fetch.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(fetch.failure().kind()).isEqualTo(RemoteFailureKind.CONNECTION_REFUSED);
        assertThat(fetch.requestBytes()).isNull();
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
        assertIncomplete(result, 1);
    }

    @Test
    @DisplayName("stored A evicted before the compute: METHOD_THREW with the node's reason INPUT_MISSING; PARTIAL")
    void inputEvicted() {
        InMemoryMatrixNodes nodes = nodes(1, 2, 3);
        nodes.before(Step.COMPUTE, 3, () -> {
            for (long id = 1; id <= MPI.receiveStoreSize(); id++) {
                nodes.collective(3).receive(new CallContext(1, 0), id, 0, new double[] {1});
            }
        });
        MatrixRunResult result = runThree(nodes, new SteppingClock());

        ComputeRecord compute = result.computes().get(2);
        assertThat(compute.failure().stage()).isEqualTo(CallStage.CALL);
        assertThat(compute.failure().kind()).isEqualTo(RemoteFailureKind.METHOD_THREW);
        assertThat(compute.nodeReason()).isEqualTo(MatrixComputeException.Reason.INPUT_MISSING);
        assertThat(compute.nodeMessage()).contains("never arrived, or the store's limit dropped it");
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
    }

    @Test
    @DisplayName("kept block of C evicted before the fetch: METHOD_THREW with BLOCK_MISSING; PARTIAL")
    void keptBlockEvicted() {
        MatrixRunRequest request = new MatrixRunRequest(1, 10, SEED, 1, THREE, Set.copyOf(THREE));
        InMemoryMatrixNodes nodes = new InMemoryMatrixNodes(MPI, new MatrixRunProperties(1, 2000, 1), 1, 2, 3);
        nodes.before(Step.FETCH, 3, () -> nodes.matrix(3).computeBlock(new CallContext(1, 0), 999, 2, 10,
                request.scatterCollectiveId(), request.broadcastCollectiveId()));
        MatrixRunResult result = executor(new SteppingClock()).run(request, nodes.clock(1), nodes, nodes, InMemoryMatrixNodes.DIRECT);

        TransferRecord fetch = result.gather().get(2);
        assertThat(fetch.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(fetch.failure().kind()).isEqualTo(RemoteFailureKind.METHOD_THREW);
        assertThat(fetch.failure().failureClass()).isEqualTo(MatrixComputeException.class.getName());
        assertThat(fetch.failure().failureMessage()).contains("keeps no block of C for run 1 and rank 2");
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
    }

    @Test
    @DisplayName("a corrupted block (recomputed checksum differs from the acknowledgement): DELIVERED but not verified; COMPLETE, passed false")
    void corruptedBlock() {
        InMemoryMatrixNodes nodes = nodes(1, 2, 3).rewriting(Step.FETCH, 3, reply -> {
            BlockReply block = (BlockReply) reply;
            double[] values = block.values();
            values[0] = Math.nextUp(values[0]);
            return new BlockReply(block.nodeId(), block.lamportTime(), block.runId(), block.rank(), values);
        });
        MatrixRunResult result = runThree(nodes, new SteppingClock());

        assertThat(result.gather().get(2).status()).isEqualTo(TransferStatus.DELIVERED);
        assertThat(result.gather().get(2).verified()).isFalse();
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        assertThat(result.verification().blocksWithBadChecksum()).isEqualTo(1);
        assertThat(result.verification().mismatchedElements()).isEqualTo(1);
        assertThat(result.verification().checksumsMatch()).isFalse();
        assertThat(result.verification().passed()).isFalse();
    }

    @Test
    @DisplayName("a node's executor rejects (queue full): EXECUTOR_REJECTED; PARTIAL")
    void executorRejects() {
        InMemoryMatrixNodes nodes = nodes(1, 2, 3)
                .executing(3, task -> CompletableFuture.failedFuture(new RejectedExecutionException("full")));
        MatrixRunResult result = runThree(nodes, new SteppingClock());

        assertThat(result.computes().get(2).nodeReason()).isEqualTo(MatrixComputeException.Reason.EXECUTOR_REJECTED);
        assertThat(result.computes().get(2).failure().kind()).isEqualTo(RemoteFailureKind.METHOD_THREW);
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
    }

    @Test
    @DisplayName("the root's own executor is unavailable: a local FAILED compute with its reason, the root's rows a hole; PARTIAL")
    void rootExecutorUnavailable() {
        InMemoryMatrixNodes nodes = nodes(1, 2, 3).executing(1, task -> {
            throw new NodeDownException(1);
        });
        MatrixRunResult result = runThree(nodes, new SteppingClock());

        ComputeRecord own = result.computes().get(0);
        assertThat(own.local()).isTrue();
        assertThat(own.status()).isEqualTo(ComputeRecord.Status.FAILED);
        assertThat(own.failure()).isNull();
        assertThat(own.nodeReason()).isEqualTo(MatrixComputeException.Reason.EXECUTOR_UNAVAILABLE);
        assertThat(result.gather().get(0).status()).isEqualTo(TransferStatus.LOCAL);
        assertThat(result.gather().get(0).verified()).isNull();
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
        assertIncomplete(result, 1);
    }

    @Test
    @DisplayName("two timeouts stay apart: the node's own (METHOD_THREW + COMPUTE_TIMED_OUT) and the root's wait bound (TIMED_OUT, observed)")
    void twoTimeouts() throws Exception {
        InMemoryMatrixNodes slow = new InMemoryMatrixNodes(MPI, new MatrixRunProperties(1, 50, 4), 1, 2, 3)
                .executing(3, task -> new CompletableFuture<>());
        ComputeRecord nodeSide = runThree(slow, new SteppingClock()).computes().get(2);
        assertThat(nodeSide.failure().kind()).isEqualTo(RemoteFailureKind.METHOD_THREW);
        assertThat(nodeSide.nodeReason()).isEqualTo(MatrixComputeException.Reason.COMPUTE_TIMED_OUT);

        CountDownLatch release = new CountDownLatch(1);
        InMemoryMatrixNodes stuck = nodes(1, 2, 3).before(Step.COMPUTE, 3, () -> release.await(10, TimeUnit.SECONDS));
        MatrixRunBudget tight = new MatrixRunBudget(200, 200, 10_000, 2000, 1);   // the root waits 3 x (200 + 200) = 1200 ms
        try {
            MatrixRunResult result = run(executor(RUN, tight, new SteppingClock()), stuck, 10, 1, THREE, Set.copyOf(THREE));
            ComputeRecord rootSide = result.computes().get(2);
            assertThat(rootSide.failure().kind()).isEqualTo(RemoteFailureKind.TIMED_OUT);
            assertThat(rootSide.failure().failureClass()).isNull();
            assertThat(rootSide.failure().failureMessage()).contains("The root stopped waiting for this compute after 1200 ms");
            assertThat(rootSide.nodeReason()).isNull();
            assertThat(rootSide.timeToFailureNanos()).isEqualTo(result.timing().computePhaseNanos());
            assertThat(result.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("replies that do not match the request (rows of the ack, length of the block) are FAILED, kind OTHER")
    void mismatchedReplies() {
        InMemoryMatrixNodes nodes = nodes(1, 2, 3)
                .rewriting(Step.COMPUTE, 3, reply -> {
                    ComputeAck ack = (ComputeAck) reply;
                    return new ComputeAck(ack.nodeId(), ack.lamportTime(), ack.runId(), ack.rank(), ack.rowCount() + 1,
                            ack.computeNanos(), ack.checksum());
                })
                .rewriting(Step.FETCH, 2, reply -> {
                    BlockReply block = (BlockReply) reply;
                    double[] values = block.values();
                    return new BlockReply(block.nodeId(), block.lamportTime(), block.runId(), block.rank(),
                            java.util.Arrays.copyOf(values, values.length - 1));
                });
        MatrixRunResult result = runThree(nodes, new SteppingClock());

        ComputeRecord compute = result.computes().get(2);
        assertThat(compute.failure().kind()).isEqualTo(RemoteFailureKind.OTHER);
        assertThat(compute.failure().failureMessage()).startsWith("reply did not match the request: expected node 3, run 1, rank 2, 3 rows");
        assertThat(compute.replyLamport()).isNotNull();
        TransferRecord fetch = result.gather().get(1);
        assertThat(fetch.failure().kind()).isEqualTo(RemoteFailureKind.OTHER);
        assertThat(fetch.failure().failureMessage()).contains("30 values; got node 2, run 1, rank 1, 29 values");
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.FAILED);   // no remote block arrived
    }

    @Test
    @DisplayName("every remote compute fails: FAILED although the root's own block arrived")
    void everyRemoteFails() {
        InMemoryMatrixNodes nodes = nodes(1, 2, 3).failing(Step.COMPUTE, 2, CallStage.CALL, LOST)
                .failing(Step.COMPUTE, 3, CallStage.CALL, LOST);
        MatrixRunResult result = runThree(nodes, new SteppingClock());

        assertThat(result.computes().get(0).status()).isEqualTo(ComputeRecord.Status.DONE);
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.FAILED);
        assertIncomplete(result, 2);
    }

    // ------------------------------------------------------------------ the run deadline

    @Test
    @DisplayName("the deadline passes during the scatter: the broadcast is not started, nothing is computed or fetched; FAILED")
    void deadlineDuringScatter() {
        SteppingClock clock = new SteppingClock();
        InMemoryMatrixNodes nodes = nodes(1, 2, 3).before(Step.DELIVER, 2, () -> clock.jump(DEADLINE_NANOS + 1_000_000));
        MatrixRunResult result = runThree(nodes, clock);

        assertThat(result.scatter().transfers()).extracting(TransferRecord::status)
                .containsExactly(TransferStatus.LOCAL, TransferStatus.DELIVERED, TransferStatus.NOT_ATTEMPTED);
        assertThat(result.broadcast().transfers()).extracting(TransferRecord::status)
                .containsExactly(TransferStatus.LOCAL, TransferStatus.NOT_ATTEMPTED, TransferStatus.NOT_ATTEMPTED);
        assertThat(result.broadcast().transfers().get(1).notAttemptedReason())
                .isEqualTo("The run's deadline of 10000 ms had passed before this broadcast.");
        assertThat(result.broadcast().totalNanos()).isNull();
        assertThat(result.broadcast().outcome()).isEqualTo(CollectiveOutcome.FAILED);
        assertThat(result.broadcast().inputChecksum()).isEqualTo(VectorChecksum.of(MatrixGenerator.b(SEED, 10)));
        assertThat(result.computes()).allMatch(c -> c.status() == ComputeRecord.Status.NOT_ATTEMPTED);
        assertThat(result.computes().get(0).notAttemptedReason())
                .isEqualTo("The run's deadline of 10000 ms had passed before this compute.");
        assertThat(nodes.calls(Step.COMPUTE)).isEmpty();
        assertThat(nodes.calls(Step.FETCH)).isEmpty();
        assertThat(nodes.calls(Step.DELIVER)).hasSize(1);
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.FAILED);
        assertIncomplete(result, 3);
        assertThat(result.timing().broadcastNanos()).isNull();
        assertThat(result.timing().computePhaseNanos()).isNull();
        assertThat(result.timing().gatherNanos()).isNull();
        assertThat(result.timing().parallelTotalNanos()).isNotNull();
    }

    @Test
    @DisplayName("the deadline passes during the gather: the remaining fetch is NOT_ATTEMPTED; PARTIAL")
    void deadlineDuringGather() {
        SteppingClock clock = new SteppingClock();
        InMemoryMatrixNodes nodes = nodes(1, 2, 3).before(Step.FETCH, 2, () -> clock.jump(DEADLINE_NANOS + 1_000_000));
        MatrixRunResult result = runThree(nodes, clock);

        assertThat(result.gather().get(1).status()).isEqualTo(TransferStatus.DELIVERED);
        assertThat(result.gather().get(2).status()).isEqualTo(TransferStatus.NOT_ATTEMPTED);
        assertThat(result.gather().get(2).notAttemptedReason())
                .isEqualTo("The run's deadline of 10000 ms had passed before this fetch.");
        assertThat(nodes.calls(Step.FETCH)).hasSize(1);
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
    }

    @Test
    @DisplayName("the pool must have a thread per rank (a configuration error, not data)")
    void poolTooSmall() {
        try (MatrixDispatchPool small = new MatrixDispatchPool(2)) {
            ParallelMatrixExecutor executor = new ParallelMatrixExecutor(LIMITS, RUN, MPI, BUDGET, small, new SteppingClock());
            InMemoryMatrixNodes nodes = nodes(1, 2, 3);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.run(
                            new MatrixRunRequest(1, 10, SEED, 1, THREE, Set.copyOf(THREE)), nodes.clock(1), nodes, nodes,
                            InMemoryMatrixNodes.DIRECT))
                    .isInstanceOf(IllegalStateException.class).hasMessage("the dispatch pool has 2 threads for 3 ranks");
            assertThat(nodes.calls()).isEmpty();
        }
    }
}
