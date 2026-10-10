package com.udcf.modules.rmi;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;

/**
 * Shared set-up for the E1b tests on real sockets: a standalone cluster (no Spring context, so
 * crashes never disturb other tests), the configured settings, and thread and port probes.
 *
 * <p><b>Test ports</b> (Jai's block, sub-range 28800-28899, unused by every other test; all below
 * Linux's ephemeral range 32768+): each class passes its own RMI base, so node k listens on base +
 * k. The other five bases (28850-28890) are never bound here, because only the rmi service
 * starts.</p>
 */
final class RmiTestSupport {

    /** Equal to application.yml (RmiPropertiesTest). */
    static final RmiProperties PROPERTIES = new RmiProperties(1_000_000, 1024, 50, 3000, 300);

    /** Equal to application.yml (RmiTransportPropertiesTest). */
    static final RmiTransportProperties TRANSPORT = new RmiTransportProperties(1000, 50,
            new RmiTransportProperties.SerialFilter(5, 20, 16384, 16));

    private RmiTestSupport() {
    }

    static ClusterEventBus bus() {
        return new ClusterEventBus(new EventProperties(5000, 5000), Clock.systemUTC());
    }

    static Cluster cluster(int rmiBase, int size, ClusterEventBus bus) {
        return new Cluster(new ClusterProperties(size, List.of(FAST, MEDIUM, SLOW),
                new ClusterProperties.Ports(rmiBase, 28850, 28860, 28870, 28880, 28890)), bus);
    }

    /** Starts (through ensureService) node {@code nodeId}'s RMI service with the configured settings. */
    static RmiNodeService start(Cluster cluster, int nodeId) {
        return start(cluster, nodeId, PROPERTIES, () -> null);
    }

    /**
     * Starts node {@code nodeId}'s RMI service with {@code properties}; {@code endpoints} may return
     * null to mean "a new NodeEndpointLogic for this node".
     */
    static RmiNodeService start(Cluster cluster, int nodeId, RmiProperties properties,
                                Supplier<? extends NodeEndpoint> endpoints) {
        return cluster.node(nodeId).ensureService(RmiNodeService.NAME,
                node -> new RmiNodeService(node, properties, TRANSPORT, () -> {
                    NodeEndpoint custom = endpoints.get();
                    return custom != null ? custom : NodeEndpointLogic.forNode(node, properties, System::nanoTime);
                }));
    }

    static RemoteEndpointLookup remote(Cluster cluster) {
        return RemoteEndpointLookup.of(cluster, PROPERTIES, TRANSPORT);
    }

    static RmiInvoker invoker(Cluster cluster, int senderNodeId) {
        ClusterNode sender = cluster.node(senderNodeId);
        return new RmiInvoker(sender.id(), sender.clock(), PROPERTIES, new CallTimer(System::nanoTime));
    }

    /** A REMOTE lookup that always returns {@code stub}: calls through a stub kept from earlier. */
    static EndpointLookup fixed(NodeEndpoint stub) {
        return new EndpointLookup() {
            @Override
            public NodeEndpoint lookup(int nodeId) {
                return stub;
            }

            @Override
            public CallPath path() {
                return CallPath.REMOTE;
            }
        };
    }

    /** The verdicts a call to a node that is down may get (see {@link #drainUntilRefused}). */
    static final java.util.Set<RemoteFailureKind> NODE_GONE =
            java.util.EnumSet.of(RemoteFailureKind.CONNECTION_REFUSED, RemoteFailureKind.CONNECTION_LOST);

    /** A remote action expected to fail. */
    interface RemoteAction {
        void run() throws Exception;
    }

    /**
     * Repeats {@code action} against a node that is down until it is refused, and returns the
     * verdicts seen. RMI reuses a pooled connection without checking it if it was used less than
     * about 5 ms before, so a call may first fail as CONNECTION_LOST (each such failure discards
     * one pooled connection); a refusal means RMI found no pooled connection and tried a new one,
     * so the pool of that channel is then empty and a later call after a recovery is
     * deterministic. At most 10 attempts.
     */
    static java.util.List<RemoteFailureKind> drainUntilRefused(RemoteAction action) {
        java.util.List<RemoteFailureKind> seen = new java.util.ArrayList<>();
        for (int attempt = 0; attempt < 10; attempt++) {
            RemoteFailureKind kind;
            try {
                action.run();
                throw new AssertionError("the call succeeded against a node that is down");
            } catch (Exception e) {
                kind = RemoteFailureClassifier.classify(e, CallStage.CALL);
            }
            seen.add(kind);
            if (!NODE_GONE.contains(kind)) {
                throw new AssertionError("unexpected verdict " + kind + " after " + seen);
            }
            if (kind == RemoteFailureKind.CONNECTION_REFUSED) {
                return seen;
            }
        }
        throw new AssertionError("never refused after 10 attempts: " + seen);
    }

    /** Whether a plain listener can bind 127.0.0.1:{@code port} right now (no reuse flag set). */
    static boolean canBind(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(LoopbackSocketFactory.LOOPBACK, port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** RMI's listener thread for {@code port}, named by the JDK "RMI TCP Accept-<port>". */
    static boolean acceptThreadAlive(int port) {
        return liveThreadNames().contains("RMI TCP Accept-" + port);
    }

    /**
     * RMI's client-side DGC threads for stubs made by {@code service}: one per client endpoint,
     * named by the JDK "RMI RenewClean-[127.0.0.1:<port>,<client socket factory>]". Matching the
     * factory too attributes the thread to this service; a service from an earlier test on the
     * same port has another factory id, and its thread ends only once its stubs are collected.
     */
    static long renewCleanThreads(RmiNodeService service) {
        String name = "RMI RenewClean-[127.0.0.1:" + service.port() + "," + service.socketFactory() + "]";
        return liveThreadNames().stream().filter(name::equals).count();
    }

    static Set<String> liveThreadNames() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .collect(Collectors.toSet());
    }

    /** The RMI method hash (Java RMI specification 8.3): SHA-1 of writeUTF(name + descriptor), first 8 bytes little-endian. */
    static long methodHash(Method method) throws IOException, NoSuchAlgorithmException {
        StringBuilder descriptor = new StringBuilder(method.getName()).append('(');
        for (Class<?> parameter : method.getParameterTypes()) {
            descriptor.append(parameter.descriptorString());
        }
        descriptor.append(')').append(method.getReturnType().descriptorString());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeUTF(descriptor.toString());
        byte[] sha = MessageDigest.getInstance("SHA-1").digest(bytes.toByteArray());
        long hash = 0;
        for (int i = 0; i < Math.min(8, sha.length); i++) {
            hash += ((long) (sha[i] & 0xFF)) << (i * 8);
        }
        return hash;
    }
}
