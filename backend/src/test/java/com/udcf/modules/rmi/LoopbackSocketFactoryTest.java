package com.udcf.modules.rmi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The factory on real 127.0.0.1 sockets. Every listener here binds port 0 (an OS-chosen free
 * port), so this class uses no fixed port.
 */
@Timeout(30)
class LoopbackSocketFactoryTest {

    private final List<AutoCloseable> opened = new ArrayList<>();
    private final ExecutorService acceptor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() throws Exception {
        acceptor.shutdownNow();
        for (AutoCloseable closeable : opened) {
            closeable.close();
        }
    }

    private <T extends AutoCloseable> T track(T closeable) {
        opened.add(closeable);
        return closeable;
    }

    private static LoopbackSocketFactory factory() {
        return new LoopbackSocketFactory(1000, 2000, 50);
    }

    private Socket connect(ServerSocket server) throws IOException {
        Socket client = track(new Socket());
        client.connect(new InetSocketAddress(LoopbackSocketFactory.LOOPBACK, server.getLocalPort()), 2000);
        client.setSoTimeout(5000);
        return client;
    }

    @Test
    @DisplayName("the listener binds 127.0.0.1 only, and keeps the JDK's reuse-address default")
    void listenerBindsLoopbackOnly() throws IOException {
        ServerSocket server = track(factory().createServerSocket(0));
        boolean jdkDefault;
        try (ServerSocket plain = new ServerSocket()) {
            jdkDefault = plain.getReuseAddress();
        }

        assertThat(server.isBound()).isTrue();
        assertThat(server.getInetAddress()).isEqualTo(LoopbackSocketFactory.LOOPBACK);
        assertThat(server.getInetAddress().getHostAddress()).isEqualTo("127.0.0.1");
        assertThat(server.getLocalPort()).isPositive();
        assertThat(server.getReuseAddress()).isEqualTo(jdkDefault);
    }

    @Test
    @DisplayName("accepted connections are remembered, and closing them ends the peers' connections")
    void acceptedConnectionsAreTrackedAndClosed() throws IOException {
        LoopbackSocketFactory factory = factory();
        ServerSocket server = track(factory.createServerSocket(0));
        Socket first = connect(server);
        Socket second = connect(server);
        Socket third = connect(server);
        track(server.accept());
        track(server.accept()).close();   // closed by "RMI": pruned, never counted as open
        track(server.accept());

        assertThat(factory.openAcceptedConnections()).isEqualTo(2);
        assertThat(factory.closeAcceptedConnections()).isEqualTo(2);
        assertThat(factory.openAcceptedConnections()).isZero();
        for (Socket client : List.of(first, second, third)) {
            assertThat(readEnds(client)).as("the peer sees the connection end").isTrue();
        }
        assertThat(factory.closeAcceptedConnections()).isZero();
    }

    private static boolean readEnds(Socket client) {
        try {
            return client.getInputStream().read() == -1;
        } catch (SocketTimeoutException e) {
            return false;
        } catch (IOException e) {
            return true;   // reset: also an end
        }
    }

    @Test
    @DisplayName("the client ignores the host it is given and connects to 127.0.0.1, with the call timeout as read timeout")
    void clientIgnoresHostAndSetsTimeout() throws IOException {
        ServerSocket server = track(new ServerSocket());
        server.bind(new InetSocketAddress(LoopbackSocketFactory.LOOPBACK, 0));

        Socket client = track(new LoopbackSocketFactory(1000, 250, 50)
                .createSocket("unreachable.invalid", server.getLocalPort()));
        track(server.accept());

        assertThat(client.isConnected()).isTrue();
        assertThat(client.getInetAddress()).isEqualTo(LoopbackSocketFactory.LOOPBACK);
        assertThat(client.getSoTimeout()).isEqualTo(250);
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.getInputStream().read()).isInstanceOf(SocketTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(200));
    }

    @Test
    @DisplayName("a port nobody listens on is refused at once")
    void refusedPort() throws IOException {
        int port;
        try (ServerSocket gone = new ServerSocket()) {
            gone.bind(new InetSocketAddress(LoopbackSocketFactory.LOOPBACK, 0));
            port = gone.getLocalPort();
        }

        assertThatThrownBy(() -> factory().createSocket("127.0.0.1", port))
                .isInstanceOf(java.net.ConnectException.class);
    }

    @Test
    @DisplayName("equality is per instance id and settings: two factories with the same settings are different")
    void equalityAndHashCode() throws Exception {
        LoopbackSocketFactory one = factory();
        LoopbackSocketFactory two = factory();
        LoopbackSocketFactory copy = roundTrip(one);

        assertThat(one).isEqualTo(one).isEqualTo(copy).isNotEqualTo(two).isNotEqualTo("factory");
        assertThat(one.hashCode()).isEqualTo(copy.hashCode());
        assertThat(two.instanceId()).isGreaterThan(one.instanceId());
        assertThat(one.toString()).isEqualTo("LoopbackSocketFactory[id=" + one.instanceId()
                + ", connect=1000 ms, call=2000 ms, backlog=50]");
    }

    @Test
    @DisplayName("serialisation keeps the settings and drops the server-side tracking: a copy is client-only")
    void serialisationDropsTracking() throws Exception {
        LoopbackSocketFactory original = factory();
        ServerSocket server = track(original.createServerSocket(0));
        connect(server);
        track(server.accept());

        LoopbackSocketFactory copy = roundTrip(original);

        assertThat(copy).isEqualTo(original);
        assertThat(copy.connectTimeoutMillis()).isEqualTo(1000);
        assertThat(copy.callTimeoutMillis()).isEqualTo(2000);
        assertThat(copy.listenBacklog()).isEqualTo(50);
        assertThat(original.openAcceptedConnections()).isEqualTo(1);
        assertThat(copy.openAcceptedConnections()).isZero();
        assertThat(copy.closeAcceptedConnections()).isZero();
        assertThatThrownBy(() -> copy.createServerSocket(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a deserialised LoopbackSocketFactory is client-only");
        Socket viaCopy = track(copy.createSocket("ignored", server.getLocalPort()));
        assertThat(viaCopy.isConnected()).isTrue();
        assertThat(original.openAcceptedConnections()).isEqualTo(1);
    }

    @Test
    @DisplayName("after a listener with a thread inside accept() is closed, the wait returns and the port is free")
    void waitsForClosedListener() throws Exception {
        LoopbackSocketFactory factory = factory();
        ServerSocket server = factory.createServerSocket(0);
        int port = server.getLocalPort();
        Future<Socket> blocked = acceptor.submit(server::accept);
        await().atMost(Duration.ofSeconds(5)).until(LoopbackSocketFactoryTest::someThreadInTrackedAccept);

        assertThat(factory.awaitClosedListenersIdle(Duration.ZERO)).as("an open listener is not waited for").isTrue();
        server.close();

        assertThat(factory.awaitClosedListenersIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(RmiTestSupport.canBind(port)).isTrue();
        assertThatThrownBy(() -> blocked.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(SocketException.class);
    }

    /** Whether a thread is inside the factory's accept() (only this test's acceptor can be). */
    private static boolean someThreadInTrackedAccept() {
        return Thread.getAllStackTraces().values().stream()
                .anyMatch(frames -> java.util.Arrays.stream(frames)
                        .anyMatch(frame -> frame.getMethodName().equals("accept")
                                && frame.getClassName().endsWith("TrackingServerSocket")));
    }

    @Test
    @DisplayName("settings below 1 are rejected with their name, and from() uses the configured values")
    void settings() {
        assertThatThrownBy(() -> new LoopbackSocketFactory(0, 1, 1)).hasMessage("connectTimeoutMillis must be >= 1, was 0");
        assertThatThrownBy(() -> new LoopbackSocketFactory(1, 0, 1)).hasMessage("callTimeoutMillis must be >= 1, was 0");
        assertThatThrownBy(() -> new LoopbackSocketFactory(1, 1, 0)).hasMessage("listenBacklog must be >= 1, was 0");

        LoopbackSocketFactory configured = LoopbackSocketFactory.from(RmiTestSupport.PROPERTIES, RmiTestSupport.TRANSPORT);
        assertThat(configured.connectTimeoutMillis()).isEqualTo(1000);
        assertThat(configured.callTimeoutMillis()).isEqualTo(3000);
        assertThat(configured.listenBacklog()).isEqualTo(50);
    }

    private static LoopbackSocketFactory roundTrip(LoopbackSocketFactory factory) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(factory);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (LoopbackSocketFactory) in.readObject();
        }
    }
}
