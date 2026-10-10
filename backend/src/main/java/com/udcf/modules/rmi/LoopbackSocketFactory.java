package com.udcf.modules.rmi;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The socket factory of Experiment 1 (step E1b): loopback only, timed, and able to close every
 * connection a node accepted.
 *
 * <p><b>Server side</b> (never leaves the JVM). Binds 127.0.0.1 only, on an unbound
 * {@link ServerSocket} bound with the configured backlog. It never calls
 * {@code setReuseAddress}: the JDK default is kept (off on Windows, so no other socket can
 * take over the port; on on Linux, so a port with TIME_WAIT connections can be bound again).
 * Every accepted socket is remembered so a crash can close it, and the threads inside
 * {@code accept()} are counted so a crash can wait until a closed listener has really released
 * its port (see {@link #awaitClosedListenersIdle}).</p>
 *
 * <p><b>Client side</b> (serialised into every stub). {@link #createSocket} ignores the host
 * argument and connects to 127.0.0.1 with {@code connect-timeout-millis}, then sets
 * {@code SO_TIMEOUT} to {@code call-timeout-millis}. That is how the per-call timeout is
 * enforced: RMI restores a non-zero timeout set by the factory after its handshake. No
 * {@code sun.rmi.*} property and no {@code RMISocketFactory.setSocketFactory}: both are JVM-wide.</p>
 *
 * <p><b>Identity.</b> RMI keeps, for the life of the JVM, the first factory that is
 * {@code equals} to a later one on the same port, and creates every later listener with that
 * first instance (measured in E1b). So each instance carries a unique {@code instanceId} that
 * takes part in {@code equals}: an {@code RmiNodeService} creates one factory and uses it for
 * every start and recovery, and a new service on the same port never inherits another one's
 * tracking. A deserialised copy keeps the id, so it stays equal to its original, and RMI
 * shares one client channel per node.</p>
 *
 * <p>The tracking state is {@code transient}: a deserialised copy is client-only.</p>
 */
public final class LoopbackSocketFactory implements RMIServerSocketFactory, RMIClientSocketFactory, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Every RMI socket binds and connects here (docs/HANDOFF.md 6.3); built as ReplicationProtocol.LOOPBACK. */
    public static final InetAddress LOOPBACK = loopback();

    private static final AtomicLong NEXT_INSTANCE_ID = new AtomicLong(1);

    private final long instanceId;
    private final int connectTimeoutMillis;
    private final int callTimeoutMillis;
    private final int listenBacklog;
    private transient ServerSide serverSide;   // null on a deserialised copy

    public LoopbackSocketFactory(int connectTimeoutMillis, int callTimeoutMillis, int listenBacklog) {
        requireAtLeastOne("connectTimeoutMillis", connectTimeoutMillis);
        requireAtLeastOne("callTimeoutMillis", callTimeoutMillis);
        requireAtLeastOne("listenBacklog", listenBacklog);
        this.instanceId = NEXT_INSTANCE_ID.getAndIncrement();
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.callTimeoutMillis = callTimeoutMillis;
        this.listenBacklog = listenBacklog;
        this.serverSide = new ServerSide();
    }

    /** A factory with the configured timeouts and backlog. */
    public static LoopbackSocketFactory from(RmiProperties properties, RmiTransportProperties transport) {
        Objects.requireNonNull(properties, "properties must not be null");
        Objects.requireNonNull(transport, "transport must not be null");
        return new LoopbackSocketFactory(transport.connectTimeoutMillis(), properties.callTimeoutMillis(),
                transport.listenBacklog());
    }

    public long instanceId() {
        return instanceId;
    }

    public int connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public int callTimeoutMillis() {
        return callTimeoutMillis;
    }

    public int listenBacklog() {
        return listenBacklog;
    }

    @Override
    public ServerSocket createServerSocket(int port) throws IOException {
        ServerSide side = requireServerSide();
        TrackingServerSocket socket = new TrackingServerSocket(side);
        try {
            socket.bind(new InetSocketAddress(LOOPBACK, port), listenBacklog);
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
        side.listeners.add(socket);
        return socket;
    }

    /** Connects to 127.0.0.1:{@code port} whatever {@code host} says, with both timeouts set. */
    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(LOOPBACK, port), connectTimeoutMillis);
            socket.setSoTimeout(callTimeoutMillis);
            return socket;
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    /**
     * Closes every connection accepted by this factory's listeners, so peers holding a pooled
     * connection see a failure instead of an answer.
     *
     * @return how many were still open
     */
    int closeAcceptedConnections() {
        ServerSide side = serverSide;
        if (side == null) {
            return 0;
        }
        int closed = 0;
        for (Socket socket : side.accepted) {
            if (!socket.isClosed()) {
                closeQuietly(socket);
                closed++;
            }
            side.accepted.remove(socket);
        }
        return closed;
    }

    /** Accepted connections that are still open. */
    int openAcceptedConnections() {
        ServerSide side = serverSide;
        return side == null ? 0 : (int) side.accepted.stream().filter(socket -> !socket.isClosed()).count();
    }

    /**
     * Waits until no thread is inside {@code accept()} of a closed listener, then forgets the
     * closed listeners. Open listeners are not waited for.
     *
     * <p>Closing a listener does not free its port at once on Linux while a thread is blocked in
     * {@code accept()}: the JDK closes the descriptor when that thread leaves. Once the count is
     * zero, the port is free.</p>
     *
     * @return false if the timeout elapsed first
     */
    boolean awaitClosedListenersIdle(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout must not be null");
        ServerSide side = serverSide;
        if (side == null) {
            return true;
        }
        long remaining = timeout.toNanos();
        side.lock.lock();
        try {
            while (side.closedListenerBusy()) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = side.idle.awaitNanos(remaining);
            }
            side.listeners.removeIf(ServerSocket::isClosed);
            return true;
        } finally {
            side.lock.unlock();
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LoopbackSocketFactory that
                && instanceId == that.instanceId
                && connectTimeoutMillis == that.connectTimeoutMillis
                && callTimeoutMillis == that.callTimeoutMillis
                && listenBacklog == that.listenBacklog;
    }

    @Override
    public int hashCode() {
        return Objects.hash(instanceId, connectTimeoutMillis, callTimeoutMillis, listenBacklog);
    }

    @Override
    public String toString() {
        return "LoopbackSocketFactory[id=" + instanceId + ", connect=" + connectTimeoutMillis + " ms, call="
                + callTimeoutMillis + " ms, backlog=" + listenBacklog + "]";
    }

    private ServerSide requireServerSide() {
        ServerSide side = serverSide;
        if (side == null) {
            throw new IllegalStateException("a deserialised LoopbackSocketFactory is client-only");
        }
        return side;
    }

    /** What the listeners of one factory remember: accepted sockets and threads inside accept(). */
    private static final class ServerSide {
        private final Set<Socket> accepted = ConcurrentHashMap.newKeySet();
        private final Set<TrackingServerSocket> listeners = ConcurrentHashMap.newKeySet();
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition idle = lock.newCondition();

        private boolean closedListenerBusy() {   // caller holds lock
            for (TrackingServerSocket listener : listeners) {
                if (listener.isClosed() && listener.inAccept > 0) {
                    return true;
                }
            }
            return false;
        }

        private void enter(TrackingServerSocket listener) {
            lock.lock();
            try {
                listener.inAccept++;
            } finally {
                lock.unlock();
            }
        }

        private void exit(TrackingServerSocket listener) {
            lock.lock();
            try {
                listener.inAccept--;
                idle.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    /** A listener that remembers what it accepts and counts the threads inside accept(). */
    private static final class TrackingServerSocket extends ServerSocket {
        private final ServerSide side;
        private int inAccept;   // guarded by side.lock

        private TrackingServerSocket(ServerSide side) throws IOException {
            super();
            this.side = side;
        }

        @Override
        public Socket accept() throws IOException {
            side.enter(this);
            try {
                Socket socket = super.accept();
                side.accepted.removeIf(Socket::isClosed);
                side.accepted.add(socket);
                if (isClosed()) {
                    // Accepted while the node went down: refuse it, as RMI's accept loop expects.
                    side.accepted.remove(socket);
                    closeQuietly(socket);
                    throw new SocketException("Socket is closed");
                }
                return socket;
            } finally {
                side.exit(this);
            }
        }
    }

    private static void requireAtLeastOne(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be >= 1, was " + value);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // closing on a failure or shutdown path; nothing more to do
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
