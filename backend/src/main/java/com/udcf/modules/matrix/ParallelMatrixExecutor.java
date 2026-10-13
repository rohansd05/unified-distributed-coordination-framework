package com.udcf.modules.matrix;

import com.udcf.core.clock.LamportClock;
import com.udcf.modules.mpi.BlockPartition;
import com.udcf.modules.mpi.CollectiveExecutor;
import com.udcf.modules.mpi.CollectiveOperation;
import com.udcf.modules.mpi.CollectiveOutcome;
import com.udcf.modules.mpi.CollectiveRequest;
import com.udcf.modules.mpi.CollectiveResult;
import com.udcf.modules.mpi.CollectiveTransport;
import com.udcf.modules.mpi.MpiProperties;
import com.udcf.modules.mpi.RankAssignment;
import com.udcf.modules.mpi.TransferDirection;
import com.udcf.modules.mpi.TransferException;
import com.udcf.modules.mpi.TransferRecord;
import com.udcf.modules.mpi.TransferStatus;
import com.udcf.modules.mpi.VectorChecksum;
import com.udcf.modules.mpi.Verification;
import com.udcf.modules.rmi.CallContext;
import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.CallTimer;
import com.udcf.modules.rmi.PayloadSizer;
import com.udcf.modules.rmi.RemoteFailureDetail;
import com.udcf.modules.rmi.RemoteFailureKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Runs one parallel matrix multiplication from the root (step E10b, link L6): the root scatters row blocks
 * of A and broadcasts B with Experiment 9's collectives, every rank multiplies its rows on its own node's
 * executor at the same time, the root fetches the blocks of C one by one, assembles C and compares it bit
 * for bit with the sequential product. No sockets of its own: the transports are injected, and time comes
 * from an injected nanosecond clock (thread safe: the compute calls read it on several threads).
 *
 * <p><b>Order of one run.</b></p>
 * <ol>
 *   <li>Refusals (data, nothing generated or sent, the clock not read): {@link MatrixLimits#check}, then
 *       the root must be a live participant (ROOT_UNAVAILABLE).</li>
 *   <li>A and B are generated (timed: {@code payloadGenerationNanos}, informational).</li>
 *   <li>{@code warmup-products} untimed full products, then the timed sequential product, on the calling
 *       thread.</li>
 *   <li>The parallel run, timed from just before the scatter: scatter of A (count n x n, block unit n, so
 *       every block is whole rows), broadcast of B, the concurrent compute phase, the linear gather.</li>
 * </ol>
 *
 * <p><b>One run deadline</b> ({@code udcf.mpi.collective-deadline-millis}, from just before the scatter).
 * Each collective gets the time that remains, rounded up to whole milliseconds, as its Experiment 9
 * deadline. If none remains before a collective, the compute dispatch or a fetch, that step is not started:
 * it and every later step are NOT_ATTEMPTED with a reason (a collective that is not started is still
 * reported as a {@link CollectiveResult}: the root's part LOCAL, every other rank NOT_ATTEMPTED, no time).</p>
 *
 * <p><b>Failures are data.</b> A failed transfer or compute is a hole with its stage, kind and, when the node
 * said why, its reason. A rank whose rows of A or whose B did not arrive verified is not asked to compute; a
 * rank that did not compute is not fetched. The root waits at most {@link MatrixRunBudget#computeWaitMillis()}
 * for the compute phase; a compute still running then is FAILED with an observed TIMED_OUT.</p>
 *
 * <p><b>Lamport (link L4).</b> The root ticks before every compute and fetch and applies the receive rule to a
 * matching reply; a failed call merges nothing. The root's clock is atomic, so the concurrent compute calls
 * may tick and merge at once.</p>
 */
public final class ParallelMatrixExecutor {

    private final MatrixLimits limits;
    private final MatrixRunProperties properties;
    private final MpiProperties mpi;
    private final MatrixRunBudget budget;
    private final MatrixDispatchPool pool;
    private final LongSupplier nanoClock;
    private final CallTimer timer;

    public ParallelMatrixExecutor(MatrixLimits limits, MatrixRunProperties properties, MpiProperties mpi, MatrixRunBudget budget,
                                  MatrixDispatchPool pool, LongSupplier nanoClock) {
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.mpi = Objects.requireNonNull(mpi, "mpi must not be null");
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
        this.pool = Objects.requireNonNull(pool, "pool must not be null");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        this.timer = new CallTimer(nanoClock);
    }

    /**
     * Runs {@code request} from its root.
     *
     * @param rootClock    the root node's shared Lamport clock
     * @param collectives  how the root reaches the nodes' CollectiveEndpoints (scatter and broadcast)
     * @param matrices     how the root reaches the nodes' MatrixEndpoints (compute and fetch)
     * @param rootExecutor the root node's own executor, for its own block
     * @return what happened; a refusal is a result too (outcome REFUSED)
     * @throws IllegalStateException if the pool has fewer threads than the run has ranks (a configuration error)
     */
    public MatrixRunResult run(MatrixRunRequest request, LamportClock rootClock, CollectiveTransport collectives,
                               MatrixTransport matrices, NodeExecutor rootExecutor) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(rootClock, "rootClock must not be null");
        Objects.requireNonNull(collectives, "collectives must not be null");
        Objects.requireNonNull(matrices, "matrices must not be null");
        Objects.requireNonNull(rootExecutor, "rootExecutor must not be null");

        RankAssignment ranks = RankAssignment.assign(request.participantNodeIds(), request.liveNodeIds(), request.rootNodeId());
        int liveParticipants = (int) request.participantNodeIds().stream().filter(request.liveNodeIds()::contains).count();
        Optional<MatrixRefusal> refusal = limits.check(request.n(), liveParticipants);
        if (refusal.isEmpty() && ranks.refusal().isPresent()) {
            refusal = Optional.of(new MatrixRefusal(MatrixRefusal.Reason.ROOT_UNAVAILABLE, ranks.refusal().get().message()));
        }
        if (refusal.isPresent()) {
            return new MatrixRunResult(request.runId(), request.n(), request.seed(), request.rootNodeId(), null, List.of(),
                    ranks.skipped(), List.of(), CollectiveOutcome.REFUSED, refusal.get(), mpi.collectiveDeadlineMillis(), null,
                    null, null, List.of(), List.of(), null, null, null);
        }
        if (ranks.size() > pool.threads()) {
            throw new IllegalStateException("the dispatch pool has " + pool.threads() + " threads for " + ranks.size() + " ranks");
        }
        return new Run(request, ranks, rootClock, collectives, matrices, rootExecutor).run();
    }

    /** One run in progress. */
    private final class Run {

        private final MatrixRunRequest request;
        private final RankAssignment ranks;
        private final LamportClock rootClock;
        private final CollectiveTransport collectives;
        private final MatrixTransport matrices;
        private final NodeExecutor rootExecutor;
        private final int n;
        private final int p;
        private final int root;
        private final int rootRank;
        private final List<Integer> nodes;
        private final List<RowPartition.RowBlock> rows;
        private final long deadlineNanos;
        private long parallelStart;

        Run(MatrixRunRequest request, RankAssignment ranks, LamportClock rootClock, CollectiveTransport collectives,
            MatrixTransport matrices, NodeExecutor rootExecutor) {
            this.request = request;
            this.ranks = ranks;
            this.rootClock = rootClock;
            this.collectives = collectives;
            this.matrices = matrices;
            this.rootExecutor = rootExecutor;
            this.n = request.n();
            this.p = ranks.size();
            this.root = request.rootNodeId();
            this.rootRank = ranks.rootRank();
            this.nodes = ranks.nodes();
            this.rows = RowPartition.of(n, p);
            this.deadlineNanos = mpi.collectiveDeadlineMillis() * 1_000_000L;
        }

        /** What a compute task hands back: its record, and the root's own rows of C (null for a remote rank). */
        private record Computed(ComputeRecord record, double[] ownValues) {
        }

        MatrixRunResult run() {
            long generationStart = nanoClock.getAsLong();
            double[] a = MatrixGenerator.a(request.seed(), n);
            double[] b = MatrixGenerator.b(request.seed(), n);
            long payloadGenerationNanos = elapsed(generationStart, nanoClock.getAsLong());

            for (int i = 0; i < properties.warmupProducts(); i++) {
                MatrixMultiply.multiply(a, b, n);   // untimed: lets the JIT compile the multiply first
            }
            MatrixMultiply.Timed sequential = MatrixMultiply.timedRows(nanoClock, a, b, n);

            parallelStart = nanoClock.getAsLong();
            CollectiveResult scatter = collective(CollectiveOperation.SCATTER, request.scatterCollectiveId(),
                    MatrixGenerator.seedA(request.seed()), n, a);
            CollectiveResult broadcast = collective(CollectiveOperation.BROADCAST, request.broadcastCollectiveId(),
                    MatrixGenerator.seedB(request.seed()), 1, b);

            // ------------------------------------------------------------ compute phase, concurrent
            Computed[] computed = new Computed[p];
            List<Integer> dispatch = new ArrayList<>();
            boolean late = deadlinePassed();
            for (int rank = 0; rank < p; rank++) {
                String blocked = late ? deadlineReason("compute") : missingInput(scatter, broadcast, rank);
                if (blocked == null) {
                    dispatch.add(rank);
                } else {
                    computed[rank] = new Computed(notAttemptedCompute(rank, blocked), null);
                }
            }
            Long computePhaseNanos = null;
            Long computeEnd = null;
            if (!dispatch.isEmpty()) {
                long computeStart = nanoClock.getAsLong();
                Map<Integer, CompletableFuture<Computed>> futures = new LinkedHashMap<>();
                for (int rank : dispatch) {
                    futures.put(rank, pool.submit(rank == rootRank ? () -> computeOwn(rank, a, b) : () -> computeRemote(rank)));
                }
                awaitAll(futures.values());
                computeEnd = nanoClock.getAsLong();
                computePhaseNanos = elapsed(computeStart, computeEnd);
                for (Map.Entry<Integer, CompletableFuture<Computed>> entry : futures.entrySet()) {
                    CompletableFuture<Computed> future = entry.getValue();
                    computed[entry.getKey()] = future.isDone() && !future.isCompletedExceptionally() ? future.join()
                            : new Computed(abandonedCompute(entry.getKey(), computePhaseNanos), null);
                }
            }

            // ------------------------------------------------------------ gather, linear
            List<TransferRecord> gather = new ArrayList<>();
            List<ResultBlock> blocks = new ArrayList<>();
            Long gatherStart = null;
            Long gatherEnd = null;
            for (int rank = 0; rank < p; rank++) {
                RowPartition.RowBlock block = rows.get(rank);
                int node = nodes.get(rank);
                String expected = VectorChecksum.of(RowPartition.rows(sequential.values(), block));
                ComputeRecord compute = computed[rank].record();
                if (rank == rootRank) {
                    double[] own = computed[rank].ownValues();
                    gather.add(new TransferRecord(rank, root, root, TransferDirection.FROM_RANK, TransferStatus.LOCAL,
                            block.elementOffset(), block.elementLength(), null, null, null, null, expected, null,
                            own == null ? null : VectorChecksum.of(own).equals(expected), null, null, null, null, null));
                    blocks.add(own == null ? ResultBlock.hole(block, node) : new ResultBlock(block, node, own, VectorChecksum.of(own)));
                    continue;
                }
                if (compute.status() != ComputeRecord.Status.DONE) {
                    gather.add(notAttemptedFetch(rank, block, expected,
                            "There is no block to fetch: its compute ended " + compute.status() + "."));
                    blocks.add(ResultBlock.hole(block, node));
                    continue;
                }
                long now = nanoClock.getAsLong();
                if (now - parallelStart >= deadlineNanos) {
                    gather.add(notAttemptedFetch(rank, block, expected, deadlineReason("fetch")));
                    blocks.add(ResultBlock.hole(block, node));
                    continue;
                }
                if (gatherStart == null) {
                    gatherStart = now;
                }
                Fetched fetched = fetch(rank, block, expected, compute.checksum());
                gatherEnd = nanoClock.getAsLong();
                gather.add(fetched.record());
                blocks.add(fetched.values() == null ? ResultBlock.hole(block, node)
                        : new ResultBlock(block, node, fetched.values(), compute.checksum()));
            }
            Long gatherNanos = gatherStart == null ? null : elapsed(gatherStart, gatherEnd);
            long parallelEnd = gatherEnd != null ? gatherEnd : computeEnd != null ? computeEnd : nanoClock.getAsLong();

            MatrixAssembly.Assembled assembled = MatrixAssembly.assemble(n, blocks, rootRank, sequential.values());
            List<MatrixTiming.RankCompute> rankComputes = new ArrayList<>();
            List<ComputeRecord> computes = new ArrayList<>();
            for (int rank = 0; rank < p; rank++) {
                ComputeRecord record = computed[rank].record();
                computes.add(record);
                rankComputes.add(new MatrixTiming.RankCompute(rank, record.nodeId(), record.rowCount(), record.computeNanos()));
            }
            MatrixTiming timing = new MatrixTiming(n, properties.warmupProducts(), sequential.nanos(), scatter.totalNanos(),
                    broadcast.totalNanos(), computePhaseNanos, gatherNanos, elapsed(parallelStart, parallelEnd), rankComputes);
            return new MatrixRunResult(request.runId(), n, request.seed(), root, rootRank, nodes, ranks.skipped(), rows,
                    assembled.outcome(), null, mpi.collectiveDeadlineMillis(), payloadGenerationNanos, scatter, broadcast,
                    computes, gather, timing, assembled.verification(),
                    inputsDelivered(scatter.verification().passed(), broadcast.verification().passed()));
        }

        // ---------------------------------------------------------------- collectives

        /** Experiment 9's collective with the time left of the run deadline, or NOT_ATTEMPTED when none is left. */
        private CollectiveResult collective(CollectiveOperation operation, long id, long seed, int unit, double[] payload) {
            long remaining = deadlineNanos - (nanoClock.getAsLong() - parallelStart);
            int count = Math.multiplyExact(n, n);
            if (remaining <= 0) {
                return notAttemptedCollective(operation, id, seed, unit, payload);
            }
            int deadlineMillis = (int) Math.min(Integer.MAX_VALUE, (remaining + 999_999) / 1_000_000);   // rounded up: >= 1
            MpiProperties phase = new MpiProperties(mpi.maxPayloadElements(), deadlineMillis, mpi.historySize(),
                    mpi.receiveStoreSize(), mpi.maxFailureMessageLength());
            CollectiveRequest collective = new CollectiveRequest(id, operation, root, request.participantNodeIds(),
                    request.liveNodeIds(), count, seed, unit);
            return new CollectiveExecutor(phase, nanoClock).execute(collective, rootClock, collectives);
        }

        /** A collective that was not started: the root's part LOCAL, every other rank NOT_ATTEMPTED, no time. */
        private CollectiveResult notAttemptedCollective(CollectiveOperation operation, long id, long seed, int unit,
                                                        double[] payload) {
            int count = payload.length;
            boolean scatter = operation == CollectiveOperation.SCATTER;
            List<BlockPartition.Block> parts = scatter ? BlockPartition.of(count, p, unit) : null;
            List<TransferRecord> transfers = new ArrayList<>();
            for (int rank = 0; rank < p; rank++) {
                int offset = scatter ? parts.get(rank).offset() : 0;
                int length = scatter ? parts.get(rank).length() : count;
                String expected = VectorChecksum.of(Arrays.copyOfRange(payload, offset, offset + length));
                transfers.add(rank == rootRank
                        ? new TransferRecord(rank, root, root, TransferDirection.TO_RANK, TransferStatus.LOCAL, offset, length,
                        null, null, null, null, expected, null, null, null, null, null, null, null)
                        : new TransferRecord(rank, root, nodes.get(rank), TransferDirection.TO_RANK, TransferStatus.NOT_ATTEMPTED,
                        offset, length, null, null, null, null, expected, null, null, null, null, null, null,
                        deadlineReason(operation.name().toLowerCase())));
            }
            int remote = p - 1;
            CollectiveOutcome outcome = remote == 0 ? CollectiveOutcome.COMPLETE : CollectiveOutcome.FAILED;
            return new CollectiveResult(id, operation, root, rootRank, nodes, ranks.skipped(), count, seed, outcome, null,
                    VectorChecksum.of(payload), transfers, null, List.of(), Verification.of(0, 0, remote, null),
                    mpi.collectiveDeadlineMillis());
        }

        /** Why rank {@code rank} cannot compute (its input did not arrive verified); null when it can. */
        private String missingInput(CollectiveResult scatter, CollectiveResult broadcast, int rank) {
            if (rank == rootRank) {
                return null;
            }
            TransferRecord rowsOfA = scatter.transfers().get(rank);
            TransferRecord wholeB = broadcast.transfers().get(rank);
            if (arrived(rowsOfA) && arrived(wholeB)) {
                return null;
            }
            return "Its input did not arrive verified: its rows of A " + describe(rowsOfA) + ", B " + describe(wholeB) + ".";
        }

        private static boolean arrived(TransferRecord transfer) {
            return transfer.status() == TransferStatus.DELIVERED && Boolean.TRUE.equals(transfer.verified());
        }

        private static String describe(TransferRecord transfer) {
            if (transfer.status() == TransferStatus.DELIVERED) {
                return Boolean.TRUE.equals(transfer.verified()) ? "delivered" : "delivered but not verified";
            }
            return transfer.status() == TransferStatus.FAILED ? "failed" : "not attempted";
        }

        // ---------------------------------------------------------------- compute

        private Computed computeRemote(int rank) {
            int node = nodes.get(rank);
            RowPartition.RowBlock block = rows.get(rank);
            CallContext context = new CallContext(root, rootClock.tick());
            CallTimer.Measurement<ComputeAck> made = timer.measure(() -> matrices.compute(node, context, request.runId(), rank, n,
                    request.scatterCollectiveId(), request.broadcastCollectiveId()));
            if (made.failed()) {
                Failure failure = failure(made.failure());
                MatrixComputeException said = failure.original() instanceof MatrixComputeException m ? m : null;
                return new Computed(new ComputeRecord(rank, node, block.rowStart(), block.rowCount(), false,
                        ComputeRecord.Status.FAILED, null, made.elapsedNanos(), null, null, failure.detail(),
                        said == null ? null : said.reason(), said == null ? null : said.getMessage(),
                        failure.detail().stage() == CallStage.CALL ? context.senderLamport() : null, null, null, null), null);
            }
            ComputeAck ack = made.value();
            String mismatch = ack == null ? "no reply" : mismatch(ack.nodeId(), ack.runId(), ack.rank(), node, rank,
                    ack.rowCount(), block.rowCount(), "rows");
            if (mismatch != null) {
                return new Computed(new ComputeRecord(rank, node, block.rowStart(), block.rowCount(), false,
                        ComputeRecord.Status.FAILED, null, made.elapsedNanos(), null, null, unmatched(mismatch), null, null,
                        context.senderLamport(), ack == null ? null : ack.lamportTime(), null, null), null);
            }
            long after = rootClock.update(ack.lamportTime());
            return new Computed(new ComputeRecord(rank, node, block.rowStart(), block.rowCount(), false, ComputeRecord.Status.DONE,
                    made.elapsedNanos(), null, ack.computeNanos(), ack.checksum(), null, null, null, context.senderLamport(),
                    ack.lamportTime(), after, null), null);
        }

        /** The root's own block, on the root's own executor: no network, no Lamport message. */
        private Computed computeOwn(int rank, double[] a, double[] b) {
            RowPartition.RowBlock block = rows.get(rank);
            double[] aRows = RowPartition.rows(a, block);
            long start = nanoClock.getAsLong();
            try {
                MatrixMultiply.Timed timed = rootExecutor.multiply(aRows, b, n, properties.computeTimeoutMillis(), nanoClock);
                return new Computed(new ComputeRecord(rank, root, block.rowStart(), block.rowCount(), true, ComputeRecord.Status.DONE,
                        null, null, timed.nanos(), VectorChecksum.of(timed.values()), null, null, null, null, null, null, null),
                        timed.values());
            } catch (MatrixComputeException e) {
                return new Computed(new ComputeRecord(rank, root, block.rowStart(), block.rowCount(), true,
                        ComputeRecord.Status.FAILED, null, elapsed(start, nanoClock.getAsLong()), null, null, null, e.reason(),
                        e.getMessage(), null, null, null, null), null);
            }
        }

        /** Waits for every compute, at most the budget's compute wait; the caller reads which ones finished. */
        private void awaitAll(java.util.Collection<CompletableFuture<Computed>> futures) {
            try {
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(budget.computeWaitMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // The ones still running are recorded as abandoned by the caller.
            } catch (ExecutionException e) {
                throw new IllegalStateException("a compute task failed unexpectedly", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the compute phase", e);
            }
        }

        /** A compute still running when the root stopped waiting: observed TIMED_OUT (remote) or the root's own reason. */
        private ComputeRecord abandonedCompute(int rank, long waitedNanos) {
            RowPartition.RowBlock block = rows.get(rank);
            String message = "The root stopped waiting for this compute after " + budget.computeWaitMillis()
                    + " ms (the run's bound for one compute call).";
            if (rank == rootRank) {
                return new ComputeRecord(rank, root, block.rowStart(), block.rowCount(), true, ComputeRecord.Status.FAILED, null,
                        waitedNanos, null, null, null, MatrixComputeException.Reason.COMPUTE_TIMED_OUT, message, null, null, null, null);
            }
            return new ComputeRecord(rank, nodes.get(rank), block.rowStart(), block.rowCount(), false, ComputeRecord.Status.FAILED,
                    null, waitedNanos, null, null,
                    RemoteFailureDetail.observed(CallStage.CALL, RemoteFailureKind.TIMED_OUT, message, mpi.maxFailureMessageLength()),
                    null, null, null, null, null, null);
        }

        private ComputeRecord notAttemptedCompute(int rank, String reason) {
            RowPartition.RowBlock block = rows.get(rank);
            return new ComputeRecord(rank, nodes.get(rank), block.rowStart(), block.rowCount(), rank == rootRank,
                    ComputeRecord.Status.NOT_ATTEMPTED, null, null, null, null, null, null, null, null, null, null, reason);
        }

        // ---------------------------------------------------------------- gather

        private record Fetched(TransferRecord record, double[] values) {
        }

        private Fetched fetch(int rank, RowPartition.RowBlock block, String expected, String acknowledged) {
            int node = nodes.get(rank);
            CallContext context = new CallContext(root, rootClock.tick());
            long requestBytes = PayloadSizer.sizeOf(context, request.runId(), rank);
            CallTimer.Measurement<BlockReply> made = timer.measure(() -> matrices.fetch(node, context, request.runId(), rank));
            int offset = block.elementOffset();
            int length = block.elementLength();
            if (made.failed()) {
                Failure failure = failure(made.failure());
                boolean sent = failure.detail().stage() == CallStage.CALL;
                return new Fetched(new TransferRecord(rank, node, root, TransferDirection.FROM_RANK, TransferStatus.FAILED, offset,
                        length, null, made.elapsedNanos(), sent ? requestBytes : null, null, expected, acknowledged, null,
                        failure.detail(), sent ? context.senderLamport() : null, null, null, null), null);
            }
            BlockReply reply = made.value();
            String mismatch = reply == null ? "no reply" : mismatch(reply.nodeId(), reply.runId(), reply.rank(), node, rank,
                    reply.length(), length, "values");
            if (mismatch != null) {
                return new Fetched(new TransferRecord(rank, node, root, TransferDirection.FROM_RANK, TransferStatus.FAILED, offset,
                        length, null, made.elapsedNanos(), requestBytes, reply == null ? null : PayloadSizer.sizeOf(reply), expected,
                        acknowledged, null, unmatched(mismatch), context.senderLamport(), reply == null ? null : reply.lamportTime(),
                        null, null), null);
            }
            long after = rootClock.update(reply.lamportTime());
            double[] values = reply.values();
            String received = VectorChecksum.of(values);   // recomputed by the root over what arrived
            boolean verified = received.equals(acknowledged) && received.equals(expected);
            return new Fetched(new TransferRecord(rank, node, root, TransferDirection.FROM_RANK, TransferStatus.DELIVERED, offset,
                    length, made.elapsedNanos(), null, requestBytes, PayloadSizer.sizeOf(reply), expected, acknowledged, verified,
                    null, context.senderLamport(), reply.lamportTime(), after, null), values);
        }

        private TransferRecord notAttemptedFetch(int rank, RowPartition.RowBlock block, String expected, String reason) {
            return new TransferRecord(rank, nodes.get(rank), root, TransferDirection.FROM_RANK, TransferStatus.NOT_ATTEMPTED,
                    block.elementOffset(), block.elementLength(), null, null, null, null, expected, null, null, null, null, null,
                    null, reason);
        }

        // ---------------------------------------------------------------- shared

        private record Failure(RemoteFailureDetail detail, Exception original) {
        }

        /** The stage and classification of a failed call, as in Experiment 9. */
        private Failure failure(Exception thrown) {
            CallStage stage = CallStage.CALL;
            Exception original = thrown;
            if (thrown instanceof TransferException transferFailure) {
                stage = transferFailure.stage();
                original = transferFailure.original();
            }
            return new Failure(RemoteFailureDetail.of(original, stage, mpi.maxFailureMessageLength()), original);
        }

        private RemoteFailureDetail unmatched(String why) {
            return RemoteFailureDetail.observed(CallStage.CALL, RemoteFailureKind.OTHER, "reply did not match the request: " + why,
                    mpi.maxFailureMessageLength());
        }

        /** Why a reply does not belong to the request it answers; null when it does. */
        private String mismatch(int gotNode, long gotRun, int gotRank, int wantNode, int wantRank, int gotSize, int wantSize,
                                String unit) {
            if (gotNode == wantNode && gotRun == request.runId() && gotRank == wantRank && gotSize == wantSize) {
                return null;
            }
            return "expected node " + wantNode + ", run " + request.runId() + ", rank " + wantRank + ", " + wantSize + " " + unit
                    + "; got node " + gotNode + ", run " + gotRun + ", rank " + gotRank + ", " + gotSize + " " + unit;
        }

        private boolean deadlinePassed() {
            return nanoClock.getAsLong() - parallelStart >= deadlineNanos;
        }

        private String deadlineReason(String step) {
            return "The run's deadline of " + mpi.collectiveDeadlineMillis() + " ms had passed before this " + step + ".";
        }
    }

    /** False if either check failed; null if either is unknown; true only if both passed. */
    private static Boolean inputsDelivered(Boolean scatterPassed, Boolean broadcastPassed) {
        if (Boolean.FALSE.equals(scatterPassed) || Boolean.FALSE.equals(broadcastPassed)) {
            return false;
        }
        if (scatterPassed == null || broadcastPassed == null) {
            return null;
        }
        return true;
    }

    private static long elapsed(long start, long end) {
        long nanos = end - start;
        if (nanos < 0) {
            throw new IllegalStateException("time source went backwards by " + (-nanos) + " ns");
        }
        return nanos;
    }
}
