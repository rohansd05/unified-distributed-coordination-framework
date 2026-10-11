package com.udcf.modules.rmi;

import com.udcf.modules.rmi.dto.BoundNameDto;
import com.udcf.modules.rmi.dto.CallCommand;
import com.udcf.modules.rmi.dto.CoHostingDto;
import com.udcf.modules.rmi.dto.ComparisonDto;
import com.udcf.modules.rmi.dto.FailureKindDto;
import com.udcf.modules.rmi.dto.HistoryEntryDto;
import com.udcf.modules.rmi.dto.HistoryKind;
import com.udcf.modules.rmi.dto.MethodDto;
import com.udcf.modules.rmi.dto.ParameterDto;
import com.udcf.modules.rmi.dto.RegistryDto;
import com.udcf.modules.rmi.dto.ReplyDto;
import com.udcf.modules.rmi.dto.RmiCallDto;
import com.udcf.modules.rmi.dto.RmiLimitsDto;
import com.udcf.modules.rmi.dto.RmiNodeDto;
import com.udcf.modules.rmi.dto.RmiNodeState;
import com.udcf.modules.rmi.dto.RmiOverviewDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.lang.reflect.RecordComponent;
import java.rmi.NoSuchObjectException;
import java.rmi.ServerException;
import java.rmi.UnmarshalException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.udcf.modules.rmi.RmiResultFixtures.callTimedOut;
import static com.udcf.modules.rmi.RmiResultFixtures.everyMethod;
import static com.udcf.modules.rmi.RmiResultFixtures.localSuccess;
import static com.udcf.modules.rmi.RmiResultFixtures.lookupRefused;
import static com.udcf.modules.rmi.RmiResultFixtures.remoteSuccess;
import static org.assertj.core.api.Assertions.assertThat;

/** Model to wire: every field copied, nothing invented, null never 0 (R7). */
class RmiDtoMapperTest {

    private static final Instant AT = Instant.parse("2026-10-12T10:00:00Z");

    /** Every record that reaches the wire. */
    private static final List<Class<? extends Record>> DTOS = List.of(RmiOverviewDto.class, RmiNodeDto.class,
            MethodDto.class, ParameterDto.class, RmiLimitsDto.class, CoHostingDto.class, FailureKindDto.class,
            RegistryDto.class, BoundNameDto.class, CallCommand.class, RmiCallDto.class, ReplyDto.class,
            ComparisonDto.class, HistoryEntryDto.class);

    /**
     * The only numeric components allowed to be primitive: values that always exist (ids, node
     * ids, ports, configured limits, parameter bounds, the reply's own stamp). Every measured or
     * method-specific number must be boxed, so it can be null.
     */
    private static final Map<Class<?>, Set<String>> ALWAYS_KNOWN = Map.of(
            RmiCallDto.class, Set.of("id", "fromNode", "targetNode"),
            ComparisonDto.class, Set.of("id", "fromNode", "targetNode"),
            HistoryEntryDto.class, Set.of("id"),
            ReplyDto.class, Set.of("nodeId", "lamportTime"),
            RmiNodeDto.class, Set.of("nodeId", "registryPort"),
            RegistryDto.class, Set.of("nodeId", "registryPort"),
            ParameterDto.class, Set.of("minimum", "maximum"),
            RmiLimitsDto.class, Set.of("maxHashRounds", "maxEchoLength", "historySize", "callTimeoutMillis",
                    "connectTimeoutMillis", "maxFailureMessageLength"));

    @Test
    @DisplayName("a REMOTE success copies every value: times, sizes with their basis, stub, Lamport values, reply")
    void remoteSuccessIsCopied() {
        RmiCallDto dto = RmiDtoMapper.call(7, AT, remoteSuccess(RmiCall.echo("hi")));

        assertThat(dto.id()).isEqualTo(7);
        assertThat(dto.calledAt()).isEqualTo(AT);
        assertThat(dto.method()).isEqualTo(RmiMethod.ECHO);
        assertThat(dto.fromNode()).isEqualTo(1);
        assertThat(dto.targetNode()).isEqualTo(2);
        assertThat(dto.path()).isEqualTo(CallPath.REMOTE);
        assertThat(dto.argumentSummary()).isEqualTo("text=\"hi\" (2 characters)");
        assertThat(dto.resultSummary()).isEqualTo("\"hi\" (2 characters)");
        assertThat(dto.outcome()).isEqualTo(CallOutcome.SUCCESS);
        assertThat(dto.lookupNanos()).isEqualTo(120_000L);
        assertThat(dto.roundTripNanos()).isEqualTo(840_000L);
        assertThat(dto.timeToFailureNanos()).isNull();
        assertThat(dto.argumentBytes()).isEqualTo(74L);
        assertThat(dto.resultBytes()).isEqualTo(61L);
        assertThat(dto.payloadSizeBasis()).isEqualTo(PayloadSizer.LABEL);
        assertThat(dto.stubClassName()).isEqualTo(RmiResultFixtures.STUB);
        assertThat(List.of(dto.senderLamportAtSend(), dto.replyLamport(), dto.senderLamportAfterReply()))
                .containsExactly(5L, 7L, 8L);
        assertThat(dto.failureStage()).isNull();
        assertThat(dto.failureKind()).isNull();
        assertThat(dto.failureDescription()).isNull();
        assertThat(dto.failureClass()).isNull();
        assertThat(dto.failureMessage()).isNull();
        assertThat(dto.failureCauseClass()).isNull();
        assertThat(dto.reply()).isEqualTo(new ReplyDto(RmiMethod.ECHO, 2, 7, null, null, null, null, "hi", null, null,
                null, null));
    }

    @Test
    @DisplayName("a LOCAL success marshals nothing: no sizes and no size basis; the 'stub' is the endpoint object")
    void localSuccessHasNoSizes() {
        RmiCallDto dto = RmiDtoMapper.call(3, AT, localSuccess(RmiCall.ping()));

        assertThat(dto.path()).isEqualTo(CallPath.LOCAL);
        assertThat(dto.argumentBytes()).isNull();
        assertThat(dto.resultBytes()).isNull();
        assertThat(dto.payloadSizeBasis()).isNull();
        assertThat(dto.stubClassName()).isEqualTo(NodeEndpointLogic.class.getName());
        assertThat(dto.roundTripNanos()).isEqualTo(52_000L);
    }

    @Test
    @DisplayName("a lookup failure: kind with its description, class, message, cause, time to failure; nothing else measured")
    void lookupFailureIsData() {
        RmiCallDto dto = RmiDtoMapper.call(1, AT, lookupRefused(RmiCall.ping()));

        assertThat(dto.outcome()).isEqualTo(CallOutcome.REMOTE_FAILURE);
        assertThat(dto.failureStage()).isEqualTo(CallStage.LOOKUP);
        assertThat(dto.failureKind()).isEqualTo(RemoteFailureKind.CONNECTION_REFUSED);
        assertThat(dto.failureDescription()).isEqualTo(RemoteFailureKind.CONNECTION_REFUSED.description());
        assertThat(dto.failureClass()).isEqualTo("java.rmi.ConnectException");
        assertThat(dto.failureCauseClass()).isEqualTo("java.net.ConnectException");
        assertThat(dto.failureMessage()).startsWith("Connection refused to host: 127.0.0.1");
        assertThat(dto.timeToFailureNanos()).isEqualTo(1_200_000L);
        assertThat(dto.lookupNanos()).isNull();
        assertThat(dto.roundTripNanos()).isNull();
        assertThat(dto.argumentBytes()).isNull();
        assertThat(dto.resultBytes()).isNull();
        assertThat(dto.payloadSizeBasis()).isNull();
        assertThat(dto.stubClassName()).isNull();
        assertThat(dto.senderLamportAtSend()).isNull();
        assertThat(dto.replyLamport()).isNull();
        assertThat(dto.senderLamportAfterReply()).isNull();
        assertThat(dto.reply()).isNull();
        assertThat(dto.resultSummary()).isNull();
    }

    @Test
    @DisplayName("a call failure keeps the lookup time, argument size with its basis and sent Lamport time; no round trip")
    void callFailureIsData() {
        RmiCallDto dto = RmiDtoMapper.call(1, AT, callTimedOut(RmiCall.computeHash(5)));

        assertThat(dto.failureStage()).isEqualTo(CallStage.CALL);
        assertThat(dto.failureKind()).isEqualTo(RemoteFailureKind.TIMED_OUT);
        assertThat(dto.failureDescription()).isEqualTo(RemoteFailureKind.TIMED_OUT.description());
        assertThat(dto.failureCauseClass()).isEqualTo("java.net.SocketTimeoutException");
        assertThat(dto.lookupNanos()).isEqualTo(300_000L);
        assertThat(dto.timeToFailureNanos()).isEqualTo(3_000_000_000L);
        assertThat(dto.roundTripNanos()).isNull();
        assertThat(dto.argumentBytes()).isEqualTo(80L);
        assertThat(dto.resultBytes()).isNull();
        assertThat(dto.payloadSizeBasis()).isEqualTo(PayloadSizer.LABEL);
        assertThat(dto.senderLamportAtSend()).isEqualTo(6L);
        assertThat(dto.replyLamport()).isNull();
        assertThat(dto.reply()).isNull();
    }

    @Test
    @DisplayName("CONNECTION_LOST, ARGUMENTS_REJECTED and NO_SUCH_OBJECT reach the DTO with their own descriptions")
    void newerKindsAreDescribed() {
        RmiCall ping = RmiCall.ping();
        RmiCallDto lost = RmiDtoMapper.call(1, AT, RmiResultFixtures.callConnectionLost(ping));
        RmiCallDto rejected = RmiDtoMapper.call(2, AT, RmiCallResult.callFailure(ping, 2, 1, 1, 2, 3, "S", 4,
                new ServerException("RemoteException occurred in server thread",
                        new UnmarshalException("error unmarshalling arguments")), 300));
        RmiCallDto gone = RmiDtoMapper.call(3, AT, RmiCallResult.callFailure(ping, 2, 1, 1, 2, 3, "S", 4,
                new NoSuchObjectException("no such object in table"), 300));

        assertThat(lost.failureKind()).isEqualTo(RemoteFailureKind.CONNECTION_LOST);
        assertThat(lost.failureDescription()).isEqualTo(RemoteFailureKind.CONNECTION_LOST.description());
        assertThat(lost.failureCauseClass()).isEqualTo(EOFException.class.getName());
        assertThat(rejected.failureKind()).isEqualTo(RemoteFailureKind.ARGUMENTS_REJECTED);
        assertThat(rejected.failureDescription()).isEqualTo(RemoteFailureKind.ARGUMENTS_REJECTED.description());
        assertThat(gone.failureKind()).isEqualTo(RemoteFailureKind.NO_SUCH_OBJECT);
        assertThat(gone.failureCauseClass()).isNull();
    }

    @Test
    @DisplayName("each reply keeps exactly its own fields; the other methods' fields are null")
    void repliesKeepTheirOwnFields() {
        assertThat(RmiDtoMapper.reply(new PingReply(2, 9)))
                .isEqualTo(new ReplyDto(RmiMethod.PING, 2, 9, null, null, null, null, null, null, null, null, null));
        assertThat(RmiDtoMapper.reply(new NodeInfo(3, 4, 1103, "SLOW", 77, "21.0.12")))
                .isEqualTo(new ReplyDto(RmiMethod.NODE_INFO, 3, 4, 1103, "SLOW", 77L, "21.0.12", null, null, null,
                        null, null));
        assertThat(RmiDtoMapper.reply(new EchoReply(1, 5, "a|b\nc")))
                .isEqualTo(new ReplyDto(RmiMethod.ECHO, 1, 5, null, null, null, null, "a|b\nc", null, null, null,
                        null));
        assertThat(RmiDtoMapper.reply(new HashResult(4, 6, 10, "udcf-rmi-node-4", RmiResultFixtures.DIGEST, 99)))
                .isEqualTo(new ReplyDto(RmiMethod.COMPUTE_HASH, 4, 6, null, null, null, null, null, 10,
                        "udcf-rmi-node-4", RmiResultFixtures.DIGEST, 99L));
    }

    @Test
    @DisplayName("a comparison carries both halves under one id, the overhead, the ratio, digestsMatch and the note")
    void comparisonIsCopied() {
        RmiCall hash = RmiCall.computeHash(1000);
        ComparisonDto dto = RmiDtoMapper.comparison(4, AT, localSuccess(hash), remoteSuccess(hash), "note");

        assertThat(dto.id()).isEqualTo(4);
        assertThat(dto.local().id()).isEqualTo(4);
        assertThat(dto.remote().id()).isEqualTo(4);
        assertThat(dto.local().path()).isEqualTo(CallPath.LOCAL);
        assertThat(dto.remote().path()).isEqualTo(CallPath.REMOTE);
        assertThat(dto.fromNode()).isEqualTo(1);
        assertThat(dto.targetNode()).isEqualTo(2);
        assertThat(dto.localNanos()).isEqualTo(52_000L);
        assertThat(dto.remoteNanos()).isEqualTo(840_000L);
        assertThat(dto.overheadNanos()).isEqualTo(788_000L);
        assertThat(dto.ratio()).isEqualTo(840_000d / 52_000d);
        assertThat(dto.digestsMatch()).isTrue();
        assertThat(dto.note()).isEqualTo("note");

        RmiCall ping = RmiCall.ping();
        ComparisonDto failed = RmiDtoMapper.comparison(5, AT, localSuccess(ping), lookupRefused(ping), "note");
        assertThat(failed.remoteNanos()).isNull();
        assertThat(failed.overheadNanos()).isNull();
        assertThat(failed.ratio()).isNull();
        assertThat(failed.digestsMatch()).isNull();
        assertThat(HistoryEntryDto.of(failed)).isEqualTo(new HistoryEntryDto(5, HistoryKind.COMPARISON, null, failed));
    }

    @Test
    @DisplayName("a listed registry keeps its sorted names and stub classes; a failed one has null names, never []")
    void registries() {
        RegistrySnapshot snapshot = new RegistrySnapshot(2, 1102, List.of(
                new RegistrySnapshot.BoundName("Zeta", "jdk.proxy2.$Proxy9"),
                new RegistrySnapshot.BoundName("NodeEndpoint", RmiResultFixtures.STUB)));
        RegistryDto listed = RmiDtoMapper.registry(snapshot, RmiNodeState.EXPORTED);
        assertThat(listed.reachable()).isTrue();
        assertThat(listed.names()).containsExactly(new BoundNameDto("NodeEndpoint", RmiResultFixtures.STUB),
                new BoundNameDto("Zeta", "jdk.proxy2.$Proxy9"));
        assertThat(listed.failureKind()).isNull();

        RegistryDto failed = RmiDtoMapper.registryFailure(3, 1103, RmiNodeState.CRASHED,
                new java.rmi.ConnectException("Connection refused to host: 127.0.0.1; " + "x".repeat(400),
                        new java.net.ConnectException("refused")), 50);
        assertThat(failed.reachable()).isFalse();
        assertThat(failed.names()).isNull();
        assertThat(failed.state()).isEqualTo(RmiNodeState.CRASHED);
        assertThat(failed.failureKind()).isEqualTo(RemoteFailureKind.CONNECTION_REFUSED);
        assertThat(failed.failureDescription()).isEqualTo(RemoteFailureKind.CONNECTION_REFUSED.description());
        assertThat(failed.failureClass()).isEqualTo("java.rmi.ConnectException");
        assertThat(failed.failureCauseClass()).isEqualTo("java.net.ConnectException");
        assertThat(failed.failureMessage()).hasSize(50).endsWith(DisplayText.CUT_MARKER);
    }

    @Test
    @DisplayName("null, never 0: only always-known numbers are primitive, and no DTO built from any outcome holds a 0")
    void nullNeverZero() throws Exception {
        for (Class<? extends Record> dto : DTOS) {
            for (RecordComponent component : dto.getRecordComponents()) {
                Class<?> type = component.getType();
                boolean numeric = Number.class.isAssignableFrom(type)
                        || (type.isPrimitive() && type != boolean.class && type != char.class);
                if (numeric && type.isPrimitive()) {
                    assertThat(ALWAYS_KNOWN.getOrDefault(dto, Set.of()))
                            .as("%s.%s is a primitive number, so it can never be null", dto.getSimpleName(),
                                    component.getName())
                            .contains(component.getName());
                }
            }
        }

        List<Object> built = new ArrayList<>();
        for (RmiCall call : everyMethod()) {
            built.add(RmiDtoMapper.call(1, AT, remoteSuccess(call)));
            built.add(RmiDtoMapper.call(2, AT, localSuccess(call)));
            built.add(RmiDtoMapper.call(3, AT, lookupRefused(call)));
            built.add(RmiDtoMapper.call(4, AT, callTimedOut(call)));
            built.add(HistoryEntryDto.of(RmiDtoMapper.comparison(5, AT, localSuccess(call), remoteSuccess(call), "n")));
            built.add(HistoryEntryDto.of(RmiDtoMapper.comparison(6, AT, localSuccess(call), lookupRefused(call), "n")));
        }
        built.add(RmiDtoMapper.registry(new RegistrySnapshot(2, 1102,
                List.of(new RegistrySnapshot.BoundName("NodeEndpoint", "S"))), RmiNodeState.EXPORTED));
        built.add(RmiDtoMapper.registryFailure(2, 1102, RmiNodeState.CRASHED, new java.rmi.ConnectException("x"), 300));

        Set<String> zeros = new TreeSet<>();
        for (Object dto : built) {
            collectZeros(dto, dto.getClass().getSimpleName(), zeros);
        }
        assertThat(zeros).as("numbers that are 0 although every measured value in the fixtures is non-zero").isEmpty();
    }

    private static void collectZeros(Object value, String path, Set<String> zeros) throws Exception {
        if (value == null) {
            return;
        }
        if (value instanceof Number number) {
            if (number.doubleValue() == 0) {
                zeros.add(path);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                collectZeros(list.get(i), path + "[" + i + "]", zeros);
            }
            return;
        }
        if (value instanceof Record record) {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                collectZeros(component.getAccessor().invoke(record), path + "." + component.getName(), zeros);
            }
        }
    }
}
