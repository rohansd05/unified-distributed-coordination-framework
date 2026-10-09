package com.udcf.modules.mapreduce;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for line-based wire protocol encoding and decoding.
 */
class MapReduceProtocolTest {

    @Test
    @DisplayName("encodes and decodes MAP request round-trip with multiline payload")
    void encodesAndDecodesMapRequestRoundTrip() throws Exception {
        String payload = "line 1\nline 2 with tabs\there\nline 3";
        String encoded = MapReduceProtocol.encodeRequest(TaskType.MAP, 1, 42L, "10", "word-count", payload);
        assertThat(encoded).startsWith("MAP|1|42|10|word-count|");

        MapReduceProtocol.Request decoded = MapReduceProtocol.decodeRequest(encoded, 1, 5);
        assertThat(decoded.taskType()).isEqualTo(TaskType.MAP);
        assertThat(decoded.senderId()).isEqualTo(1);
        assertThat(decoded.lamportTime()).isEqualTo(42L);
        assertThat(decoded.taskId()).isEqualTo("10");
        assertThat(decoded.jobName()).isEqualTo("word-count");
        assertThat(decoded.payload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("encodes and decodes REDUCE request round-trip")
    void encodesAndDecodesReduceRequestRoundTrip() throws Exception {
        String payload = "apple\t1\u00012\u00013\nbanana\t4\n";
        String encoded = MapReduceProtocol.encodeRequest(TaskType.REDUCE, 2, 99L, "25", "avg-latency-per-node", payload);
        assertThat(encoded).startsWith("REDUCE|2|99|25|avg-latency-per-node|");

        MapReduceProtocol.Request decoded = MapReduceProtocol.decodeRequest(encoded, 1, 5);
        assertThat(decoded.taskType()).isEqualTo(TaskType.REDUCE);
        assertThat(decoded.senderId()).isEqualTo(2);
        assertThat(decoded.lamportTime()).isEqualTo(99L);
        assertThat(decoded.taskId()).isEqualTo("25");
        assertThat(decoded.jobName()).isEqualTo("avg-latency-per-node");
        assertThat(decoded.payload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("encodes and decodes OK reply round-trip")
    void encodesAndDecodesOkReplyRoundTrip() throws Exception {
        String result = "apple\t3\nbanana\t4\n";
        String encoded = MapReduceProtocol.encodeOkReply(3, 105L, "10", result);
        assertThat(encoded).startsWith("OK|3|105|10|");

        MapReduceProtocol.Reply reply = MapReduceProtocol.decodeReply(encoded);
        assertThat(reply.isOk()).isTrue();
        assertThat(reply.nodeId()).isEqualTo(3);
        assertThat(reply.lamportTime()).isEqualTo(105L);
        assertThat(reply.taskId()).isEqualTo("10");
        assertThat(reply.resultPayload()).isEqualTo(result);
        assertThat(reply.errorMessage()).isNull();
    }

    @Test
    @DisplayName("encodes and decodes ERROR reply with delimiters and newlines without breaking framing")
    void encodesAndDecodesErrorReplyWithDelimiters() throws Exception {
        String error = "Task failed with exception: something | broke \n on line 2 | details here";
        String encoded = MapReduceProtocol.encodeErrorReply(2, 50L, "12", error);
        assertThat(encoded).startsWith("ERROR|2|50|12|");

        MapReduceProtocol.Reply reply = MapReduceProtocol.decodeReply(encoded);
        assertThat(reply.isOk()).isFalse();
        assertThat(reply.nodeId()).isEqualTo(2);
        assertThat(reply.lamportTime()).isEqualTo(50L);
        assertThat(reply.taskId()).isEqualTo("12");
        assertThat(reply.errorMessage()).isEqualTo(error);
        assertThat(reply.resultPayload()).isNull();
    }

    @Test
    @DisplayName("unknown task ID is encoded as - and decoded as null in reply")
    void unknownTaskIdDecodedAsNull() throws Exception {
        String encoded = MapReduceProtocol.encodeErrorReply(1, 10L, null, "Worker is busy");
        assertThat(encoded).startsWith("ERROR|1|10|-|");

        MapReduceProtocol.Reply reply = MapReduceProtocol.decodeReply(encoded);
        assertThat(reply.taskId()).isNull();
        assertThat(reply.errorMessage()).isEqualTo("Worker is busy");
    }

    @Test
    @DisplayName("rejects malformed requests with missing fields or blank values")
    void rejectsMalformedRequests() {
        assertThatThrownBy(() -> MapReduceProtocol.decodeRequest("MAP|1|2|3", 1, 5))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("expected 6 fields");

        assertThatThrownBy(() -> MapReduceProtocol.decodeRequest("MAP|not_a_number|2|3|job|payload", 1, 5))
                .isInstanceOf(ProtocolException.class);

        assertThatThrownBy(() -> MapReduceProtocol.decodeRequest("MAP|1|not_a_number|3|job|payload", 1, 5))
                .isInstanceOf(ProtocolException.class);

        assertThatThrownBy(() -> MapReduceProtocol.decodeRequest("MAP|1|2| |job|payload", 1, 5))
                .isInstanceOf(ProtocolException.class);

        assertThatThrownBy(() -> MapReduceProtocol.decodeRequest("MAP|1|2|3| |payload", 1, 5))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    @DisplayName("rejects unknown task types")
    void rejectsUnknownTaskTypes() {
        assertThatThrownBy(() -> MapReduceProtocol.decodeRequest("SHUFFLE|1|2|3|job|payload", 1, 5))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("Unknown task type");
    }

    @Test
    @DisplayName("rejects out-of-range Lamport times")
    void rejectsOutOfRangeLamportTimes() {
        assertThatThrownBy(() -> MapReduceProtocol.decodeRequest("MAP|1|-1|3|job|cGF5bG9hZA==", 1, 5))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("Out-of-range Lamport time");

        long tooBig = Long.MAX_VALUE / 2;
        assertThatThrownBy(() -> MapReduceProtocol.decodeRequest("MAP|1|" + tooBig + "|3|job|cGF5bG9hZA==", 1, 5))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("Out-of-range Lamport time");
    }

    @Test
    @DisplayName("rejects sender ID outside valid cluster node range")
    void rejectsInvalidSenderId() {
        assertThatThrownBy(() -> MapReduceProtocol.decodeRequest("MAP|0|10|3|job|cGF5bG9hZA==", 1, 5))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("Unknown sender node ID");

        assertThatThrownBy(() -> MapReduceProtocol.decodeRequest("MAP|6|10|3|job|cGF5bG9hZA==", 1, 5))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("Unknown sender node ID");
    }

    @Test
    @DisplayName("readLineBounded reads lines within cap and rejects lines exceeding cap")
    void readLineBoundedEnforcesCap() throws IOException {
        String testInput = "first line\nsecond line exceeding cap\nthird line\n";
        ByteArrayInputStream in = new ByteArrayInputStream(testInput.getBytes(StandardCharsets.UTF_8));

        String line1 = MapReduceProtocol.readLineBounded(in, 100);
        assertThat(line1).isEqualTo("first line");

        assertThatThrownBy(() -> MapReduceProtocol.readLineBounded(in, 10))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("Line exceeds maxRequestBytes limit");
    }
}
