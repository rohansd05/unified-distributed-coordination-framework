package com.udcf.modules.mapreduce;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Line-based wire protocol for MapReduce tasks and replies over TCP.
 *
 * <pre>
 * request: &lt;taskType&gt;|&lt;senderId&gt;|&lt;lamportTime&gt;|&lt;taskId&gt;|&lt;jobName&gt;|&lt;base64Payload&gt;\n
 * reply:   OK|&lt;nodeId&gt;|&lt;lamportTime&gt;|&lt;taskId&gt;|&lt;base64Result&gt;\n
 * error:   ERROR|&lt;nodeId&gt;|&lt;lamportTime&gt;|&lt;taskId&gt;|&lt;base64Error&gt;\n
 * </pre>
 *
 * <p>All payloads and error messages are RFC 4648 Base64 encoded over UTF-8, safely
 * round-tripping delimiters ({@code |}), tabs, newlines ({@code \n}), and carriage returns.
 * The usable payload size is approximately {@code 0.75 * maxRequestBytes} due to Base64
 * expansion.</p>
 */
public final class MapReduceProtocol {

    public static final String OK = "OK";
    public static final String ERROR = "ERROR";
    public static final String UNKNOWN_TASK_ID = "-";
    public static final long MAX_SANE_LAMPORT = Long.MAX_VALUE / 4;

    private MapReduceProtocol() {
    }

    /**
     * Decoded representation of an incoming task request.
     */
    public record Request(
            TaskType taskType,
            int senderId,
            long lamportTime,
            String taskId,
            String jobName,
            String payload
    ) {
        public Request {
            Objects.requireNonNull(taskType, "taskType must not be null");
            Objects.requireNonNull(taskId, "taskId must not be null");
            Objects.requireNonNull(jobName, "jobName must not be null");
            Objects.requireNonNull(payload, "payload must not be null");
        }
    }

    /**
     * Decoded representation of a worker reply.
     */
    public record Reply(
            boolean isOk,
            int nodeId,
            long lamportTime,
            String taskId,
            String resultPayload,
            String errorMessage
    ) {
        public static Reply success(int nodeId, long lamportTime, String taskId, String resultPayload) {
            return new Reply(true, nodeId, lamportTime, taskId, resultPayload, null);
        }

        public static Reply error(int nodeId, long lamportTime, String taskId, String errorMessage) {
            return new Reply(false, nodeId, lamportTime, taskId, null, errorMessage);
        }
    }

    /**
     * Encodes a task request into a single protocol line without trailing newline.
     */
    public static String encodeRequest(TaskType taskType, int senderId, long lamportTime,
                                       String taskId, String jobName, String payload) {
        Objects.requireNonNull(taskType, "taskType must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(jobName, "jobName must not be null");
        String b64Payload = encodeBase64(payload != null ? payload : "");
        return taskType.name() + "|" + senderId + "|" + lamportTime + "|" + taskId + "|" + jobName + "|" + b64Payload;
    }

    /**
     * Encodes a successful task reply into a single protocol line without trailing newline.
     */
    public static String encodeOkReply(int nodeId, long lamportTime, String taskId, String resultPayload) {
        String b64Result = encodeBase64(resultPayload != null ? resultPayload : "");
        String id = taskId != null ? taskId : UNKNOWN_TASK_ID;
        return OK + "|" + nodeId + "|" + lamportTime + "|" + id + "|" + b64Result;
    }

    /**
     * Encodes an error reply into a single protocol line without trailing newline.
     */
    public static String encodeErrorReply(int nodeId, long lamportTime, String taskId, String errorMessage) {
        String b64Error = encodeBase64(errorMessage != null ? errorMessage : "");
        String id = taskId != null ? taskId : UNKNOWN_TASK_ID;
        return ERROR + "|" + nodeId + "|" + lamportTime + "|" + id + "|" + b64Error;
    }

    /**
     * Decodes a request line.
     *
     * @throws ProtocolException if the line is malformed, has invalid numbers, or out-of-range values
     */
    public static Request decodeRequest(String line, int minNodeId, int maxNodeId) throws ProtocolException {
        if (line == null || line.isEmpty()) {
            throw new ProtocolException("Empty request line");
        }
        String[] parts = line.split("\\|", 6);
        if (parts.length < 6) {
            throw new ProtocolException("Malformed request: expected 6 fields, got " + parts.length);
        }

        TaskType type;
        try {
            type = TaskType.valueOf(parts[0]);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Unknown task type: " + parts[0]);
        }

        int senderId;
        try {
            senderId = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new ProtocolException("Malformed sender ID: " + parts[1]);
        }
        if (senderId < minNodeId || senderId > maxNodeId) {
            throw new ProtocolException("Unknown sender node ID: " + senderId);
        }

        long lamportTime;
        try {
            lamportTime = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            throw new ProtocolException("Malformed Lamport time: " + parts[2]);
        }
        if (lamportTime < 0 || lamportTime > MAX_SANE_LAMPORT) {
            throw new ProtocolException("Out-of-range Lamport time: " + lamportTime);
        }

        String taskId = parts[3];
        if (taskId.isBlank()) {
            throw new ProtocolException("Task ID must not be blank");
        }

        String jobName = parts[4];
        if (jobName.isBlank()) {
            throw new ProtocolException("Job name must not be blank");
        }

        String payload;
        try {
            payload = decodeBase64(parts[5]);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Corrupt Base64 payload: " + e.getMessage());
        }

        return new Request(type, senderId, lamportTime, taskId, jobName, payload);
    }

    /**
     * Decodes a reply line from a worker.
     *
     * @throws ProtocolException if the reply is malformed or invalid
     */
    public static Reply decodeReply(String line) throws ProtocolException {
        if (line == null || line.isEmpty()) {
            throw new ProtocolException("Empty reply line");
        }
        String[] parts = line.split("\\|", 5);
        if (parts.length < 5) {
            throw new ProtocolException("Malformed reply: expected 5 fields, got " + parts.length);
        }

        String status = parts[0];
        int nodeId;
        try {
            nodeId = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new ProtocolException("Malformed node ID in reply: " + parts[1]);
        }

        long lamportTime;
        try {
            lamportTime = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            throw new ProtocolException("Malformed Lamport time in reply: " + parts[2]);
        }
        if (lamportTime < 0 || lamportTime > MAX_SANE_LAMPORT) {
            throw new ProtocolException("Out-of-range Lamport time in reply: " + lamportTime);
        }

        String taskId = UNKNOWN_TASK_ID.equals(parts[3]) ? null : parts[3];

        if (OK.equals(status)) {
            String result;
            try {
                result = decodeBase64(parts[4]);
            } catch (IllegalArgumentException e) {
                throw new ProtocolException("Corrupt Base64 in OK reply: " + e.getMessage());
            }
            return Reply.success(nodeId, lamportTime, taskId, result);
        } else if (ERROR.equals(status)) {
            String errorMsg;
            try {
                errorMsg = decodeBase64(parts[4]);
            } catch (IllegalArgumentException e) {
                errorMsg = parts[4]; // fallback if raw
            }
            return Reply.error(nodeId, lamportTime, taskId, errorMsg);
        } else {
            throw new ProtocolException("Unknown reply status: " + status);
        }
    }

    /**
     * Reads a single line bounded by {@code maxBytes} from the given input stream.
     *
     * @return the line content as a UTF-8 string without line endings, or {@code null} on EOF
     * @throws ProtocolException if line exceeds {@code maxBytes} before a newline
     * @throws IOException on socket read failure
     */
    public static String readLineBounded(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int bytesRead = 0;
        int b;
        while ((b = in.read()) != -1) {
            bytesRead++;
            if (bytesRead > maxBytes) {
                throw new ProtocolException("Line exceeds maxRequestBytes limit (" + maxBytes + ")");
            }
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buffer.write(b);
            }
        }
        if (b == -1 && buffer.size() == 0) {
            return null; // clean EOF
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /**
     * Reads a single line bounded by {@code maxBytes} from a blocking {@link SocketChannel}.
     * Channel reads are natively interruptible.
     */
    public static String readLineBounded(SocketChannel channel, int maxBytes) throws IOException {
        return readLineBounded(channel.socket().getInputStream(), maxBytes);
    }

    private static String encodeBase64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeBase64(String b64) {
        return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
    }
}
