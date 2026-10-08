package com.udcf.modules.replication;

import com.udcf.core.clock.LamportClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The replication client against scripted fake nodes on real 127.0.0.1 sockets.
 *
 * <p>Test-only ports 28421 to 28428, one per test so a listener closing on Linux never
 * overlaps the next test; 28429 is never bound (the refusal test). All below Linux's
 * ephemeral range (32768 and up) and Windows' dynamic range (49152 and up).</p>
 */
class ReplicationClientTest {

    private static final int CLOSED_PORT = 28429;

    /** A fake node: answers each connection with the responder's lines; null keeps it open and silent. */
    private static final class FakeNode implements AutoCloseable {
        private final ServerSocket server;
        private final Thread acceptThread;
        private final List<ReplicationProtocol.Request> requests = new CopyOnWriteArrayList<>();
        private final List<Socket> held = new CopyOnWriteArrayList<>();

        FakeNode(int port, Function<ReplicationProtocol.Request, List<String>> responder) throws IOException {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(ReplicationProtocol.LOOPBACK, port));
            acceptThread = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        InputStream in = new BufferedInputStream(socket.getInputStream());
                        ReplicationProtocol.Request request = ReplicationProtocol.readRequest(in);
                        requests.add(request);
                        List<String> reply = responder.apply(request);
                        if (reply == null) {
                            held.add(socket);   // silent: never answers
                        } else {
                            ReplicationProtocol.writeLines(socket.getOutputStream(), reply);
                            socket.close();
                        }
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "fake-replication-node-" + port);
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        @Override
        public void close() throws Exception {
            server.close();
            for (Socket socket : held) {
                socket.close();
            }
            acceptThread.join(5000);
            assertThat(acceptThread.isAlive()).as("fake accept thread exited").isFalse();
        }
    }

    private static DataItem item(String key) {
        return new DataItem(key, "v;~" + key, 3, 1, 1);
    }

    @Test
    @DisplayName("a port with nothing listening is a ConnectException (a crashed node)")
    void refusedIsConnectException() {
        ReplicationClient client = new ReplicationClient(2000);
        assertThatThrownBy(() -> client.read(CLOSED_PORT, 1, new LamportClock(), "k")).isInstanceOf(ConnectException.class);
    }

    @Test
    @DisplayName("a node that accepts but never answers is a SocketTimeoutException (a silent node)")
    void silentNodeTimesOut() throws Exception {
        try (FakeNode fake = new FakeNode(28421, request -> null)) {
            ReplicationClient client = new ReplicationClient(300);
            assertThatThrownBy(() -> client.replicate(28421, 1, new LamportClock(), 1, item("k")))
                    .isInstanceOf(SocketTimeoutException.class);
        }
    }

    @Test
    @DisplayName("a connection closed without a reply is an IOException, neither a timeout nor a protocol error")
    void closedWithoutReply() throws Exception {
        try (FakeNode fake = new FakeNode(28422, request -> List.of())) {
            ReplicationClient client = new ReplicationClient(2000);
            assertThatThrownBy(() -> client.replicate(28422, 1, new LamportClock(), 1, item("k")))
                    .isInstanceOf(IOException.class)
                    .isNotInstanceOf(SocketTimeoutException.class)
                    .isNotInstanceOf(ProtocolException.class)
                    .hasMessageContaining("closed the connection without replying");
        }
    }

    @Test
    @DisplayName("an ERROR reply is a ProtocolException, and its Lamport time is still merged")
    void errorReplyUpdatesClock() throws Exception {
        try (FakeNode fake = new FakeNode(28423, request -> List.of(ReplicationProtocol.error(5, 100, "nope")))) {
            LamportClock clock = new LamportClock();
            ReplicationClient client = new ReplicationClient(2000);

            assertThatThrownBy(() -> client.read(28423, 1, clock, "k"))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("nope");
            assertThat(clock.current()).isEqualTo(101);
        }
    }

    @Test
    @DisplayName("the clock ticks before sending (the request carries it) and merges the reply's time (L4)")
    void ticksBeforeSendAndMergesReply() throws Exception {
        try (FakeNode fake = new FakeNode(28424, request -> List.of(ReplicationProtocol.ack(2, 50, 1, ApplyResult.APPLIED)))) {
            LamportClock clock = new LamportClock();
            clock.update(9);   // 10

            AckReply reply = new ReplicationClient(2000).replicate(28424, 1, clock, 1, item("k"));

            assertThat(fake.requests).singleElement().satisfies(request -> {
                assertThat(request.lamportTime()).isEqualTo(11);
                assertThat(request.senderId()).isEqualTo(1);
                assertThat(request.items()).containsExactly(item("k"));
            });
            assertThat(reply).isEqualTo(new AckReply(2, 50, 1, ApplyResult.APPLIED));
            assertThat(clock.current()).isEqualTo(51);
        }
    }

    @Test
    @DisplayName("dump follows the pages with a cursor until 'more' is 0")
    void dumpFollowsPages() throws Exception {
        try (FakeNode fake = new FakeNode(28425, request -> request.key() == null
                ? ReplicationProtocol.dumped(2, 20, 1, List.of(item("a"), item("b")), true)
                : ReplicationProtocol.dumped(2, 21, 3, List.of(item("c")), false))) {

            ReplicaDump dump = new ReplicationClient(2000).dump(28425, 0, new LamportClock(), 2);

            assertThat(dump.items()).containsOnlyKeys("a", "b", "c");
            assertThat(dump.items().get("c")).isEqualTo(item("c"));
            assertThat(dump.nodeId()).isEqualTo(2);
            assertThat(dump.storeEpoch()).isEqualTo(3);
            assertThat(fake.requests).extracting(ReplicationProtocol.Request::key).containsExactly(null, "b");
            assertThat(fake.requests).extracting(ReplicationProtocol.Request::limit).containsExactly(2, 2);
        }
    }

    @Test
    @DisplayName("a SYNCED reply that does not account for every item sent is a ProtocolException")
    void syncReplyMustAccountForEveryItem() throws Exception {
        try (FakeNode fake = new FakeNode(28426,
                request -> List.of(ReplicationProtocol.synced(2, 5, 1, new AntiEntropyResult(1, 1, 0, 0, 0))))) {
            assertThatThrownBy(() -> new ReplicationClient(2000).sync(28426, 1, new LamportClock(), 1,
                    List.of(item("a"), item("b"))))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("accounted for 1 items, 2 were sent");
        }
    }

    @Test
    @DisplayName("a page announcing more items but holding none, or not starting after the cursor, is a ProtocolException")
    void badPagesRefused() throws Exception {
        try (FakeNode fake = new FakeNode(28427, request -> request.key() == null
                ? ReplicationProtocol.dumped(2, 1, 1, List.of(), true)
                : ReplicationProtocol.dumped(2, 1, 1, List.of(item("a")), false))) {
            ReplicationClient client = new ReplicationClient(2000);

            assertThatThrownBy(() -> client.dump(28427, 0, new LamportClock(), 5))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("empty page");
            assertThatThrownBy(() -> client.dumpPage(28427, 0, new LamportClock(), "b", 5))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("does not start after the cursor");
        }
    }

    @Test
    @DisplayName("a READ reply carries the replica's version, or nothing")
    void readReturnsTheVersion() throws Exception {
        try (FakeNode fake = new FakeNode(28428, request -> ReplicationProtocol.value(3, 9, 2,
                request.key().equals("present") ? Optional.of(item("present")) : Optional.empty()))) {
            ReplicationClient client = new ReplicationClient(2000);

            assertThat(client.read(28428, 0, new LamportClock(), "present").item()).contains(item("present"));
            assertThat(client.read(28428, 0, new LamportClock(), "absent").item()).isEmpty();
        }
    }

    @Test
    @DisplayName("the timeout must be at least 1 ms")
    void rejectsZeroTimeout() {
        assertThatThrownBy(() -> new ReplicationClient(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
