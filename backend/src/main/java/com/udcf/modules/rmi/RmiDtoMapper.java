package com.udcf.modules.rmi;

import com.udcf.modules.rmi.dto.BoundNameDto;
import com.udcf.modules.rmi.dto.ComparisonDto;
import com.udcf.modules.rmi.dto.RegistryDto;
import com.udcf.modules.rmi.dto.ReplyDto;
import com.udcf.modules.rmi.dto.RmiCallDto;
import com.udcf.modules.rmi.dto.RmiNodeState;

import java.time.Instant;
import java.util.Objects;

/**
 * Turns the Experiment 1 model ({@link RmiCallResult}, {@link LocalRemoteComparison},
 * {@link RegistrySnapshot}) into wire DTOs. The internal records never reach the wire.
 *
 * <p>Nothing is invented: every value is copied from what was measured, and what was not measured
 * or does not apply stays null (R7). Pure and stateless.</p>
 */
final class RmiDtoMapper {

    private RmiDtoMapper() {
    }

    /** One call; {@code id} is the history entry it belongs to. */
    static RmiCallDto call(long id, Instant calledAt, RmiCallResult result) {
        Objects.requireNonNull(calledAt, "calledAt must not be null");
        Objects.requireNonNull(result, "result must not be null");
        RemoteFailureKind kind = result.failureKind();
        boolean sized = result.argumentBytes() != null || result.resultBytes() != null;
        return new RmiCallDto(id, calledAt, result.method(), result.senderNodeId(), result.targetNodeId(), result.path(),
                result.argumentSummary(), result.resultSummary(), result.outcome(), result.failureStage(), kind,
                kind == null ? null : kind.description(), result.failureClass(), result.failureMessage(),
                result.failureCauseClass(), result.lookupNanos(), result.roundTripNanos(), result.timeToFailureNanos(),
                result.argumentBytes(), result.resultBytes(), sized ? PayloadSizer.LABEL : null, result.stubClassName(),
                result.senderLamportAtSend(), result.replyLamport(), result.senderLamportAfterReply(),
                result.reply() == null ? null : reply(result.reply()));
    }

    /** A reply, with the fields of the other methods' replies null. */
    static ReplyDto reply(EndpointReply reply) {
        Objects.requireNonNull(reply, "reply must not be null");
        return switch (reply) {
            case PingReply p -> new ReplyDto(RmiMethod.PING, p.nodeId(), p.lamportTime(),
                    null, null, null, null, null, null, null, null, null);
            case NodeInfo n -> new ReplyDto(RmiMethod.NODE_INFO, n.nodeId(), n.lamportTime(),
                    n.registryPort(), n.capacityProfile(), n.uptimeMillis(), n.javaVersion(), null, null, null, null, null);
            case EchoReply e -> new ReplyDto(RmiMethod.ECHO, e.nodeId(), e.lamportTime(),
                    null, null, null, null, e.text(), null, null, null, null);
            case HashResult h -> new ReplyDto(RmiMethod.COMPUTE_HASH, h.nodeId(), h.lamportTime(),
                    null, null, null, null, null, h.rounds(), h.seed(), h.digestHex(), h.serverNanos());
        };
    }

    /** A comparison of {@code local} and {@code remote}, both kept under {@code id}. */
    static ComparisonDto comparison(long id, Instant calledAt, RmiCallResult local, RmiCallResult remote,
                                    String note) {
        LocalRemoteComparison compared = LocalRemoteComparison.of(local, remote);
        return new ComparisonDto(id, calledAt, compared.method(), local.senderNodeId(), compared.targetNodeId(),
                call(id, calledAt, local), call(id, calledAt, remote), compared.localNanos(), compared.remoteNanos(),
                compared.overheadNanos(), compared.ratio(), compared.digestsMatch(),
                Objects.requireNonNull(note, "note must not be null"));
    }

    /** A registry that was listed completely. */
    static RegistryDto registry(RegistrySnapshot snapshot, RmiNodeState state) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return new RegistryDto(snapshot.nodeId(), snapshot.registryPort(), state, true,
                snapshot.names().stream().map(n -> new BoundNameDto(n.name(), n.stubClassName())).toList(),
                null, null, null, null, null);
    }

    /** A registry that could not be listed: the names are unknown (null), never an empty list. */
    static RegistryDto registryFailure(int nodeId, int registryPort, RmiNodeState state, Throwable failure,
                                       int maxFailureMessageLength) {
        Objects.requireNonNull(failure, "failure must not be null");
        RemoteFailureKind kind = RemoteFailureClassifier.classify(failure, CallStage.LOOKUP);
        Throwable cause = RemoteFailureClassifier.innermostCause(failure);
        return new RegistryDto(nodeId, registryPort, state, false, null, kind, kind.description(),
                failure.getClass().getName(), RmiCallResult.failureMessage(failure, maxFailureMessageLength),
                cause == null ? null : cause.getClass().getName());
    }
}
