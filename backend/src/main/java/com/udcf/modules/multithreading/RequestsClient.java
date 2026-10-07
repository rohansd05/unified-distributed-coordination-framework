package com.udcf.modules.multithreading;

import com.udcf.core.clock.LamportClock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;

/**
 * Sends one {@link WorkRequest} to a node's requests service and waits for its reply.
 *
 * <p>Lamport time (link L4): the sender's clock ticks just before sending (rule 2), and
 * the reply's time is merged into it with {@code update} (rule 3).</p>
 *
 * <p>Failures are plain {@link IOException}s, which Exp 6's balancer uses to reroute:</p>
 * <ul>
 *   <li>{@link java.net.ConnectException}: nothing is listening, the node is crashed;</li>
 *   <li>{@link java.net.SocketTimeoutException}: no reply within the timeout, a silent node;</li>
 *   <li>any other {@link IOException}: the connection closed without a reply (for example the
 *       node crashed mid-request), or, as {@link java.net.ProtocolException}, the node
 *       answered with an ERROR line.</li>
 * </ul>
 *
 * <p>Stateless apart from its timeout, so one instance can be shared by many threads.
 * Covered by RequestsClientTest and RequestsNodeServiceTest.</p>
 */
public class RequestsClient {

    private final int timeoutMillis;

    /** @param timeoutMillis connect and read timeout, at least 1 */
    public RequestsClient(int timeoutMillis) {
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("timeoutMillis must be >= 1, was " + timeoutMillis);
        }
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Runs {@code payloadSize} units of {@code type} work on the node listening on
     * 127.0.0.1:{@code port}.
     *
     * @param senderId    the sending node's id, or 0 for a cluster-level client
     * @param senderClock the sender's Lamport clock; ticked before sending, updated from the reply
     */
    public WorkReply send(int port, int senderId, LamportClock senderClock, WorkloadType type, int payloadSize)
            throws IOException {
        Objects.requireNonNull(senderClock, "senderClock must not be null");
        WorkRequest request = new WorkRequest(senderId, senderClock.tick(), type, payloadSize);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(RequestsProtocol.LOOPBACK, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            RequestsProtocol.writeLine(socket.getOutputStream(), RequestsProtocol.encode(request));

            String line = RequestsProtocol.readLine(socket.getInputStream());
            if (line == null) {
                throw new IOException("Node on port " + port + " closed the connection without replying");
            }
            RequestsProtocol.lamportTimeOf(line).ifPresent(senderClock::update);
            return RequestsProtocol.decodeReply(line);
        }
    }

    public int timeoutMillis() {
        return timeoutMillis;
    }
}
