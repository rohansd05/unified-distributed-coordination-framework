package com.udcf.modules.multithreading;

import com.udcf.core.clock.LamportClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives RequestsClient against scripted one-shot servers on real 127.0.0.1 sockets.
 *
 * <p>Test-only ports 21801 to 21810 (below 32768, outside the Linux and Windows ephemeral
 * ranges, and apart from every other test class).</p>
 */
class RequestsClientTest {

    // One port per scripted test, so a connection left open by one test never meets the next bind.
    private static final int LAMPORT_PORT = 21801;
    private static final int ERROR_PORT = 21802;
    private static final int SILENT_PORT = 21803;
    private static final int CLOSING_PORT = 21804;
    private static final int UNUSED_PORT = 21810;

    private ServerSocket server;

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    /** Accepts one connection, reads one line, and answers with {@code script} (null: send nothing). */
    private CompletableFuture<String> serveOnce(int port, UnaryOperator<String> script, boolean closeWithoutReply)
            throws IOException {
        server = new ServerSocket();
        server.bind(new InetSocketAddress(RequestsProtocol.LOOPBACK, port));
        return CompletableFuture.supplyAsync(() -> {
            try (Socket client = server.accept()) {
                String received = RequestsProtocol.readLine(client.getInputStream());
                if (closeWithoutReply) {
                    return received;
                }
                String answer = script.apply(received);
                if (answer == null) {
                    Thread.sleep(2000);   // a silent node
                } else {
                    RequestsProtocol.writeLine(client.getOutputStream(), answer);
                }
                return received;
            } catch (IOException e) {
                return "server error: " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted";
            }
        });
    }

    @Test
    @DisplayName("ticks the sender clock before sending and merges the reply's Lamport time after")
    void appliesLamportRules() throws Exception {
        CompletableFuture<String> received = serveOnce(LAMPORT_PORT,
                line -> "COMPLETED|2|50|ab12cd34;udcf-worker-n2-1;0.5;10.0;10.5;hash=beef", false);
        LamportClock sender = new LamportClock();
        sender.update(6);   // sender is at 7

        WorkReply reply = new RequestsClient(2000).send(LAMPORT_PORT, 4, sender, WorkloadType.CPU_HASH, 5);

        assertThat(received.get(5, TimeUnit.SECONDS)).isEqualTo("WORK|4|8|CPU_HASH;5");   // rule 2: 7 + 1
        assertThat(sender.current()).isEqualTo(51);                                         // rule 3: max(8, 50) + 1
        assertThat(reply.nodeId()).isEqualTo(2);
        assertThat(reply.status()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(reply.threadName()).isEqualTo("udcf-worker-n2-1");
        assertThat(reply.detail()).isEqualTo("hash=beef");
    }

    @Test
    @DisplayName("an ERROR reply throws ProtocolException, after merging its Lamport time")
    void errorReplyThrows() throws Exception {
        serveOnce(ERROR_PORT, line -> "ERROR|2|30|Expected WORK", false);
        LamportClock sender = new LamportClock();

        assertThatThrownBy(() -> new RequestsClient(2000).send(ERROR_PORT, 0, sender, WorkloadType.CPU_HASH, 5))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("Expected WORK");
        assertThat(sender.current()).isEqualTo(31);
    }

    @Test
    @DisplayName("a node that never answers produces SocketTimeoutException within the timeout")
    void silentNodeTimesOut() throws Exception {
        serveOnce(SILENT_PORT, line -> null, false);
        long start = System.nanoTime();

        assertThatThrownBy(() -> new RequestsClient(300).send(SILENT_PORT, 0, new LamportClock(), WorkloadType.CPU_HASH, 5))
                .isInstanceOf(SocketTimeoutException.class);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1500);
    }

    @Test
    @DisplayName("a connection closed without a reply produces IOException")
    void closedConnectionThrows() throws Exception {
        serveOnce(CLOSING_PORT, line -> "unused", true);

        assertThatThrownBy(() -> new RequestsClient(2000).send(CLOSING_PORT, 0, new LamportClock(), WorkloadType.CPU_HASH, 5))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(SocketTimeoutException.class)
                .hasMessageContaining("without replying");
    }

    @Test
    @DisplayName("nothing listening produces ConnectException, the crashed-node signal")
    void nothingListeningIsRefused() {
        assertThatThrownBy(() -> new RequestsClient(2000).send(UNUSED_PORT, 0, new LamportClock(), WorkloadType.CPU_HASH, 5))
                .isInstanceOf(ConnectException.class);
    }

    @Test
    @DisplayName("refuses a timeout below 1 ms and an invalid payload before connecting")
    void validatesArguments() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RequestsClient(0));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RequestsClient(2000).send(UNUSED_PORT, 0, new LamportClock(), WorkloadType.CPU_HASH, 5001));
    }
}
