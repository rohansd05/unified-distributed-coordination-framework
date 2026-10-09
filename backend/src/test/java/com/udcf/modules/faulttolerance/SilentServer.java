package com.udcf.modules.faulttolerance;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A peer that accepts TCP connections on 127.0.0.1 and never answers, so every read from it ends
 * in the client's read timeout. Test helper, not a test. No SO_REUSEADDR; bind it only on a port
 * no service has used in the test, so no TIME_WAIT entry can block it on Linux.
 */
final class SilentServer implements AutoCloseable {

    private final ServerSocket server;
    private final Thread acceptor;
    private final List<Socket> held = new CopyOnWriteArrayList<>();
    private final AtomicInteger accepted = new AtomicInteger();

    SilentServer(int port) throws IOException {
        server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port));
        acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    held.add(server.accept());
                    accepted.incrementAndGet();
                } catch (IOException e) {
                    return;
                }
            }
        }, "test-silent-server-" + port);
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int accepted() {
        return accepted.get();
    }

    @Override
    public void close() throws Exception {
        server.close();
        for (Socket socket : held) {
            socket.close();
        }
        acceptor.join(5000);
    }
}
