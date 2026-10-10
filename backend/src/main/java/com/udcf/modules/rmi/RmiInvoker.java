package com.udcf.modules.rmi;

import com.udcf.core.clock.LamportClock;

import java.util.Objects;

/**
 * Makes {@link NodeEndpoint} calls from one calling node, and measures them. Pure: the
 * transport is behind {@link EndpointLookup}.
 *
 * <p><b>The caller</b> is always a real cluster node ({@code senderNodeId >= 1}) and uses that
 * node's shared Lamport clock ({@code ClusterNode.clock()}); no client clock exists. Step E1c
 * creates one invoker per calling node ("from node").</p>
 *
 * <p><b>One call</b> ({@link #invoke}):</p>
 * <ol>
 *   <li>validates the call against the configured limits (an {@link IllegalArgumentException},
 *       before anything is sent);</li>
 *   <li>times the lookup;</li>
 *   <li>ticks the caller's clock and builds the {@link CallContext} (send rule);</li>
 *   <li>on the REMOTE path, measures the arguments' serialization size, outside the timed
 *       window;</li>
 *   <li>times the method call;</li>
 *   <li>on success, applies the receive rule to the reply ({@code clock.update}) and, on the
 *       REMOTE path, measures the reply's size, again outside the timed window;</li>
 *   <li>on a REMOTE failure, classifies it ({@link RemoteFailureClassifier}) and reports the
 *       elapsed time of the failed stage as {@code timeToFailureNanos}, never as a lookup or
 *       round-trip time.</li>
 * </ol>
 *
 * <p><b>LOCAL path.</b> A direct call has no network between caller and callee, so nothing is
 * classified: an exception from a LOCAL lookup or call is rethrown (a checked one wrapped in an
 * {@link IllegalStateException}).</p>
 */
public class RmiInvoker {

    private final int senderNodeId;
    private final LamportClock senderClock;
    private final RmiProperties properties;
    private final CallTimer timer;

    /**
     * @param senderNodeId the calling node, at least 1
     * @param senderClock  that node's shared Lamport clock
     */
    public RmiInvoker(int senderNodeId, LamportClock senderClock, RmiProperties properties, CallTimer timer) {
        if (senderNodeId < 1) {
            throw new IllegalArgumentException("senderNodeId must be >= 1 (a cluster node with its own clock), was "
                    + senderNodeId);
        }
        this.senderNodeId = senderNodeId;
        this.senderClock = Objects.requireNonNull(senderClock, "senderClock must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.timer = Objects.requireNonNull(timer, "timer must not be null");
    }

    public int senderNodeId() {
        return senderNodeId;
    }

    /** Calls {@code call} on node {@code targetNodeId} through {@code lookup}, once. */
    public RmiCallResult invoke(EndpointLookup lookup, int targetNodeId, RmiCall call) {
        Objects.requireNonNull(lookup, "lookup must not be null");
        Objects.requireNonNull(call, "call must not be null");
        if (targetNodeId < 1) {
            throw new IllegalArgumentException("targetNodeId must be >= 1, was " + targetNodeId);
        }
        call.validate(properties);
        CallPath path = Objects.requireNonNull(lookup.path(), "lookup path must not be null");
        boolean remote = path == CallPath.REMOTE;

        CallTimer.Measurement<NodeEndpoint> found = timer.measure(() -> lookup.lookup(targetNodeId));
        if (found.failed()) {
            if (!remote) {
                throw rethrow(found.failure());
            }
            // The lookup did not complete: its elapsed time is a time to failure, not a lookup time.
            return RmiCallResult.lookupFailure(call, targetNodeId, senderNodeId, found.elapsedNanos(),
                    found.failure(), properties.maxFailureMessageLength());
        }
        NodeEndpoint endpoint = found.value();
        if (endpoint == null) {
            throw new IllegalStateException("the lookup returned no endpoint for node " + targetNodeId);
        }
        String stubClassName = endpoint.getClass().getName();

        CallContext context = new CallContext(senderNodeId, senderClock.tick());
        Long argumentBytes = remote ? PayloadSizer.sizeOf(call.arguments(context).toArray()) : null;
        CallTimer.Measurement<EndpointReply> made = timer.measure(() -> call.invokeOn(endpoint, context));
        if (made.failed()) {
            if (!remote) {
                throw rethrow(made.failure());
            }
            return RmiCallResult.callFailure(call, targetNodeId, senderNodeId, found.elapsedNanos(),
                    made.elapsedNanos(), argumentBytes, stubClassName, context.senderLamport(), made.failure(),
                    properties.maxFailureMessageLength());
        }
        EndpointReply reply = made.value();
        if (reply == null) {
            throw new IllegalStateException("node " + targetNodeId + " returned no reply to " + call.method());
        }
        if (reply.nodeId() != targetNodeId) {
            throw new IllegalStateException("node " + targetNodeId + " answered as node " + reply.nodeId());
        }
        long afterReply = senderClock.update(reply.lamportTime());
        Long resultBytes = remote ? PayloadSizer.sizeOf(reply) : null;
        return RmiCallResult.success(call, targetNodeId, senderNodeId, path, reply, found.elapsedNanos(),
                made.elapsedNanos(), argumentBytes, resultBytes, stubClassName, context.senderLamport(), afterReply);
    }

    /**
     * Makes the same call once through {@code local} and then once through {@code remote}, and
     * compares them. One measurement each, local first, no warm-up call (see
     * {@link LocalRemoteComparison}).
     */
    public LocalRemoteComparison compare(EndpointLookup local, EndpointLookup remote, int targetNodeId, RmiCall call) {
        Objects.requireNonNull(local, "local must not be null");
        Objects.requireNonNull(remote, "remote must not be null");
        if (local.path() != CallPath.LOCAL || remote.path() != CallPath.REMOTE) {
            throw new IllegalArgumentException("compare needs a LOCAL and a REMOTE lookup, got "
                    + local.path() + " and " + remote.path());
        }
        RmiCallResult localResult = invoke(local, targetNodeId, call);
        RmiCallResult remoteResult = invoke(remote, targetNodeId, call);
        return LocalRemoteComparison.of(localResult, remoteResult);
    }

    private static RuntimeException rethrow(Exception failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException("local call failed: " + failure.getClass().getName(), failure);
    }
}
