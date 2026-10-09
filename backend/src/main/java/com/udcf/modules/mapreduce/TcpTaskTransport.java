package com.udcf.modules.mapreduce;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntUnaryOperator;

/**
 * TCP transport implementation of {@link TaskTransport} for shipping MapReduce
 * tasks from the coordinator to worker nodes on {@code ports().mapreduce()} (730k).
 *
 * <p>Uses interruptible {@link SocketChannel} in blocking mode with per-call socket
 * read timeouts so thread interruption immediately unblocks stuck I/O. Stamps requests
 * and merges replies using the coordinator's {@link ClusterNode#clock()}.</p>
 */
public class TcpTaskTransport implements TaskTransport {

    private final ClusterNode coordinatorNode;
    private final IntUnaryOperator peerPortResolver;
    private final ClusterEventBus bus;
    private final MapReduceProperties properties;
    private final AtomicLong nextTaskId = new AtomicLong(0);

    public TcpTaskTransport(ClusterNode coordinatorNode, IntUnaryOperator peerPortResolver,
                            ClusterEventBus bus, MapReduceProperties properties) {
        this.coordinatorNode = Objects.requireNonNull(coordinatorNode, "coordinatorNode must not be null");
        this.peerPortResolver = Objects.requireNonNull(peerPortResolver, "peerPortResolver must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    public TcpTaskTransport(Cluster cluster, ClusterNode coordinatorNode,
                            ClusterEventBus bus, MapReduceProperties properties) {
        this(coordinatorNode, id -> cluster.node(id).ports().mapreduce(), bus, properties);
    }

    @Override
    public String executeTask(int targetNodeId, TaskType taskType, String jobName, String payload)
            throws IOException {
        Objects.requireNonNull(taskType, "taskType must not be null");
        Objects.requireNonNull(jobName, "jobName must not be null");

        String taskId = String.valueOf(nextTaskId.incrementAndGet());
        long sendLamport = coordinatorNode.clock().tick();

        String reqLine = MapReduceProtocol.encodeRequest(
                taskType, coordinatorNode.id(), sendLamport, taskId, jobName, payload);
        byte[] reqBytes = (reqLine + "\n").getBytes(StandardCharsets.UTF_8);

        // Cap check before attempting network connection (usable payload ~ 0.75 * maxRequestBytes)
        if (reqBytes.length > properties.maxRequestBytes()) {
            throw new IOException("Request payload exceeds maxRequestBytes limit ("
                    + reqBytes.length + " > " + properties.maxRequestBytes() + ")");
        }

        String taskTypeName = taskType == TaskType.MAP ? "Map" : "Reduce";
        Map<String, Object> sentData = new LinkedHashMap<>();
        sentData.put("taskId", taskId);
        sentData.put("taskType", taskType.name());
        sentData.put("jobName", jobName);
        sentData.put("coordinatorId", coordinatorNode.id());
        sentData.put("workerId", targetNodeId);
        sentData.put("lamportTime", sendLamport);

        bus.publish(EventDraft.of(MapReduceNodeService.MODULE, coordinatorNode.id(), "TASK_SENT", sendLamport)
                .withPeer(targetNodeId)
                .withMessage(taskTypeName + " task " + taskId + " sent to node " + targetNodeId)
                .withData(sentData));

        int port = peerPortResolver.applyAsInt(targetNodeId);

        try {
            return sendOverSocket(targetNodeId, port, reqBytes, taskId, taskType, jobName);
        } catch (IOException e) {
            String reason = describeError(e);
            publishAttemptFailed(taskId, taskType, jobName, targetNodeId, reason);
            throw e;
        }
    }

    private String sendOverSocket(int targetNodeId, int port, byte[] reqBytes,
                                  String taskId, TaskType taskType, String jobName) throws IOException {
        try (SocketChannel channel = SocketChannel.open()) {
            channel.configureBlocking(true);
            channel.socket().setSoTimeout((int) properties.socketReadTimeoutMillis());
            channel.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));

            ByteBuffer writeBuf = ByteBuffer.wrap(reqBytes);
            while (writeBuf.hasRemaining()) {
                channel.write(writeBuf);
            }

            InputStream in = new BufferedInputStream(channel.socket().getInputStream());
            String replyLine = MapReduceProtocol.readLineBounded(in, properties.maxRequestBytes());
            if (replyLine == null) {
                throw new IOException("No reply received from worker node " + targetNodeId);
            }

            MapReduceProtocol.Reply reply = MapReduceProtocol.decodeReply(replyLine);
            coordinatorNode.clock().update(reply.lamportTime());

            if (!reply.isOk()) {
                throw new IOException("Worker node " + targetNodeId + " returned error: " + reply.errorMessage());
            }

            return reply.resultPayload();
        } catch (ClosedByInterruptException cbie) {
            Thread.currentThread().interrupt();
            throw new IOException("Task execution interrupted on worker " + targetNodeId, cbie);
        }
    }

    private void publishAttemptFailed(String taskId, TaskType taskType, String jobName,
                                      int targetNodeId, String reason) {
        String taskTypeName = taskType == TaskType.MAP ? "Map" : "Reduce";
        long stamped = coordinatorNode.clock().current();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("taskType", taskType.name());
        data.put("jobName", jobName);
        data.put("coordinatorId", coordinatorNode.id());
        data.put("workerId", targetNodeId);
        data.put("reason", reason);
        data.put("lamportTime", stamped);

        bus.publish(EventDraft.of(MapReduceNodeService.MODULE, coordinatorNode.id(), "TASK_ATTEMPT_FAILED", stamped)
                .withPeer(targetNodeId)
                .withMessage(taskTypeName + " task " + taskId + " attempt to node " + targetNodeId + " failed: " + reason)
                .withData(data));
    }

    private static String describeError(IOException e) {
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        return e.getClass().getSimpleName();
    }
}
