package com.udcf.modules.rmi;

import com.udcf.core.cluster.ClusterNode;

import java.io.ObjectInputFilter;
import java.rmi.Remote;

/**
 * One more remote object a node's {@link RmiNodeService} exports and binds beside its
 * {@link NodeEndpoint} (added in step E9b for Experiment 9's {@code CollectiveEndpoint}).
 *
 * <p><b>Why the RMI service exports it.</b> RMI keeps the first equal socket factory per port for
 * the life of the JVM (measured in E1b), so the registry and every object on a node's port must
 * share the one {@link LoopbackSocketFactory} instance the service owns. Another service with its
 * own factory would not work. A module supplies its export as a Spring bean implementing this
 * interface; the rmi package never depends on that module.</p>
 *
 * <p><b>Lifecycle.</b> On every start and recovery the service calls {@link #newEndpoint} for a
 * fresh object (a recovered node is a new process and has lost what it held), exports it on the
 * node's RMI port with {@link #filter()} and binds it under {@link #boundName()}. Crash and stop
 * unexport it with the node's other objects.</p>
 *
 * <p>No dedicated test: an interface (R6); RmiNodeServiceExtrasTest covers its use.</p>
 */
public interface RmiExtraExport {

    /** The name in the node's registry; must differ from {@link NodeEndpoint#BOUND_NAME} and every other extra. */
    String boundName();

    /** The remote interface the exported object implements. */
    Class<? extends Remote> remoteType();

    /** A new object for {@code node}; called on every start and every recovery. */
    Remote newEndpoint(ClusterNode node);

    /** The JEP 290 filter for the arguments this object reads. */
    ObjectInputFilter filter();
}
