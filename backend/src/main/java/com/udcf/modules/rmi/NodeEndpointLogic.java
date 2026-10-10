package com.udcf.modules.rmi;

import com.udcf.core.clock.LamportClock;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeCapacity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The real work behind every {@link NodeEndpoint} method, for one node. Pure: no registry, no
 * export, no socket. Step E1b exports this same object, so a local call (directly on it) and a
 * remote call (through the stub) run exactly the same code.
 *
 * <p><b>Lamport (link L4).</b> Each method validates its arguments first (an invalid call
 * changes nothing), then applies the receive rule to the caller's time
 * ({@code clock.update(senderLamport)}), does the work, and stamps the reply with
 * {@code clock.tick()} (sending a reply is a send event). The clock is the node's shared clock
 * ({@link ClusterNode#clock()}); no second clock exists.</p>
 *
 * <p><b>computeHash</b> is real chained SHA-256: {@code d0 = UTF-8(seedFor(nodeId))},
 * {@code d(i+1) = SHA-256(d(i))} over the raw 32 digest bytes, {@code rounds} times. The seed
 * depends only on the node id, so the same call on the same node gives the same digest, locally
 * or remotely. The server time is measured around the loop only.</p>
 *
 * <p><b>Thread safety.</b> Safe for concurrent calls: the clock is atomic, and each call uses its
 * own {@link MessageDigest}.</p>
 */
public class NodeEndpointLogic implements NodeEndpoint {

    private static final String SEED_PREFIX = "udcf-rmi-node-";

    private final int nodeId;
    private final int registryPort;
    private final NodeCapacity capacity;
    private final LamportClock clock;
    private final LongSupplier nanoClock;
    private final String javaVersion;
    private final RmiProperties properties;
    private final long startedNanos;

    /**
     * @param nanoClock   monotonic time source in nanoseconds ({@code System::nanoTime} in
     *                    production); uptime counts from this constructor
     * @param javaVersion the JVM version shown by {@code nodeInfo}
     */
    public NodeEndpointLogic(int nodeId, int registryPort, NodeCapacity capacity, LamportClock clock,
                             LongSupplier nanoClock, String javaVersion, RmiProperties properties) {
        if (nodeId < 1) {
            throw new IllegalArgumentException("nodeId must be >= 1, was " + nodeId);
        }
        if (registryPort < 1 || registryPort > 65535) {
            throw new IllegalArgumentException("registryPort must be 1-65535, was " + registryPort);
        }
        this.nodeId = nodeId;
        this.registryPort = registryPort;
        this.capacity = Objects.requireNonNull(capacity, "capacity must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        this.javaVersion = Objects.requireNonNull(javaVersion, "javaVersion must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.startedNanos = nanoClock.getAsLong();
    }

    /** The logic for {@code node}: its id, its RMI port, its capacity and its shared clock. */
    public static NodeEndpointLogic forNode(ClusterNode node, RmiProperties properties, LongSupplier nanoClock) {
        Objects.requireNonNull(node, "node must not be null");
        return new NodeEndpointLogic(node.id(), node.ports().rmi(), node.capacity(), node.clock(), nanoClock,
                Runtime.version().toString(), properties);
    }

    /** The text the hash chain of node {@code nodeId} starts from. */
    public static String seedFor(int nodeId) {
        return SEED_PREFIX + nodeId;
    }

    public int nodeId() {
        return nodeId;
    }

    @Override
    public PingReply ping(CallContext context) {
        receive(context);
        return new PingReply(nodeId, clock.tick());
    }

    @Override
    public NodeInfo nodeInfo(CallContext context) {
        receive(context);
        long uptimeMillis = (nanoClock.getAsLong() - startedNanos) / 1_000_000L;
        return new NodeInfo(nodeId, clock.tick(), registryPort, capacity.name(), uptimeMillis, javaVersion);
    }

    @Override
    public EchoReply echo(CallContext context, String text) {
        requireContext(context);
        requireEchoText(text, properties);
        receive(context);
        return new EchoReply(nodeId, clock.tick(), text);
    }

    @Override
    public HashResult computeHash(CallContext context, int rounds) {
        requireContext(context);
        requireHashRounds(rounds, properties);
        receive(context);
        String seed = seedFor(nodeId);
        MessageDigest digest = sha256();
        byte[] value = seed.getBytes(StandardCharsets.UTF_8);
        long start = nanoClock.getAsLong();
        for (int i = 0; i < rounds; i++) {
            value = digest.digest(value);
        }
        long elapsed = nanoClock.getAsLong() - start;
        if (elapsed < 0) {
            throw new IllegalStateException("time source went backwards by " + (-elapsed) + " ns");
        }
        return new HashResult(nodeId, clock.tick(), rounds, seed, HexFormat.of().formatHex(value), elapsed);
    }

    /** Rejects a null text or one longer than {@code max-echo-length}; an empty text is allowed. */
    static void requireEchoText(String text, RmiProperties properties) {
        if (text == null) {
            throw new IllegalArgumentException("echo text must not be null");
        }
        if (text.length() > properties.maxEchoLength()) {
            throw new IllegalArgumentException("echo text must be at most " + properties.maxEchoLength()
                    + " characters, was " + text.length());
        }
    }

    /** Rejects rounds outside 1 to {@code max-hash-rounds}. */
    static void requireHashRounds(int rounds, RmiProperties properties) {
        if (rounds < 1 || rounds > properties.maxHashRounds()) {
            throw new IllegalArgumentException("rounds must be 1-" + properties.maxHashRounds() + ", was " + rounds);
        }
    }

    private void receive(CallContext context) {
        requireContext(context);
        clock.update(context.senderLamport());
    }

    private static void requireContext(CallContext context) {
        if (context == null) {
            throw new IllegalArgumentException("call context must not be null");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK spec; reaching here means a broken runtime.
            throw new IllegalStateException("SHA-256 unavailable in this JVM", e);
        }
    }
}
