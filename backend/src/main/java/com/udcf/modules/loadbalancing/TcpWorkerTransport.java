package com.udcf.modules.loadbalancing;

import com.udcf.core.clock.LamportClock;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.modules.multithreading.RequestStatus;
import com.udcf.modules.multithreading.RequestsClient;
import com.udcf.modules.multithreading.WorkReply;
import com.udcf.modules.multithreading.WorkloadType;
import com.udcf.modules.multithreading.dto.GenerateRequestsCommand;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Experiment 6 transport: sends each request over TCP to the worker node's Exp 2 requests
 * service (127.0.0.1:{@code ports().requests()}, 720k), where it runs as real SHA-256 work
 * on that node's Exp 2 executor (link L3). Exp 6 has no port of its own.
 *
 * <p><b>Sender.</b> The balancer is a cluster-level client: sender id {@value #SENDER_ID},
 * with the cluster's Lamport clock. {@link RequestsClient} ticks it before sending (rule 2)
 * and merges the reply's time (rule 3); the node merges on receipt (link L4).</p>
 *
 * <p><b>Work units</b> are the Exp 2 payload size, 1 to 5000; one unit is 40 SHA-256 rounds
 * on the worker, times its work multiplier. This is not the legacy demo's unit (one SHA-256
 * round): its 900 units are about 23 Exp 2 units. Always {@link WorkloadType#CPU_HASH}, so
 * the work is real and nothing here is simulated (R7).</p>
 *
 * <p><b>Outcome mapping</b> (the {@link WorkerTransport} contract):</p>
 * <ul>
 *   <li>COMPLETED reply: return normally (served).</li>
 *   <li>REJECTED or FAILED reply, or a {@link ProtocolException} (the node answered ERROR,
 *       or sent a malformed reply): {@link WorkerDeclinedException}. The node is alive, so
 *       the request is rerouted and the circuit breaker is not tripped.</li>
 *   <li>{@code ConnectException} (a crashed node), {@code SocketTimeoutException} (a silent
 *       node, or a queue wait longer than the timeout) and a connection closed without a
 *       reply (a crash mid-request): rethrown as {@link IOException}; the balancer reroutes
 *       and trips the breaker. A timed-out request may still finish on that worker, so a
 *       request can run twice: delivery is at least once, never exactly once.</li>
 * </ul>
 *
 * <p><b>Events</b> (module {@value #MODULE}, node 0, the cluster clock ticked for the event,
 * peer = the worker): {@code DISPATCH_FAILED} for an attempt that tripped the breaker, data
 * {@code requestId, reason} (the exception's simple class name) {@code , message};
 * {@code DISPATCH_DECLINED} for a declined attempt, data {@code requestId, status}
 * (REJECTED, FAILED or PROTOCOL_ERROR) {@code , detail}, plus {@code workerRequestId} when the
 * node gave the request an id. Served attempts publish nothing here (the node already
 * publishes a {@code REQUEST_COMPLETED} event with peer 0). At most
 * {@value #MAX_EVENTS_PER_WORKER} events per worker per transport; beyond that only the
 * balancer's counters ({@link WorkerInfo#failed()}, {@link WorkerInfo#declined()}) move.
 * Build one transport per run so the cap is per run. Given a run id, every event also carries
 * {@code runId}, first in its data.</p>
 *
 * <p>No locks: safe for many client threads at once, and never holds a monitor across a
 * socket call. Covered by TcpWorkerTransportTest.</p>
 */
public class TcpWorkerTransport implements WorkerTransport {

    public static final String MODULE = "loadbalancing";

    /** The balancer is a cluster-level client. */
    public static final int SENDER_ID = 0;

    /** Real SHA-256 work only; never the simulated workloads. */
    public static final WorkloadType WORKLOAD = WorkloadType.CPU_HASH;

    /** Most DISPATCH_FAILED plus DISPATCH_DECLINED events published for one worker. */
    public static final int MAX_EVENTS_PER_WORKER = 20;

    static final String PROTOCOL_ERROR = "PROTOCOL_ERROR";

    private final RequestsClient client;
    private final LamportClock senderClock;
    private final ClusterEventBus bus;
    private final Map<Integer, AtomicInteger> eventsByWorker = new ConcurrentHashMap<>();
    private final String runId;

    /**
     * @param client      sends one request line and reads one reply, with its timeout
     * @param senderClock the balancer's Lamport clock (the cluster clock)
     * @param bus         receives the DISPATCH_* events
     */
    public TcpWorkerTransport(RequestsClient client, LamportClock senderClock, ClusterEventBus bus) {
        this(client, senderClock, bus, null);
    }

    /** @param runId added as {@code runId} to every event this transport publishes; null for none */
    public TcpWorkerTransport(RequestsClient client, LamportClock senderClock, ClusterEventBus bus, String runId) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.senderClock = Objects.requireNonNull(senderClock, "senderClock must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.runId = runId;
    }

    /** @throws IllegalArgumentException if {@code workUnits} is outside the Exp 2 payload range 1-5000 */
    @Override
    public void send(WorkerInfo worker, int requestId, int workUnits) throws IOException, WorkerDeclinedException {
        Objects.requireNonNull(worker, "worker must not be null");
        requireWorkUnits(workUnits);

        WorkReply reply;
        try {
            reply = client.send(worker.port(), SENDER_ID, senderClock, WORKLOAD, workUnits);
        } catch (ProtocolException e) {
            String detail = messageOf(e);
            declined(worker, requestId, PROTOCOL_ERROR, detail, null);
            throw new WorkerDeclinedException("Node " + worker.nodeId() + " answered badly: " + detail);
        } catch (IOException e) {
            failed(worker, requestId, e);
            throw e;
        }
        if (reply.status() != RequestStatus.COMPLETED) {
            declined(worker, requestId, reply.status().name(), reply.detail(), reply.requestId());
            throw new WorkerDeclinedException("Node " + worker.nodeId() + " " + reply.status().name()
                    + " the request: " + reply.detail());
        }
    }

    /** @throws IllegalArgumentException if {@code workUnits} is outside 1-5000 */
    public static void requireWorkUnits(int workUnits) {
        if (workUnits < GenerateRequestsCommand.MIN_PAYLOAD_SIZE || workUnits > GenerateRequestsCommand.MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("workUnits must be " + GenerateRequestsCommand.MIN_PAYLOAD_SIZE + "-"
                    + GenerateRequestsCommand.MAX_PAYLOAD_SIZE + " (the Exp 2 payload size), was " + workUnits);
        }
    }

    private void failed(WorkerInfo worker, int requestId, IOException e) {
        if (!underCap(worker)) {
            return;
        }
        String reason = e.getClass().getSimpleName();
        Map<String, Object> data = new LinkedHashMap<>();
        putRunId(data);
        data.put("requestId", requestId);
        data.put("reason", reason);
        data.put("message", messageOf(e));
        bus.publish(EventDraft.of(MODULE, 0, "DISPATCH_FAILED", senderClock.tick())
                .withPeer(worker.nodeId())
                .withMessage("Request " + requestId + " could not be served by node " + worker.nodeId()
                        + " (" + reason + ")")
                .withData(data));
    }

    private void declined(WorkerInfo worker, int requestId, String status, String detail, String workerRequestId) {
        if (!underCap(worker)) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        putRunId(data);
        data.put("requestId", requestId);
        data.put("status", status);
        data.put("detail", detail == null ? "" : detail);
        if (workerRequestId != null) {
            data.put("workerRequestId", workerRequestId);
        }
        bus.publish(EventDraft.of(MODULE, 0, "DISPATCH_DECLINED", senderClock.tick())
                .withPeer(worker.nodeId())
                .withMessage("Node " + worker.nodeId() + " declined request " + requestId + " (" + status + ")")
                .withData(data));
    }

    private void putRunId(Map<String, Object> data) {
        if (runId != null) {
            data.put("runId", runId);
        }
    }

    private boolean underCap(WorkerInfo worker) {
        return eventsByWorker.computeIfAbsent(worker.nodeId(), id -> new AtomicInteger())
                .incrementAndGet() <= MAX_EVENTS_PER_WORKER;
    }

    private static String messageOf(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
