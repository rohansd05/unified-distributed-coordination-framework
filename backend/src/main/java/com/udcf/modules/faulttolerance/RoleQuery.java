package com.udcf.modules.faulttolerance;

import com.udcf.core.clock.LamportClock;
import com.udcf.modules.replication.DataItem;
import com.udcf.modules.replication.ReadReply;
import com.udcf.modules.replication.ReplicationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.IntUnaryOperator;

/**
 * Experiment 8's role query ("who is in charge now?"), sent over the replication TCP channel
 * (710k, shared by Experiments 5 and 8) with Track C's existing READ request: no new port and no
 * protocol change.
 *
 * <p><b>The term record.</b> Every promotion first writes one replicated item, key
 * {@value #TERM_KEY}, synchronously to every backup (like Raft's no-op entry at the start of a
 * term). The replication service stamps it with the new primary's id and epoch. A role query
 * READs that key from every peer; each reply carries the peer's store epoch and the term record it
 * holds, which names the primary of that epoch. An answer is therefore <b>the peer's replicated
 * term record, not its in-memory role flag</b>. The record is not a client write: it is never in
 * the acknowledged ledger, but it shows as one extra row in Experiment 5's replica grid.</p>
 *
 * <p><b>Bounds.</b> Peers are asked in parallel, each with the replication client's connect and
 * read timeout, so a whole query takes at most about 2 x {@code timeout-millis} however many
 * peers are silent. Every READ ticks the asking node's Lamport clock and merges the reply's time
 * (link L4, done by {@link ReplicationClient}).</p>
 *
 * <p>Thread safety: immutable; {@link #ask} may run concurrently. Covered by RoleQueryTest.</p>
 */
public class RoleQuery {

    /** The replicated key holding the term record. */
    public static final String TERM_KEY = "failover.term";

    private static final Logger log = LoggerFactory.getLogger(RoleQuery.class);

    /**
     * The answers to one query.
     *
     * @param reports one per peer that answered, in peer order
     * @param silent  the peers that did not answer (refused, timed out or closed), in peer order
     */
    public record Answers(List<RoleReport> reports, List<Integer> silent) {

        public Answers {
            reports = List.copyOf(Objects.requireNonNull(reports, "reports must not be null"));
            silent = List.copyOf(Objects.requireNonNull(silent, "silent must not be null"));
        }

        /** Event data: each answer as {nodeId, role, epoch, believedPrimaryId}, nulls kept. */
        public List<Map<String, Object>> describe() {
            List<Map<String, Object>> described = new ArrayList<>();
            for (RoleReport report : reports) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("nodeId", report.nodeId());
                entry.put("role", report.role().name());
                entry.put("epoch", report.epoch());
                entry.put("believedPrimaryId", report.believedPrimaryId());
                described.add(entry);
            }
            return described;
        }
    }

    private final ReplicationClient client;

    /** @param timeoutMillis connect and read timeout of each READ: the replication timeout */
    public RoleQuery(int timeoutMillis) {
        this.client = new ReplicationClient(timeoutMillis);
    }

    /** The value written in a term record (display only; the primary is read from the item's origin). */
    public static String termValue(int primaryId, long epoch) {
        return String.format(Locale.ROOT, "Node %d is primary at epoch %d", primaryId, epoch);
    }

    /**
     * Reads one peer's answer: its store epoch, and the primary named by the term record it holds
     * at that epoch (null if it holds none at that epoch). The peer reports itself PRIMARY exactly
     * when that record is its own.
     */
    public static RoleReport toReport(ReadReply reply) {
        Objects.requireNonNull(reply, "reply must not be null");
        Integer believed = reply.item()
                .filter(item -> item.epoch() == reply.storeEpoch())
                .map(DataItem::originNode)
                .orElse(null);
        FailoverRole role = Objects.equals(believed, reply.nodeId()) ? FailoverRole.PRIMARY : FailoverRole.BACKUP;
        return new RoleReport(reply.nodeId(), role, reply.storeEpoch(), believed);
    }

    /**
     * Asks every peer at once and waits for all of them (each bounded by the client timeouts).
     *
     * @param selfId    the asking node (the sender id on the wire)
     * @param clock     the asking node's Lamport clock
     * @param peers     the nodes to ask, never {@code selfId}
     * @param peerPort  a node's replication port
     * @param executor  runs one READ per peer; shut down by the caller's crash
     * @throws InterruptedException if the caller is interrupted while waiting (a crash or stop)
     */
    public Answers ask(int selfId, LamportClock clock, List<Integer> peers, IntUnaryOperator peerPort,
                       ExecutorService executor) throws InterruptedException {
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(peerPort, "peerPort must not be null");
        Objects.requireNonNull(executor, "executor must not be null");
        List<Integer> asked = List.copyOf(Objects.requireNonNull(peers, "peers must not be null"));
        if (asked.contains(selfId)) {
            throw new IllegalArgumentException("node " + selfId + " cannot ask itself");
        }
        List<CompletableFuture<ReadReply>> futures = new ArrayList<>();
        for (int peer : asked) {
            int port = peerPort.applyAsInt(peer);
            CompletableFuture<ReadReply> future;
            try {
                future = CompletableFuture.supplyAsync(() -> read(port, selfId, clock, peer), executor);
            } catch (RejectedExecutionException e) {
                future = CompletableFuture.completedFuture(null);   // shutting down: no answer
            }
            futures.add(future);
        }
        List<RoleReport> reports = new ArrayList<>();
        List<Integer> silent = new ArrayList<>();
        for (int i = 0; i < asked.size(); i++) {
            ReadReply reply;
            try {
                reply = futures.get(i).get();
            } catch (ExecutionException e) {
                reply = null;
            }
            if (reply == null) {
                silent.add(asked.get(i));
            } else {
                reports.add(toReport(reply));
            }
        }
        return new Answers(reports, silent);
    }

    /** One READ; null if the peer did not answer. */
    private ReadReply read(int port, int selfId, LamportClock clock, int peer) {
        try {
            ReadReply reply = client.read(port, selfId, clock, TERM_KEY);
            if (reply.nodeId() != peer) {
                log.warn("Node {}: role query to node {} was answered by node {}; ignored", selfId, peer, reply.nodeId());
                return null;
            }
            return reply;
        } catch (IOException e) {
            log.debug("Node {}: role query to node {} got no answer: {}", selfId, peer, e.toString());
            return null;
        }
    }
}
