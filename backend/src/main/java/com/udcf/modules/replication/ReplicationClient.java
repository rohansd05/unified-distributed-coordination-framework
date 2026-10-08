package com.udcf.modules.replication;

import com.udcf.core.clock.LamportClock;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Socket;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Sends replication requests to a node's replication service, one TCP connection per exchange.
 *
 * <p>Lamport time (link L4): the sender's clock ticks just before each request is sent (rule
 * 2), and the reply's time is merged into it with {@code update} (rule 3), also when the reply
 * is an ERROR.</p>
 *
 * <p>Failures are plain {@link IOException}s, and they are failures, never stale results:</p>
 * <ul>
 *   <li>{@link java.net.ConnectException}: nothing is listening, the node is crashed;</li>
 *   <li>{@link java.net.SocketTimeoutException}: no reply within the timeout, a silent node;</li>
 *   <li>{@link ProtocolException}: the node answered ERROR, or sent a malformed reply;</li>
 *   <li>any other {@link IOException}: the connection closed without a reply (for example the
 *       node crashed mid-request).</li>
 * </ul>
 *
 * <p>Stateless apart from its timeout and guard, so one instance can be shared by many
 * threads. Covered by ReplicationClientTest and ReplicationNodeServiceTest.</p>
 */
public class ReplicationClient {

    /**
     * Lets the owning node track its outbound sockets, so its crash can close them, and
     * refuse to open one while it is down. Package-private: only the node service uses it.
     */
    interface SocketGuard {
        /** Called before connecting; throws {@link SenderDownException} if the sender is down. */
        void opened(Socket socket) throws SenderDownException;

        /** Called once the exchange is over, whatever its outcome. */
        void closed(Socket socket);
    }

    /** The sender itself is down, so nothing was sent. */
    static final class SenderDownException extends IOException {
        SenderDownException(String message) {
            super(message);
        }
    }

    private static final SocketGuard NO_GUARD = new SocketGuard() {
        @Override
        public void opened(Socket socket) {
        }

        @Override
        public void closed(Socket socket) {
        }
    };

    private final int timeoutMillis;
    private final SocketGuard guard;

    /** @param timeoutMillis connect and read timeout, at least 1 */
    public ReplicationClient(int timeoutMillis) {
        this(timeoutMillis, NO_GUARD);
    }

    ReplicationClient(int timeoutMillis, SocketGuard guard) {
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("timeoutMillis must be >= 1, was " + timeoutMillis);
        }
        this.timeoutMillis = timeoutMillis;
        this.guard = Objects.requireNonNull(guard, "guard must not be null");
    }

    public int timeoutMillis() {
        return timeoutMillis;
    }

    /** Pushes one item; the replica applies it with {@code apply(item, senderEpoch)}. */
    public AckReply replicate(int port, int senderId, LamportClock senderClock, long senderEpoch, DataItem item)
            throws IOException {
        Objects.requireNonNull(item, "item must not be null");
        List<String> request = ReplicationProtocol.replicate(senderId, tick(senderClock), senderEpoch, item);
        return exchange(port, request, senderClock, (header, in) -> ReplicationProtocol.decodeAck(header));
    }

    /**
     * Pushes one anti-entropy batch (0 to {@link ReplicationProtocol#MAX_ITEMS_PER_MESSAGE}
     * items); the replica merges it with {@link AntiEntropy#merge}.
     *
     * @throws ProtocolException also if the reply does not account for exactly the items sent
     */
    public SyncReply sync(int port, int senderId, LamportClock senderClock, long senderEpoch, List<DataItem> items)
            throws IOException {
        List<String> request = ReplicationProtocol.sync(senderId, tick(senderClock), senderEpoch, items);
        SyncReply reply = exchange(port, request, senderClock, (header, in) -> ReplicationProtocol.decodeSynced(header));
        if (reply.result().pushed() != items.size()) {
            throw new ProtocolException("Node " + reply.nodeId() + " accounted for " + reply.result().pushed()
                    + " items, " + items.size() + " were sent");
        }
        return reply;
    }

    /** Reads the version of {@code key} the replica holds right now. */
    public ReadReply read(int port, int senderId, LamportClock senderClock, String key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        List<String> request = ReplicationProtocol.read(senderId, tick(senderClock), key);
        return exchange(port, request, senderClock, ReplicationProtocol::readValue);
    }

    /** One page of the replica's store: up to {@code limit} items with keys after {@code afterKey}. */
    public DumpPage dumpPage(int port, int senderId, LamportClock senderClock, String afterKey, int limit)
            throws IOException {
        List<String> request = ReplicationProtocol.dump(senderId, tick(senderClock), afterKey, limit);
        DumpPage page = exchange(port, request, senderClock, ReplicationProtocol::readDumped);
        if (afterKey != null && !page.items().isEmpty() && page.items().get(0).key().compareTo(afterKey) <= 0) {
            throw new ProtocolException("Node " + page.nodeId() + " returned a page that does not start after the cursor");
        }
        if (page.more() && page.items().isEmpty()) {
            throw new ProtocolException("Node " + page.nodeId() + " announced more items but sent an empty page");
        }
        return page;
    }

    /**
     * The replica's whole store, read page by page ({@code pageSize} items per exchange). Not
     * one atomic snapshot; see {@link ReplicaDump}.
     */
    public ReplicaDump dump(int port, int senderId, LamportClock senderClock, int pageSize) throws IOException {
        TreeMap<String, DataItem> items = new TreeMap<>();
        String after = null;
        DumpPage page;
        do {
            page = dumpPage(port, senderId, senderClock, after, pageSize);
            page.items().forEach(item -> items.put(item.key(), item));
            if (!page.items().isEmpty()) {
                after = page.items().get(page.items().size() - 1).key();
            }
        } while (page.more());
        return new ReplicaDump(page.nodeId(), page.storeEpoch(), items);
    }

    @FunctionalInterface
    private interface ReplyReader<T> {
        T read(String header, InputStream in) throws IOException;
    }

    private static long tick(LamportClock clock) {
        return Objects.requireNonNull(clock, "senderClock must not be null").tick();
    }

    private <T> T exchange(int port, List<String> request, LamportClock senderClock, ReplyReader<T> reader)
            throws IOException {
        try (Socket socket = new Socket()) {
            guard.opened(socket);
            try {
                socket.connect(new InetSocketAddress(ReplicationProtocol.LOOPBACK, port), timeoutMillis);
                socket.setSoTimeout(timeoutMillis);
                ReplicationProtocol.writeLines(new BufferedOutputStream(socket.getOutputStream()), request);
                InputStream in = new BufferedInputStream(socket.getInputStream());
                String header = ReplicationProtocol.readLine(in);
                if (header == null) {
                    throw new IOException("Node on port " + port + " closed the connection without replying");
                }
                ReplicationProtocol.lamportTimeOf(header).ifPresent(senderClock::update);
                return reader.read(header, in);
            } finally {
                guard.closed(socket);
            }
        }
    }
}
