package com.udcf.modules.mpi;

import com.udcf.core.clock.LamportClock;
import com.udcf.modules.rmi.CallContext;
import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.CallTimer;
import com.udcf.modules.rmi.PayloadSizer;
import com.udcf.modules.rmi.RemoteFailureDetail;
import com.udcf.modules.rmi.RemoteFailureKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Runs one collective from the root, over a {@link CollectiveTransport}. Pure and deterministic:
 * single-threaded, no sockets, time from an injected nanosecond clock.
 *
 * <p><b>Algorithm.</b> Linear: the root handles the ranks one after another in rank order. Real MPI
 * libraries use trees (binomial broadcast, recursive halving) so the root does not send p - 1
 * messages itself; a tree is not part of this step.</p>
 * <ul>
 *   <li>BROADCAST: the root sends the whole vector to every other rank.</li>
 *   <li>SCATTER: the root sends block i ({@link BlockPartition}) to rank i.</li>
 *   <li>GATHER: rank i contributes exactly block i's length, computed on its own node
 *       ({@link PayloadGenerator#contribution}); the root assembles the blocks in rank order, so the
 *       assembled vector has n elements. A block that did not arrive is a hole, never zeros.</li>
 * </ul>
 * <p>The root's own part never crosses the network: it is LOCAL, with no latency and no size.</p>
 *
 * <p><b>Failures are data.</b> A failed transfer is recorded with its stage and kind (classified as
 * in Experiment 1) and the collective goes on with the next rank. A reply that names another node,
 * collective or rank (or has the wrong length) is a FAILED transfer too (stage CALL, kind OTHER),
 * never delivered or verified. Before each transfer the clock is read: once
 * {@code collective-deadline-millis} have passed since the start, no transfer starts and the rest are
 * NOT_ATTEMPTED. A transfer already running cannot be interrupted; the transport's own timeouts
 * bound it. So a crashed node is reported, never waited on for ever.</p>
 *
 * <p><b>Lamport (link L4), as in Experiment 1.</b> Before each transfer the root ticks and sends its
 * time; on a matching reply it applies the receive rule. A failed transfer merges nothing.</p>
 *
 * <p><b>Measured, never estimated.</b> Each DELIVERED transfer's latency, and each FAILED transfer's
 * time to failure, is measured by the same timer around the transport call; the sizes are measured outside that window (PayloadSizer, the arguments exactly as
 * the endpoint receives them, and the reply). The total is the clock just after the last remote
 * transfer minus the clock just before the first one: measured, not a sum.</p>
 */
public final class CollectiveExecutor {

    private final MpiProperties properties;
    private final LongSupplier nanoClock;
    private final CallTimer timer;

    public CollectiveExecutor(MpiProperties properties, LongSupplier nanoClock) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        this.timer = new CallTimer(nanoClock);
    }

    /**
     * Runs {@code request} from its root.
     *
     * @param rootClock the root node's shared Lamport clock
     * @return what happened; a refusal is a result too (outcome REFUSED), with nothing sent
     */
    public CollectiveResult execute(CollectiveRequest request, LamportClock rootClock, CollectiveTransport transport) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(rootClock, "rootClock must not be null");
        Objects.requireNonNull(transport, "transport must not be null");

        RankAssignment ranks = RankAssignment.assign(request.participantNodeIds(), request.liveNodeIds(), request.rootNodeId());
        if (ranks.refusal().isPresent()) {
            return refused(request, ranks, ranks.refusal().get());
        }
        int p = ranks.size();
        int n = request.count();
        if (n > properties.maxPayloadElements()) {
            return refused(request, ranks, new Refusal(RefusalReason.PAYLOAD_TOO_LARGE,
                    "count " + n + " is above max-payload-elements " + properties.maxPayloadElements() + "."));
        }
        if (request.operation() != CollectiveOperation.BROADCAST && n < p) {
            return refused(request, ranks, new Refusal(RefusalReason.COUNT_BELOW_PARTICIPANTS,
                    "A " + request.operation().name().toLowerCase() + " needs at least one element per live participant: count "
                            + n + ", " + p + " live participants."));
        }
        Run run = new Run(request, ranks, rootClock, transport);
        return switch (request.operation()) {
            case BROADCAST -> run.broadcast();
            case SCATTER -> run.scatter();
            case GATHER -> run.gather();
        };
    }

    private CollectiveResult refused(CollectiveRequest request, RankAssignment ranks, Refusal refusal) {
        return new CollectiveResult(request.collectiveId(), request.operation(), request.rootNodeId(), null, ranks.nodes(),
                ranks.skipped(), request.count(), request.seed(), CollectiveOutcome.REFUSED, refusal, null, List.of(), null,
                List.of(), null, properties.collectiveDeadlineMillis());
    }

    /** One collective in progress. */
    private final class Run {

        private final CollectiveRequest request;
        private final RankAssignment ranks;
        private final LamportClock rootClock;
        private final CollectiveTransport transport;
        private final int root;
        private final int rootRank;
        private final long startNanos;
        private final long deadlineNanos;
        private Long firstTransferNanos;
        private Long lastTransferEndNanos;

        Run(CollectiveRequest request, RankAssignment ranks, LamportClock rootClock, CollectiveTransport transport) {
            this.request = request;
            this.ranks = ranks;
            this.rootClock = rootClock;
            this.transport = transport;
            this.root = request.rootNodeId();
            this.rootRank = ranks.rootRank();
            this.deadlineNanos = properties.collectiveDeadlineMillis() * 1_000_000L;
            this.startNanos = nanoClock.getAsLong();
        }

        CollectiveResult broadcast() {
            int n = request.count();
            double[] vector = PayloadGenerator.vector(request.seed(), n);
            String checksum = VectorChecksum.of(vector);
            List<TransferRecord> transfers = new ArrayList<>();
            for (int rank = 0; rank < ranks.size(); rank++) {
                transfers.add(rank == rootRank ? local(rank, TransferDirection.TO_RANK, 0, n, checksum)
                        : deliver(rank, 0, vector, checksum));
            }
            return finish(transfers, checksum, List.of(), null);
        }

        CollectiveResult scatter() {
            int n = request.count();
            double[] vector = PayloadGenerator.vector(request.seed(), n);
            List<BlockPartition.Block> blocks = BlockPartition.of(n, ranks.size());
            List<TransferRecord> transfers = new ArrayList<>();
            for (BlockPartition.Block block : blocks) {
                double[] chunk = BlockPartition.slice(vector, block);
                String expected = VectorChecksum.of(chunk);
                transfers.add(block.rank() == rootRank
                        ? local(block.rank(), TransferDirection.TO_RANK, block.offset(), block.length(), expected)
                        : deliver(block.rank(), block.offset(), chunk, expected));
            }
            return finish(transfers, VectorChecksum.of(vector), List.of(), scatterTiles(transfers, blocks, n));
        }

        CollectiveResult gather() {
            int n = request.count();
            List<BlockPartition.Block> blocks = BlockPartition.of(n, ranks.size());
            double[] expectedAll = new double[n];
            List<TransferRecord> transfers = new ArrayList<>();
            List<GatheredBlock> gathered = new ArrayList<>();
            for (BlockPartition.Block block : blocks) {
                int node = ranks.nodes().get(block.rank());
                // Rank i contributes exactly block i's length, so the assembled vector has n elements.
                double[] expected = PayloadGenerator.contribution(node, block.length(), request.seed());
                System.arraycopy(expected, 0, expectedAll, block.offset(), block.length());
                String expectedChecksum = VectorChecksum.of(expected);
                if (block.rank() == rootRank) {
                    double[] own = PayloadGenerator.contribution(root, block.length(), request.seed());
                    transfers.add(local(block.rank(), TransferDirection.FROM_RANK, block.offset(), block.length(), expectedChecksum));
                    gathered.add(new GatheredBlock(block.rank(), node, block.offset(), block.length(), own, VectorChecksum.of(own)));
                } else {
                    Collected collected = collect(block, node, expectedChecksum);
                    transfers.add(collected.record());
                    gathered.add(new GatheredBlock(block.rank(), node, block.offset(), block.length(), collected.values(),
                            collected.values() == null ? null : VectorChecksum.of(collected.values())));
                }
            }
            String expectedChecksum = VectorChecksum.of(expectedAll);
            Boolean whole = null;
            if (gathered.stream().noneMatch(GatheredBlock::hole)) {
                double[] assembled = new double[n];
                gathered.forEach(b -> System.arraycopy(b.values(), 0, assembled, b.offset(), b.length()));
                whole = VectorChecksum.of(assembled).equals(expectedChecksum);
            }
            return finish(transfers, expectedChecksum, gathered, whole);
        }

        /** Scatter's whole-vector check: false on any mismatch, null while a chunk is unverified, else whether the blocks tile [0, n). */
        private Boolean scatterTiles(List<TransferRecord> transfers, List<BlockPartition.Block> blocks, int n) {
            boolean anyMismatch = transfers.stream().anyMatch(t -> Boolean.FALSE.equals(t.verified()));
            if (anyMismatch) {
                return false;
            }
            boolean allVerified = transfers.stream().allMatch(t -> t.status() == TransferStatus.LOCAL || Boolean.TRUE.equals(t.verified()));
            if (!allVerified) {
                return null;
            }
            int next = 0;
            for (BlockPartition.Block block : blocks) {
                if (block.offset() != next) {
                    return false;
                }
                next = block.end();
            }
            return next == n;
        }

        private TransferRecord local(int rank, TransferDirection direction, int offset, int length, String checksum) {
            return new TransferRecord(rank, root, root, direction, TransferStatus.LOCAL, offset, length, null, null, null, null,
                    checksum, null, null, null, null, null, null, null);
        }

        /** Whether the deadline has passed; otherwise notes the first transfer's start. */
        private boolean deadlinePassed() {
            long now = nanoClock.getAsLong();
            if (now - startNanos >= deadlineNanos) {
                return true;
            }
            if (firstTransferNanos == null) {
                firstTransferNanos = now;
            }
            return false;
        }

        private TransferRecord notAttempted(int rank, int from, int to, TransferDirection direction, int offset, int length,
                                            String expectedChecksum) {
            return new TransferRecord(rank, from, to, direction, TransferStatus.NOT_ATTEMPTED, offset, length, null, null, null, null,
                    expectedChecksum, null, null, null, null, null, null,
                    "The collective's deadline of " + properties.collectiveDeadlineMillis() + " ms had passed before this transfer.");
        }

        private TransferRecord deliver(int rank, int offset, double[] values, String expectedChecksum) {
            int node = ranks.nodes().get(rank);
            if (deadlinePassed()) {
                return notAttempted(rank, root, node, TransferDirection.TO_RANK, offset, values.length, expectedChecksum);
            }
            CallContext context = new CallContext(root, rootClock.tick());
            long id = request.collectiveId();
            long requestBytes = PayloadSizer.sizeOf(context, id, rank, values);
            CallTimer.Measurement<ReceiveReceipt> made = timer.measure(() -> transport.deliver(node, context, id, rank, values));
            lastTransferEndNanos = nanoClock.getAsLong();
            if (made.failed()) {
                return failed(rank, root, node, TransferDirection.TO_RANK, offset, values.length, expectedChecksum, made.failure(),
                        made.elapsedNanos(), requestBytes, context.senderLamport());
            }
            ReceiveReceipt receipt = made.value();
            String mismatch = receipt == null ? "no reply"
                    : mismatch(receipt.nodeId(), receipt.collectiveId(), receipt.rank(), node, rank, null, null);
            if (mismatch != null) {
                return unmatched(rank, root, node, TransferDirection.TO_RANK, offset, values.length, expectedChecksum, mismatch,
                        made.elapsedNanos(), requestBytes, receipt, context.senderLamport(), receipt == null ? null : receipt.lamportTime());
            }
            long after = rootClock.update(receipt.lamportTime());
            boolean verified = receipt.checksum().equals(expectedChecksum) && receipt.elementCount() == values.length;
            return new TransferRecord(rank, root, node, TransferDirection.TO_RANK, TransferStatus.DELIVERED, offset, values.length,
                    made.elapsedNanos(), null, requestBytes, PayloadSizer.sizeOf(receipt), expectedChecksum, receipt.checksum(), verified,
                    null, context.senderLamport(), receipt.lamportTime(), after, null);
        }

        private record Collected(TransferRecord record, double[] values) {
        }

        private Collected collect(BlockPartition.Block block, int node, String expectedChecksum) {
            int rank = block.rank();
            int length = block.length();
            if (deadlinePassed()) {
                return new Collected(notAttempted(rank, node, root, TransferDirection.FROM_RANK, block.offset(), length,
                        expectedChecksum), null);
            }
            CallContext context = new CallContext(root, rootClock.tick());
            long id = request.collectiveId();
            long seed = request.seed();
            long requestBytes = PayloadSizer.sizeOf(context, id, rank, length, seed);
            CallTimer.Measurement<Contribution> made = timer.measure(() -> transport.collect(node, context, id, rank, length, seed));
            lastTransferEndNanos = nanoClock.getAsLong();
            if (made.failed()) {
                return new Collected(failed(rank, node, root, TransferDirection.FROM_RANK, block.offset(), length, expectedChecksum,
                        made.failure(), made.elapsedNanos(), requestBytes, context.senderLamport()), null);
            }
            Contribution contribution = made.value();
            String mismatch = contribution == null ? "no reply"
                    : mismatch(contribution.nodeId(), contribution.collectiveId(), contribution.rank(), node, rank,
                    contribution.length(), length);
            if (mismatch != null) {
                return new Collected(unmatched(rank, node, root, TransferDirection.FROM_RANK, block.offset(), length,
                        expectedChecksum, mismatch, made.elapsedNanos(), requestBytes, contribution, context.senderLamport(),
                        contribution == null ? null : contribution.lamportTime()), null);
            }
            long after = rootClock.update(contribution.lamportTime());
            double[] values = contribution.values();
            String received = VectorChecksum.of(values);   // computed by the root over what arrived
            boolean verified = received.equals(contribution.checksum()) && received.equals(expectedChecksum);
            TransferRecord record = new TransferRecord(rank, node, root, TransferDirection.FROM_RANK, TransferStatus.DELIVERED,
                    block.offset(), length, made.elapsedNanos(), null, requestBytes, PayloadSizer.sizeOf(contribution), expectedChecksum,
                    contribution.checksum(), verified, null, context.senderLamport(), contribution.lamportTime(), after, null);
            return new Collected(record, values);
        }

        /** Why a reply does not belong to the request it answers; null when it does. */
        private String mismatch(int gotNode, long gotCollective, int gotRank, int wantNode, int wantRank,
                                Integer gotLength, Integer wantLength) {
            long wantCollective = request.collectiveId();
            if (gotNode == wantNode && gotCollective == wantCollective && gotRank == wantRank
                    && Objects.equals(gotLength, wantLength)) {
                return null;
            }
            String lengths = wantLength == null ? "" : ", " + wantLength + " values";
            String gotLengths = gotLength == null ? "" : ", " + gotLength + " values";
            return "expected node " + wantNode + ", collective " + wantCollective + ", rank " + wantRank + lengths
                    + "; got node " + gotNode + ", collective " + gotCollective + ", rank " + gotRank + gotLengths;
        }

        private TransferRecord failed(int rank, int from, int to, TransferDirection direction, int offset, int length,
                                      String expectedChecksum, Exception failure, long elapsedNanos, long requestBytes,
                                      long senderLamport) {
            CallStage stage = CallStage.CALL;
            Exception original = failure;
            if (failure instanceof TransferException transferFailure) {
                stage = transferFailure.stage();
                original = transferFailure.original();
            }
            RemoteFailureDetail detail = RemoteFailureDetail.of(original, stage, properties.maxFailureMessageLength());
            boolean sent = stage == CallStage.CALL;   // a failed lookup never reached the endpoint
            return new TransferRecord(rank, from, to, direction, TransferStatus.FAILED, offset, length, null, elapsedNanos,
                    sent ? requestBytes : null, null, expectedChecksum, null, null, detail, sent ? senderLamport : null, null,
                    null, null);
        }

        private TransferRecord unmatched(int rank, int from, int to, TransferDirection direction, int offset, int length,
                                         String expectedChecksum, String why, long elapsedNanos, long requestBytes, Object reply,
                                         long senderLamport, Long replyLamport) {
            RemoteFailureDetail detail = RemoteFailureDetail.observed(CallStage.CALL, RemoteFailureKind.OTHER,
                    "reply did not match the request: " + why, properties.maxFailureMessageLength());
            return new TransferRecord(rank, from, to, direction, TransferStatus.FAILED, offset, length, null, elapsedNanos, requestBytes,
                    reply == null ? null : PayloadSizer.sizeOf(reply), expectedChecksum, null, null, detail, senderLamport,
                    replyLamport, null, null);
        }

        private CollectiveResult finish(List<TransferRecord> transfers, String inputChecksum, List<GatheredBlock> gathered,
                                        Boolean wholeVectorMatches) {
            int matched = 0;
            int mismatched = 0;
            int unknown = 0;
            int delivered = 0;
            int remote = 0;
            for (TransferRecord transfer : transfers) {
                if (transfer.status() == TransferStatus.LOCAL) {
                    continue;
                }
                remote++;
                if (transfer.status() == TransferStatus.DELIVERED) {
                    delivered++;
                }
                if (Boolean.TRUE.equals(transfer.verified())) {
                    matched++;
                } else if (Boolean.FALSE.equals(transfer.verified())) {
                    mismatched++;
                } else {
                    unknown++;
                }
            }
            CollectiveOutcome outcome;
            if (remote == 0 || delivered == remote) {
                outcome = CollectiveOutcome.COMPLETE;
            } else if (delivered == 0) {
                outcome = CollectiveOutcome.FAILED;
            } else {
                outcome = CollectiveOutcome.PARTIAL;
            }
            Long total = firstTransferNanos == null || lastTransferEndNanos == null ? null : lastTransferEndNanos - firstTransferNanos;
            if (total != null && total < 0) {
                throw new IllegalStateException("time source went backwards by " + (-total) + " ns");
            }
            return new CollectiveResult(request.collectiveId(), request.operation(), root, rootRank, ranks.nodes(), ranks.skipped(),
                    request.count(), request.seed(), outcome, null, inputChecksum, transfers, total, gathered,
                    Verification.of(matched, mismatched, unknown, wholeVectorMatches), properties.collectiveDeadlineMillis());
        }
    }
}
