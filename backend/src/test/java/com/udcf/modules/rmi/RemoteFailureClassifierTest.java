package com.udcf.modules.rmi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.EOFException;
import java.io.IOException;
import java.io.NotSerializableException;
import java.net.SocketTimeoutException;
import java.rmi.ConnectException;
import java.rmi.ConnectIOException;
import java.rmi.MarshalException;
import java.rmi.NoSuchObjectException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.ServerError;
import java.rmi.ServerException;
import java.rmi.UnknownHostException;
import java.rmi.UnmarshalException;
import java.util.stream.Stream;

import static com.udcf.modules.rmi.RemoteFailureKind.CONNECTION_FAILED;
import static com.udcf.modules.rmi.RemoteFailureKind.CONNECTION_REFUSED;
import static com.udcf.modules.rmi.RemoteFailureKind.MARSHAL_FAILED;
import static com.udcf.modules.rmi.RemoteFailureKind.METHOD_THREW;
import static com.udcf.modules.rmi.RemoteFailureKind.NOT_BOUND;
import static com.udcf.modules.rmi.RemoteFailureKind.NO_SUCH_OBJECT;
import static com.udcf.modules.rmi.RemoteFailureKind.OTHER;
import static com.udcf.modules.rmi.RemoteFailureKind.TIMED_OUT;
import static com.udcf.modules.rmi.RemoteFailureKind.UNMARSHAL_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every kind, from the real exception classes a failed RMI call raises (built here; no network). */
class RemoteFailureClassifierTest {

    static Stream<Arguments> exceptions() {
        return Stream.of(
                Arguments.of("RMI connection refused (crashed node)",
                        new ConnectException("Connection refused to host: 127.0.0.1",
                                new java.net.ConnectException("Connection refused")), CONNECTION_REFUSED),
                Arguments.of("bare java.net connection refused",
                        new java.net.ConnectException("Connection refused"), CONNECTION_REFUSED),
                Arguments.of("connect I/O error",
                        new ConnectIOException("error during JRMP connection establishment", new EOFException()),
                        CONNECTION_FAILED),
                Arguments.of("connect timeout",
                        new ConnectIOException("Exception creating connection",
                                new SocketTimeoutException("Connect timed out")), TIMED_OUT),
                Arguments.of("read timeout",
                        new UnmarshalException("Error unmarshaling return header",
                                new SocketTimeoutException("Read timed out")), TIMED_OUT),
                Arguments.of("unexported object", new NoSuchObjectException("no such object in table"),
                        NO_SUCH_OBJECT),
                Arguments.of("not bound", new NotBoundException("NodeEndpoint"), NOT_BOUND),
                Arguments.of("marshal", new MarshalException("error marshalling arguments",
                        new NotSerializableException("x")), MARSHAL_FAILED),
                Arguments.of("unmarshal", new UnmarshalException("error unmarshalling return", new EOFException()),
                        UNMARSHAL_FAILED),
                Arguments.of("server exception, even with a timeout inside",
                        new ServerException("RemoteException occurred in server thread",
                                new RemoteException("inner", new SocketTimeoutException("Read timed out"))),
                        METHOD_THREW),
                Arguments.of("server error", new ServerError("Error occurred in server thread",
                        new StackOverflowError()), METHOD_THREW),
                Arguments.of("other remote exception", new RemoteException("something else"), OTHER),
                Arguments.of("unknown host", new UnknownHostException("nowhere"), OTHER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("exceptions")
    @DisplayName("each RMI failure maps to its kind, at either stage")
    void classifies(String label, Exception failure, RemoteFailureKind expected) {
        assertThat(RemoteFailureClassifier.classify(failure, CallStage.CALL)).isEqualTo(expected);
        assertThat(RemoteFailureClassifier.classify(failure, CallStage.LOOKUP)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a non-remote exception is the method's own at CALL, and OTHER at LOOKUP")
    void nonRemoteDependsOnStage() {
        IllegalArgumentException thrown = new IllegalArgumentException("rounds must be 1-1000, was 0");
        assertThat(RemoteFailureClassifier.classify(thrown, CallStage.CALL)).isEqualTo(METHOD_THREW);
        assertThat(RemoteFailureClassifier.classify(thrown, CallStage.LOOKUP)).isEqualTo(OTHER);
        assertThat(RemoteFailureClassifier.classify(new IOException("x"), CallStage.LOOKUP)).isEqualTo(OTHER);
    }

    @Test
    @DisplayName("a cyclic cause chain terminates, and the innermost cause is found")
    void cyclicChainIsSafe() {
        // RemoteException(String) fixes its cause to null, so the cycle is built from IOExceptions.
        IOException outer = new IOException("outer");
        IOException inner = new IOException("inner");
        outer.initCause(inner);
        inner.initCause(outer);

        assertThat(RemoteFailureClassifier.classify(outer, CallStage.LOOKUP)).isEqualTo(OTHER);
        assertThat(RemoteFailureClassifier.innermostCause(outer)).isNotNull();
        assertThat(RemoteFailureClassifier.innermostCause(new RemoteException("alone"))).isNull();
        assertThat(RemoteFailureClassifier.innermostCause(
                new ConnectException("refused", new java.net.ConnectException("refused"))))
                .isInstanceOf(java.net.ConnectException.class);
    }

    @Test
    @DisplayName("every kind has a plain-English description")
    void everyKindIsDescribed() {
        for (RemoteFailureKind kind : RemoteFailureKind.values()) {
            assertThat(kind.description()).as(kind.name()).isNotBlank().endsWith(".");
        }
    }

    @Test
    @DisplayName("null failure or stage is rejected")
    void nullsRejected() {
        assertThatThrownBy(() -> RemoteFailureClassifier.classify(null, CallStage.CALL))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RemoteFailureClassifier.classify(new RemoteException(), null))
                .isInstanceOf(NullPointerException.class);
    }
}
