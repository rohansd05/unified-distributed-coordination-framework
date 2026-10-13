package com.udcf.modules.rmi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.rmi.ConnectException;
import java.rmi.UnmarshalException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RemoteFailureDetail (added in E9a) reports a failure by exactly Experiment 1's rules. */
class RemoteFailureDetailTest {

    @Test
    @DisplayName("of: the stage, the classifier's kind, the exception class and the innermost cause class")
    void classifiesLikeExperimentOne() {
        UnmarshalException timeout = new UnmarshalException("Error unmarshaling return header",
                new SocketTimeoutException("Read timed out"));
        RemoteFailureDetail detail = RemoteFailureDetail.of(timeout, CallStage.CALL, 300);

        assertThat(detail.stage()).isEqualTo(CallStage.CALL);
        assertThat(detail.kind()).isEqualTo(RemoteFailureClassifier.classify(timeout, CallStage.CALL)).isEqualTo(RemoteFailureKind.TIMED_OUT);
        assertThat(detail.failureClass()).isEqualTo("java.rmi.UnmarshalException");
        assertThat(detail.failureCauseClass()).isEqualTo("java.net.SocketTimeoutException");
        assertThat(RemoteFailureDetail.of(new ConnectException("refused"), CallStage.LOOKUP, 300).failureCauseClass()).isNull();
    }

    @Test
    @DisplayName("of: the message is the exception's own, single-line and cut exactly as RmiCallResult does")
    void messageAsRmiCallResult() {
        ConnectException failure = new ConnectException("line one\nline two " + "x".repeat(400));
        RemoteFailureDetail detail = RemoteFailureDetail.of(failure, CallStage.LOOKUP, 300);
        assertThat(detail.failureMessage()).isEqualTo(RmiCallResult.failureMessage(failure, 300)).hasSize(300).doesNotContain("\n");
        assertThat(RemoteFailureDetail.of(new ConnectException(null), CallStage.LOOKUP, 300).failureMessage()).isNull();
    }

    @Test
    @DisplayName("observed: a failure found without an exception has no exception class, and its message is cut too")
    void observed() {
        RemoteFailureDetail detail = RemoteFailureDetail.observed(CallStage.CALL, RemoteFailureKind.OTHER,
                "reply did not match the request: no reply", 10);
        assertThat(detail.failureClass()).isNull();
        assertThat(detail.failureCauseClass()).isNull();
        assertThat(detail.failureMessage()).hasSize(10);
        assertThatThrownBy(() -> new RemoteFailureDetail(CallStage.CALL, RemoteFailureKind.OTHER, null, "a\nb", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RemoteFailureDetail.of(new ConnectException("x"), CallStage.CALL, 0))
                .hasMessage("maxMessageLength must be >= 1, was 0");
    }
}
