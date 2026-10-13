package com.udcf.modules.rmi;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.events.ClusterEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InvalidClassException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.rmi.NoSuchObjectException;
import java.rmi.Remote;
import java.rmi.ServerException;
import java.rmi.UnmarshalException;
import java.rmi.registry.Registry;
import java.rmi.server.RemoteObjectInvocationHandler;
import java.rmi.server.RemoteRef;
import java.rmi.server.UnicastRemoteObject;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.udcf.modules.rmi.RmiTestSupport.acceptThreadAlive;
import static com.udcf.modules.rmi.RmiTestSupport.canBind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The RMI service of each node on real 127.0.0.1 sockets, on a standalone three-node cluster (no
 * Spring context, so crashes never disturb other tests). No sleeps: Awaitility with explicit
 * bounds only.
 *
 * <p>Test-only ports 28801 to 28803 (RMI base 28800; see RmiTestSupport).</p>
 */
@Timeout(60)
class RmiNodeServiceTest {

    private static final int BASE = 28800;
    private static final List<RmiCall> ALL_METHODS = List.of(RmiCall.ping(), RmiCall.nodeInfo(),
            RmiCall.echo("hello | world"), RmiCall.computeHash(1000));

    private ClusterEventBus bus;
    private Cluster cluster;
    private RemoteEndpointLookup remote;

    @BeforeEach
    void setUp() {
        bus = RmiTestSupport.bus();
        cluster = RmiTestSupport.cluster(BASE, 3, bus);
        remote = RmiTestSupport.remote(cluster);
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    private int port(int nodeId) {
        return BASE + nodeId;
    }

    private RmiNodeService start(int nodeId) {
        return RmiTestSupport.start(cluster, nodeId);
    }

    @Test
    @DisplayName("every node's registry lists NodeEndpoint, bound to a stub, read over the network")
    void registryListsNodeEndpoint() throws Exception {
        for (int nodeId = 1; nodeId <= 3; nodeId++) {
            RmiNodeService service = start(nodeId);
            assertThat(service.isRunning()).isTrue();
            assertThat(service.port()).isEqualTo(port(nodeId));
            assertThat(service.name()).isEqualTo("rmi");

            RegistrySnapshot snapshot = remote.snapshot(nodeId);

            assertThat(snapshot.nodeId()).isEqualTo(nodeId);
            assertThat(snapshot.registryPort()).isEqualTo(port(nodeId));
            assertThat(snapshot.names()).extracting(RegistrySnapshot.BoundName::name).containsExactly("NodeEndpoint");
            assertThat(snapshot.find("NodeEndpoint").orElseThrow().stubClassName())
                    .isEqualTo(remote.lookup(nodeId).getClass().getName());
        }
    }

    @Test
    @DisplayName("all four methods succeed on every live node through RmiInvoker, measured, with the filter installed")
    void allMethodsOnEveryNode() {
        for (int nodeId = 1; nodeId <= 3; nodeId++) {
            start(nodeId);
        }
        RmiInvoker invoker = RmiTestSupport.invoker(cluster, 1);
        for (int target = 1; target <= 3; target++) {
            for (RmiCall call : ALL_METHODS) {
                RmiCallResult result = invoker.invoke(remote, target, call);

                assertThat(result.outcome()).as(call + " on node " + target).isEqualTo(CallOutcome.SUCCESS);
                assertThat(result.path()).isEqualTo(CallPath.REMOTE);
                assertThat(result.reply().nodeId()).isEqualTo(target);
                assertThat(result.lookupNanos()).isNotNull().isPositive();
                assertThat(result.roundTripNanos()).isNotNull().isPositive();
                assertThat(result.argumentBytes()).isNotNull().isPositive();
                assertThat(result.resultBytes()).isNotNull().isPositive();
                assertThat(Proxy.isProxyClass(classNamed(result.stubClassName()))).isTrue();
            }
        }
        NodeInfo info = (NodeInfo) invoker.invoke(remote, 2, RmiCall.nodeInfo()).reply();
        assertThat(info.registryPort()).isEqualTo(port(2));
    }

    private static Class<?> classNamed(String name) {
        try {
            return Class.forName(name, false, NodeEndpoint.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("an echo of the longest allowed text, all 3-byte characters (U+20AC), passes the filter intact")
    void longestEchoPassesTheFilter() {
        start(1);
        String text = "\u20ac".repeat(RmiTestSupport.PROPERTIES.maxEchoLength());

        RmiCallResult result = RmiTestSupport.invoker(cluster, 2).invoke(remote, 1, RmiCall.echo(text));

        assertThat(result.outcome()).isEqualTo(CallOutcome.SUCCESS);
        assertThat(((EchoReply) result.reply()).text()).isEqualTo(text);
    }

    @Test
    @DisplayName("the looked-up object is a real stub, not the node's object, and a call crosses a socket")
    void lookupGivesARealStub() throws Exception {
        RmiNodeService service = start(1);

        NodeEndpoint stub = remote.lookup(1);
        stub.ping(new CallContext(2, 0));

        assertThat(Proxy.isProxyClass(stub.getClass())).isTrue();
        assertThat(Proxy.getInvocationHandler(stub)).isInstanceOf(RemoteObjectInvocationHandler.class);
        assertThat(stub).isNotInstanceOf(NodeEndpointLogic.class).isNotSameAs(service.localEndpoint());
        assertThat(service.socketFactory().openAcceptedConnections()).as("connections accepted by node 1").isPositive();
    }

    @Test
    @DisplayName("the stub carries 127.0.0.1 and the node's port (java.rmi.server.hostname)")
    void stubCarriesLoopback() throws Exception {
        start(2);

        String stub = remote.lookup(2).toString();

        assertThat(System.getProperty("java.rmi.server.hostname")).isEqualTo("127.0.0.1");
        assertThat(stub).contains("[127.0.0.1:" + port(2) + ",");
    }

    @Test
    @DisplayName("the local and the remote call of computeHash give the same digest")
    void localVersusRemote() {
        start(3);

        LocalRemoteComparison comparison = RmiTestSupport.invoker(cluster, 1)
                .compare(new LocalEndpointLookup(cluster), remote, 3, RmiCall.computeHash(1000));

        assertThat(comparison.localNanos()).isNotNull();
        assertThat(comparison.remoteNanos()).isNotNull();
        assertThat(comparison.digestsMatch()).isTrue();
    }

    @Test
    @DisplayName("starting a running service changes nothing")
    void startTwice() {
        AtomicInteger built = new AtomicInteger();
        RmiNodeService service = RmiTestSupport.start(cluster, 1, RmiTestSupport.PROPERTIES, () -> {
            built.incrementAndGet();
            return null;
        });
        NodeEndpoint endpoint = service.localEndpoint();
        Registry registry = service.registry();

        service.start();

        assertThat(built).hasValue(1);
        assertThat(service.localEndpoint()).isSameAs(endpoint);
        assertThat(service.registry()).isSameAs(registry);
        assertThat(RmiTestSupport.invoker(cluster, 1).invoke(remote, 1, RmiCall.ping()).outcome())
                .isEqualTo(CallOutcome.SUCCESS);
    }

    @Test
    @DisplayName("a port held by another listener fails the start, naming node and port, and leaves nothing behind")
    void startFailsWhenPortHeld() throws IOException {
        RmiNodeService service = new RmiNodeService(cluster.node(1), RmiTestSupport.PROPERTIES,
                RmiTestSupport.TRANSPORT, () -> NodeEndpointLogic.forNode(cluster.node(1), RmiTestSupport.PROPERTIES,
                System::nanoTime));
        try (ServerSocket holder = new ServerSocket()) {
            holder.bind(new InetSocketAddress(LoopbackSocketFactory.LOOPBACK, port(1)));

            assertThatThrownBy(service::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("Node 1 cannot start RMI on port " + port(1) + ": ");
            assertThat(service.isRunning()).isFalse();
            assertThatThrownBy(service::localEndpoint).hasMessage("node 1 is not running");
        }

        service.start();   // nothing was left exported: the port and the registry id are free again

        assertThat(service.isRunning()).isTrue();
        assertThat(RmiTestSupport.invoker(cluster, 2).invoke(remote, 1, RmiCall.ping()).outcome())
                .isEqualTo(CallOutcome.SUCCESS);
        service.stop();
    }

    @Test
    @DisplayName("a failed export after the registry was created unexports the registry and frees the port")
    void exportFailureRollsBack() {
        NodeEndpointLogic shared = NodeEndpointLogic.forNode(cluster.node(2), RmiTestSupport.PROPERTIES, System::nanoTime);
        RmiTestSupport.start(cluster, 2, RmiTestSupport.PROPERTIES, () -> shared);
        RmiNodeService first = new RmiNodeService(cluster.node(1), RmiTestSupport.PROPERTIES, RmiTestSupport.TRANSPORT,
                () -> shared);   // already exported by node 2: exportObject fails after createRegistry

        assertThatThrownBy(first::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Node 1 cannot start RMI on port " + port(1))
                .hasRootCauseMessage("object already exported");

        assertThat(first.isRunning()).isFalse();
        assertThat(canBind(port(1))).as("port free at once").isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() -> !acceptThreadAlive(port(1)));
        assertThat(RmiTestSupport.invoker(cluster, 3).invoke(remote, 2, RmiCall.ping()).outcome())
                .as("node 2 is unaffected").isEqualTo(CallOutcome.SUCCESS);
    }

    @Test
    @DisplayName("stop leaves no exported object, frees the port at once, ends the accept thread, and is idempotent")
    void stopLeavesNothing() {
        RmiNodeService service = start(1);
        RmiTestSupport.invoker(cluster, 2).invoke(remote, 1, RmiCall.ping());
        NodeEndpoint endpoint = service.localEndpoint();
        Registry registry = service.registry();

        service.stop();

        assertThat(canBind(port(1))).as("port free on return").isTrue();
        assertThat(service.isRunning()).isFalse();
        assertThatThrownBy(() -> UnicastRemoteObject.unexportObject(endpoint, true)).isInstanceOf(NoSuchObjectException.class);
        assertThatThrownBy(() -> UnicastRemoteObject.unexportObject(registry, true)).isInstanceOf(NoSuchObjectException.class);
        await().atMost(Duration.ofSeconds(5)).until(() -> !acceptThreadAlive(port(1)));
        service.stop();
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    @DisplayName("a crash frees the port at once, ends the accept thread and unexports the endpoint")
    void crashFreesThePort() {
        RmiNodeService service = start(2);
        RmiTestSupport.invoker(cluster, 1).invoke(remote, 2, RmiCall.ping());
        NodeEndpoint endpoint = service.localEndpoint();

        cluster.crash(2);

        assertThat(canBind(port(2))).as("port free on return").isTrue();
        assertThat(service.isRunning()).isFalse();
        assertThatThrownBy(() -> UnicastRemoteObject.unexportObject(endpoint, true)).isInstanceOf(NoSuchObjectException.class);
        await().atMost(Duration.ofSeconds(5)).until(() -> !acceptThreadAlive(port(2)));
    }

    @Test
    @DisplayName("a crash closes every accepted connection: a connected peer sees its connection end")
    void crashClosesAcceptedConnections() throws IOException {
        RmiNodeService service = start(3);
        RmiTestSupport.invoker(cluster, 1).invoke(remote, 3, RmiCall.ping());   // a pooled RMI connection
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(LoopbackSocketFactory.LOOPBACK, port(3)), 2000);
            raw.setSoTimeout(5000);
            await().atMost(Duration.ofSeconds(5)).until(() -> service.socketFactory().openAcceptedConnections() >= 2);

            service.crash();

            assertThat(service.socketFactory().openAcceptedConnections()).isZero();
            assertThat(connectionEnded(raw)).isTrue();
        }
    }

    private static boolean connectionEnded(Socket socket) {
        try {
            return socket.getInputStream().read() == -1;
        } catch (SocketTimeoutException e) {
            return false;
        } catch (IOException e) {
            return true;   // reset
        }
    }

    @Test
    @DisplayName("crash and recover leave the node's Lamport clock alone (core owns it)")
    void lifecycleLeavesTheClockAlone() {
        RmiNodeService service = start(1);
        cluster.node(1).clock().update(41);
        long before = cluster.node(1).clock().current();

        service.crash();
        assertThat(cluster.node(1).clock().current()).isEqualTo(before);
        service.recover();
        assertThat(cluster.node(1).clock().current()).isEqualTo(before);
        service.stop();
        assertThat(cluster.node(1).clock().current()).isEqualTo(before);
    }

    @Test
    @DisplayName("recover exports a new endpoint object from the supplier and the node answers again")
    void recoverExportsANewEndpoint() {
        AtomicInteger built = new AtomicInteger();
        RmiNodeService service = RmiTestSupport.start(cluster, 2, RmiTestSupport.PROPERTIES, () -> {
            built.incrementAndGet();
            return null;
        });
        NodeEndpoint before = service.localEndpoint();

        cluster.crash(2);
        assertThatThrownBy(service::localEndpoint).hasMessage("node 2 is not running");
        cluster.recover(2);

        assertThat(built).hasValue(2);
        assertThat(service.isRunning()).isTrue();
        assertThat(service.localEndpoint()).isNotSameAs(before);
        assertThat(RmiTestSupport.invoker(cluster, 1).invoke(RmiTestSupport.remote(cluster), 2, RmiCall.nodeInfo())
                .outcome()).isEqualTo(CallOutcome.SUCCESS);
    }

    @Test
    @DisplayName("the serial filter refuses a foreign argument end to end; the method never runs and the node keeps serving")
    void filterRejectsAForeignArgument() throws Exception {
        start(1);
        NodeEndpoint stub = remote.lookup(1);
        RemoteRef ref = ((RemoteObjectInvocationHandler) Proxy.getInvocationHandler(stub)).getRef();
        Method echo = NodeEndpoint.class.getMethod("echo", CallContext.class, String.class);
        long clockBefore = cluster.node(1).clock().current();

        // The real method hash of echo, but a HashMap where the CallContext belongs.
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                ref.invoke((Remote) stub, echo, new Object[]{new HashMap<String, String>(), "x"},
                        RmiTestSupport.methodHash(echo)));

        assertThat(thrown).isInstanceOf(ServerException.class)
                .cause().isInstanceOf(UnmarshalException.class)
                .hasMessageStartingWith("error unmarshalling arguments")
                .cause().isInstanceOf(InvalidClassException.class)
                .hasMessage("filter status: REJECTED");
        assertThat(RemoteFailureClassifier.classify(thrown, CallStage.CALL)).isEqualTo(RemoteFailureKind.ARGUMENTS_REJECTED);
        assertThat(cluster.node(1).clock().current()).as("the method never ran").isEqualTo(clockBefore);
        assertThat(stub.ping(new CallContext(2, 0)).nodeId()).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent crash and recover calls leave a consistent service")
    void concurrentLifecycle() throws Exception {
        RmiNodeService service = start(1);
        int threads = 4;
        CyclicBarrier gate = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> done = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int seed = t;
                done.add(pool.submit(() -> {
                    gate.await();
                    for (int i = 0; i < 10; i++) {
                        if ((i + seed) % 2 == 0) {
                            service.crash();
                        } else {
                            service.recover();
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> future : done) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        service.crash();
        assertThat(service.isRunning()).isFalse();
        assertThat(canBind(port(1))).isTrue();
        service.recover();
        assertThat(service.isRunning()).isTrue();
        assertThat(RmiTestSupport.invoker(cluster, 2).invoke(RmiTestSupport.remote(cluster), 1, RmiCall.ping()).outcome())
                .isEqualTo(CallOutcome.SUCCESS);
    }

    @Test
    @DisplayName("a service that never started has no local endpoint")
    void notStartedHasNoEndpoint() {
        RmiNodeService service = new RmiNodeService(cluster.node(3), RmiTestSupport.PROPERTIES, RmiTestSupport.TRANSPORT,
                () -> NodeEndpointLogic.forNode(cluster.node(3), RmiTestSupport.PROPERTIES, System::nanoTime));

        assertThat(service.isRunning()).isFalse();
        assertThat(service.nodeId()).isEqualTo(3);
        assertThatThrownBy(service::localEndpoint).isInstanceOf(IllegalStateException.class)
                .hasMessage("node 3 is not running");
        service.stop();   // stopping a never-started service is harmless
        assertThat(RmiNodeService.find(cluster.node(3))).isEmpty();
    }

    @Test
    @DisplayName("on() starts the service once through ensureService, and find() returns it")
    void onAndFind() {
        RmiNodeService service = RmiNodeService.on(cluster.node(2), RmiTestSupport.PROPERTIES, RmiTestSupport.TRANSPORT);

        assertThat(RmiNodeService.on(cluster.node(2), RmiTestSupport.PROPERTIES, RmiTestSupport.TRANSPORT)).isSameAs(service);
        assertThat(RmiNodeService.find(cluster.node(2))).containsSame(service);
        assertThat(cluster.node(2).runningServices()).containsExactly("rmi");
        assertThat(service.localEndpoint()).isInstanceOf(NodeEndpointLogic.class);
    }
}
