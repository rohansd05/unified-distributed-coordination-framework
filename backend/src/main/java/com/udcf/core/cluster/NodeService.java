package com.udcf.core.cluster;

/**
 * One protocol endpoint owned by a node: a UDP or TCP socket, or an RMI export.
 *
 * <p>{@link ClusterNode} drives the lifecycle: {@code crash()} must make the node look
 * dead to every peer (close sockets, unexport), and {@code recover()} must reverse it.</p>
 *
 * <p>No dedicated test: an interface. ClusterNodeTest exercises the lifecycle contract
 * through a fake implementation.</p>
 */
public interface NodeService {

    /** Service name, unique per node: "election", "replication", ... */
    String name();

    /** Bind sockets or export RMI objects. */
    void start();

    /** Close sockets or unexport: peers see a dead node. */
    void crash();

    /** Rebind after a crash. */
    void recover();

    /** Clean shutdown. */
    void stop();

    boolean isRunning();
}
