package com.udcf.modules.multithreading;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;

/**
 * The line protocol of a node's requests service: one request line and one reply line
 * per TCP connection, the same shape as the legacy Exp 6 worker.
 *
 * <pre>
 * request  WORK|&lt;senderId&gt;|&lt;lamport&gt;|&lt;TYPE&gt;;&lt;payloadSize&gt;
 * reply    &lt;STATUS&gt;|&lt;nodeId&gt;|&lt;lamport&gt;|&lt;requestId&gt;;&lt;thread&gt;;&lt;queueWaitMs&gt;;&lt;processingMs&gt;;&lt;totalMs&gt;;&lt;detail&gt;
 * error    ERROR|&lt;nodeId&gt;|&lt;lamport&gt;|&lt;message&gt;
 * </pre>
 *
 * <p>STATUS is COMPLETED, FAILED or REJECTED; {@code thread} is empty when no worker ran the
 * request; {@code detail} comes last, so it may contain {@code ;} and {@code |}. Every
 * line is printable ASCII ending in {@code \n} (a trailing {@code \r} is ignored) and at
 * most {@value #MAX_LINE_LENGTH} characters. Numbers use {@link Double#toString}, so the
 * decimal separator is {@code .} in every locale. Every message carries the sender's
 * Lamport time (link L4).</p>
 *
 * <p>Pure: no sockets of its own. Covered by RequestsProtocolTest.</p>
 */
public final class RequestsProtocol {

    /** Longest accepted line, in characters, excluding the line terminator. */
    public static final int MAX_LINE_LENGTH = 1024;

    /** Every requests socket binds and connects here (docs/HANDOFF.md 6.3). */
    public static final InetAddress LOOPBACK = loopback();

    static final String WORK = "WORK";
    static final String ERROR = "ERROR";

    private RequestsProtocol() {
    }

    public static String encode(WorkRequest request) {
        return WORK + "|" + request.senderId() + "|" + request.lamportTime() + "|"
                + request.type().name() + ";" + request.payloadSize();
    }

    /** @throws ProtocolException if the line is not a valid WORK request */
    public static WorkRequest decodeRequest(String line) throws ProtocolException {
        String[] parts = split(line, 4);
        if (!WORK.equals(parts[0])) {
            throw new ProtocolException("Expected " + WORK + ", got '" + parts[0] + "'");
        }
        String[] payload = parts[3].split(";", -1);
        if (payload.length != 2) {
            throw new ProtocolException("Expected <TYPE>;<payloadSize>, got '" + parts[3] + "'");
        }
        try {
            return new WorkRequest(Integer.parseInt(parts[1]), Long.parseLong(parts[2]),
                    WorkloadType.valueOf(payload[0]), Integer.parseInt(payload[1]));
        } catch (IllegalArgumentException e) {   // includes NumberFormatException
            throw new ProtocolException("Invalid request: " + e.getMessage());
        }
    }

    /** Encodes a reply; the detail is made printable and cut so the line fits {@value #MAX_LINE_LENGTH}. */
    public static String encode(WorkReply reply) {
        requireField(reply.requestId(), "requestId");
        String thread = reply.threadName() == null ? "" : reply.threadName();
        requireField(thread, "threadName");
        String head = reply.status().name() + "|" + reply.nodeId() + "|" + reply.lamportTime() + "|"
                + reply.requestId() + ";" + thread + ";" + reply.queueWaitMillis() + ";"
                + reply.processingMillis() + ";" + reply.totalMillis() + ";";
        return head + fit(reply.detail(), MAX_LINE_LENGTH - head.length());
    }

    /** An ERROR reply; the message is made printable and cut to fit. */
    public static String error(int nodeId, long lamportTime, String message) {
        String head = ERROR + "|" + nodeId + "|" + lamportTime + "|";
        return head + fit(message == null ? "" : message, MAX_LINE_LENGTH - head.length());
    }

    /** @throws ProtocolException for an ERROR reply or a line that is not a valid reply */
    public static WorkReply decodeReply(String line) throws ProtocolException {
        String[] parts = split(line, 4);
        if (ERROR.equals(parts[0])) {
            throw new ProtocolException("Node " + parts[1] + " answered with an error: " + parts[3]);
        }
        String[] payload = parts[3].split(";", 6);
        if (payload.length != 6) {
            throw new ProtocolException("Expected 6 reply fields, got '" + parts[3] + "'");
        }
        try {
            return new WorkReply(Integer.parseInt(parts[1]), Long.parseLong(parts[2]), payload[0],
                    RequestStatus.valueOf(parts[0]), payload[1], Double.parseDouble(payload[2]),
                    Double.parseDouble(payload[3]), Double.parseDouble(payload[4]), payload[5]);
        } catch (IllegalArgumentException e) {   // includes NumberFormatException
            throw new ProtocolException("Invalid reply: " + e.getMessage());
        }
    }

    /**
     * The Lamport time a line carries (its third field), if it has one. Lets a receiver
     * apply Lamport rule 3 even to a message it then rejects, such as an ERROR reply.
     */
    public static OptionalLong lamportTimeOf(String line) {
        String[] parts = line == null ? new String[0] : line.split("\\|", 4);
        if (parts.length < 3) {
            return OptionalLong.empty();
        }
        try {
            long value = Long.parseLong(parts[2]);
            return value < 0 ? OptionalLong.empty() : OptionalLong.of(value);
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Reads one line, without its terminator.
     *
     * @return the line, or null if the stream ended before any character
     * @throws ProtocolException if the line is longer than {@value #MAX_LINE_LENGTH}
     *                           characters; the rest of the line is not read
     */
    public static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return stripCarriageReturn(line);
            }
            if (line.size() == MAX_LINE_LENGTH) {
                throw new ProtocolException("Line longer than " + MAX_LINE_LENGTH + " characters");
            }
            line.write(b);
        }
        return line.size() == 0 ? null : stripCarriageReturn(line);
    }

    /** Writes {@code line} plus {@code \n} as ASCII and flushes. */
    public static void writeLine(OutputStream out, String line) throws IOException {
        if (line.length() > MAX_LINE_LENGTH || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("not a single protocol line of at most " + MAX_LINE_LENGTH + " characters");
        }
        out.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String[] split(String line, int fields) throws ProtocolException {
        if (line == null) {
            throw new ProtocolException("Empty message");
        }
        String[] parts = line.split("\\|", fields);
        if (parts.length != fields) {
            throw new ProtocolException("Expected " + fields + " '|'-separated fields, got '" + line + "'");
        }
        return parts;
    }

    private static String stripCarriageReturn(ByteArrayOutputStream line) {
        String text = line.toString(StandardCharsets.US_ASCII);
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }

    /** Printable ASCII only (anything else becomes a space), cut to {@code max} characters. */
    private static String fit(String text, int max) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), Math.max(0, max)));
        for (int i = 0; i < text.length() && out.length() < max; i++) {
            char c = text.charAt(i);
            out.append(c >= 0x20 && c <= 0x7E ? c : ' ');
        }
        return out.toString();
    }

    private static void requireField(String value, String name) {
        if (value.indexOf(';') >= 0 || value.indexOf('|') >= 0 || !value.equals(fit(value, value.length()))) {
            throw new IllegalArgumentException(name + " must be printable ASCII without ';' or '|': " + value);
        }
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByAddress("localhost", new byte[]{127, 0, 0, 1});
        } catch (UnknownHostException e) {
            throw new IllegalStateException("cannot build 127.0.0.1", e);   // impossible: 4 bytes is valid
        }
    }
}
