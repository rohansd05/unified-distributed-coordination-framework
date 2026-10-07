package com.udcf.modules.multithreading;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.core.module.ExperimentModule;
import com.udcf.core.module.ModuleActionGuard;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.multithreading.RequestProcessingService.Submission;
import com.udcf.modules.multithreading.dto.BatchDto;
import com.udcf.modules.multithreading.dto.BatchKind;
import com.udcf.modules.multithreading.dto.GenerateRequestsCommand;
import com.udcf.modules.multithreading.dto.MultithreadingOverviewDto;
import com.udcf.modules.multithreading.dto.NodeRequestsDto;
import com.udcf.modules.multithreading.dto.RequestResult;
import com.udcf.modules.multithreading.dto.ThreadPoolStats;
import com.udcf.modules.multithreading.dto.WorkloadDto;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Experiment 2 (lab 2) on the shared cluster: bursts of requests sent to one node's
 * executor, per-node statistics, and the backpressure demonstration.
 *
 * <p><b>Bursts.</b> A batch and the backpressure demo both use
 * {@link RequestProcessingService#submitTogether}: the burst is submitted before any of its
 * work starts, so the accepted and rejected counts are exact. The backpressure demo sends
 * {@code threads + queueCapacity + extraRequests} requests (extraRequests at least 1), so at
 * least extraRequests are always rejected.</p>
 *
 * <p><b>Events</b> (module {@value #ID}, the node's own Lamport clock), one pair per burst and
 * never one per request: {@code BATCH_SUBMITTED} and, once every accepted request has ended,
 * {@code BATCH_FINISHED}. A node that is down at that moment publishes nothing, like any
 * crashed node.</p>
 *
 * <p><b>Status.</b> BUSY while the backpressure demo holds the module's
 * {@link ModuleActionGuard} (a second demo gets HTTP 409); RUNNING while any burst is still
 * draining; otherwise IDLE. The guard is released when the demo's burst has ended, also when
 * a crash ends it.</p>
 */
@Component
public class MultithreadingModule implements ExperimentModule {

    public static final String ID = RequestsNodeService.MODULE;

    static final String CAPACITY_NOTE = "These capacity profiles are set in the configuration, not measured. "
            + "Every node runs on the same computer, so a SLOW node is slow because it is given fewer "
            + "threads and more work per request, not because its hardware is slower.";

    static final List<WorkloadDto> WORKLOADS = List.of(
            new WorkloadDto(WorkloadType.CPU_HASH,
                    "Each request repeatedly computes SHA-256 hashes, which is real work for the processor.",
                    false, null),
            new WorkloadDto(WorkloadType.IO_SIMULATED,
                    "Each request waits, as if for a network reply or a disk read.",
                    true, "The wait is a sleep standing in for a real network or disk delay, "
                            + "so this waiting time is simulated."),
            new WorkloadDto(WorkloadType.MIXED,
                    "Each request computes SHA-256 hashes and then waits, like most real requests.",
                    true, "The waiting half is a sleep standing in for a real network or disk delay, "
                            + "so that part is simulated; the hashing half is real work."));

    private static final Logger log = LoggerFactory.getLogger(MultithreadingModule.class);

    private final Cluster cluster;
    private final MultithreadingProperties properties;
    private final MeterRegistry meterRegistry;
    private final ClusterEventBus bus;
    private final ModuleActionGuard guard = new ModuleActionGuard(ID);
    private final AtomicInteger openBatches = new AtomicInteger();

    public MultithreadingModule(Cluster cluster, MultithreadingProperties properties,
                                MeterRegistry meterRegistry, ClusterEventBus bus) {
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int labNumber() {
        return 2;
    }

    @Override
    public String title() {
        return "Multithreading";
    }

    @Override
    public ModuleStatus status() {
        if (guard.isBusy()) {
            return ModuleStatus.BUSY;
        }
        return openBatches.get() > 0 ? ModuleStatus.RUNNING : ModuleStatus.IDLE;
    }

    /**
     * Clears every started node's request history and throughput samples. Work in flight
     * carries on; nothing is stopped and nothing is published. Safe while a node crashes or
     * recovers (see {@link RequestsNodeService#clearHistory()}).
     */
    @Override
    public void reset() {
        for (ClusterNode node : cluster.nodes()) {
            RequestsNodeService.find(node).ifPresent(RequestsNodeService::clearHistory);
        }
    }

    /**
     * Sends {@code command.count()} requests to the node as one burst.
     *
     * @throws com.udcf.core.cluster.UnknownNodeException if there is no such node (HTTP 404)
     * @throws com.udcf.core.cluster.NodeDownException    if the node is crashed (HTTP 409)
     */
    public BatchDto submitBatch(int nodeId, GenerateRequestsCommand command) {
        return submit(cluster.node(nodeId), BatchKind.BATCH, command.type(), command.payloadSize(),
                command.count(), null);
    }

    /**
     * The backpressure demonstration: overflows the node's queue so some requests are
     * REJECTED. One at a time across the module; the module is BUSY until its burst has ended.
     *
     * @throws com.udcf.core.module.ModuleBusyException if a demo is already running (HTTP 409)
     */
    public BatchDto backpressure(int nodeId) {
        ClusterNode node = cluster.node(nodeId);
        MultithreadingProperties.Backpressure demo = properties.backpressure();
        ModuleActionGuard.ActionTicket ticket = guard.begin("Backpressure demo on node " + nodeId);
        try {
            int count = node.capacity().threads() + properties.queueCapacity() + demo.extraRequests();
            return submit(node, BatchKind.BACKPRESSURE, demo.workload(), demo.payloadSize(), count, ticket);
        } catch (RuntimeException e) {
            ticket.close();
            throw e;
        }
    }

    /** Every node, its capacity and its live executor snapshot (null while not running). */
    public MultithreadingOverviewDto overview() {
        List<NodeRequestsDto> nodes = cluster.nodes().stream().map(node -> {
            Optional<ThreadPoolStats> stats = RequestsNodeService.find(node).flatMap(RequestsNodeService::snapshot);
            return new NodeRequestsDto(node.id(), node.status(), node.capacity(), true,
                    node.capacity().threads(), node.capacity().workMultiplier(), node.ports().requests(),
                    stats.isPresent(), stats.orElse(null));
        }).toList();
        return new MultithreadingOverviewDto(status(), guard.currentAction().orElse(null), CAPACITY_NOTE,
                WORKLOADS, nodes);
    }

    /**
     * The node's most recent requests, newest first. Empty if its service has never started;
     * still available while the node is crashed, because the history survives.
     */
    public List<RequestResult> requests(int nodeId, int limit) {
        return RequestsNodeService.find(cluster.node(nodeId))
                .map(service -> service.registry().recent(limit).stream().map(DistributedRequest::toResult).toList())
                .orElse(List.of());
    }

    private BatchDto submit(ClusterNode node, BatchKind kind, WorkloadType type, int payloadSize, int count,
                            ModuleActionGuard.ActionTicket ticket) {
        RequestsNodeService service = RequestsNodeService.on(node, properties, meterRegistry, bus);
        String batchId = UUID.randomUUID().toString().substring(0, 8);
        long startNanos = System.nanoTime();

        openBatches.incrementAndGet();
        List<Submission> submissions;
        try {
            submissions = service.processing().submitTogether(type, payloadSize, count);
        } catch (RuntimeException e) {
            openBatches.decrementAndGet();
            throw e;
        }

        List<String> ids = submissions.stream().map(Submission::requestId).toList();
        int rejected = (int) submissions.stream().map(Submission::result)
                .filter(future -> future.isDone() && future.join().status() == RequestStatus.REJECTED)
                .count();
        BatchDto batch = new BatchDto(batchId, node.id(), kind, type, payloadSize, count, count - rejected,
                rejected, ids);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchId", batchId);
        data.put("kind", kind.name());
        data.put("requested", count);
        data.put("accepted", batch.accepted());
        data.put("rejected", rejected);
        data.put("workload", type.name());
        data.put("payloadSize", payloadSize);
        publish(node, "BATCH_SUBMITTED", "Node " + node.id() + " received " + count + " requests ("
                + rejected + " rejected)", data);

        CompletableFuture<?>[] results = submissions.stream().map(Submission::result).toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(results)
                .whenComplete((ignored, error) -> finish(service, node, batchId, kind, submissions, startNanos, ticket));
        return batch;
    }

    /** Runs once every request of the burst has ended, whichever thread ended the last one. */
    private void finish(RequestsNodeService service, ClusterNode node, String batchId, BatchKind kind,
                        List<Submission> submissions, long startNanos, ModuleActionGuard.ActionTicket ticket) {
        try {
            if (!service.isRunning()) {
                return;   // the node is down: a dead node publishes nothing
            }
            List<RequestResult> results = submissions.stream().map(s -> s.result().join()).toList();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("batchId", batchId);
            data.put("kind", kind.name());
            data.put("completed", results.stream().filter(r -> r.status() == RequestStatus.COMPLETED).count());
            data.put("failed", results.stream().filter(r -> r.status() == RequestStatus.FAILED).count());
            data.put("threads", results.stream().map(RequestResult::threadName).filter(Objects::nonNull)
                    .distinct().count());
            data.put("elapsedMillis", Math.round((System.nanoTime() - startNanos) / 1_000_000d * 1000d) / 1000d);
            publish(node, "BATCH_FINISHED", "Node " + node.id() + " finished batch " + batchId, data);
        } catch (RuntimeException e) {
            log.warn("Could not report the end of batch {} on node {}", batchId, node.id(), e);
        } finally {
            openBatches.decrementAndGet();
            if (ticket != null) {
                ticket.close();
            }
        }
    }

    private void publish(ClusterNode node, String type, String message, Map<String, Object> data) {
        bus.publish(EventDraft.of(ID, node.id(), type, node.clock().tick()).withMessage(message).withData(data));
    }
}
