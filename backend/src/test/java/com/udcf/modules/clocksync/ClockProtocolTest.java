package com.udcf.modules.clocksync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClockProtocolTest {

    @Test
    @DisplayName("encodes and decodes Lamport application message")
    void encodeAndDecodeLamport() throws Exception {
        ClockMessage original = ClockMessage.lamport(2, 42L, 101L, "Transaction_1_from_N2");
        byte[] bytes = ClockProtocol.encode(original);

        ClockMessage decoded = ClockProtocol.decode(bytes, bytes.length);

        assertThat(decoded.type()).isEqualTo(ClockMessageType.LAMPORT);
        assertThat(decoded.senderId()).isEqualTo(2);
        assertThat(decoded.lamportTime()).isEqualTo(42L);
        assertThat(decoded.id()).isEqualTo(101L);
        assertThat(decoded.textPayload()).isEqualTo("Transaction_1_from_N2");
    }

    @Test
    @DisplayName("decodes legacy standalone demo format <senderId>|<lamportTime>|<payload>")
    void decodeLegacyFormat() throws Exception {
        String legacy = "3|15|Transaction_5_from_N3";
        byte[] bytes = legacy.getBytes(StandardCharsets.UTF_8);

        ClockMessage decoded = ClockProtocol.decode(bytes, bytes.length);

        assertThat(decoded.type()).isEqualTo(ClockMessageType.LAMPORT);
        assertThat(decoded.senderId()).isEqualTo(3);
        assertThat(decoded.lamportTime()).isEqualTo(15L);
        assertThat(decoded.textPayload()).isEqualTo("Transaction_5_from_N3");
    }

    @Test
    @DisplayName("encodes and decodes Berkeley POLL message")
    void encodeAndDecodeBerkeleyPoll() throws Exception {
        ClockMessage poll = ClockMessage.poll(1, 10L, 5L);
        byte[] bytes = ClockProtocol.encode(poll);

        ClockMessage decoded = ClockProtocol.decode(bytes, bytes.length);

        assertThat(decoded.type()).isEqualTo(ClockMessageType.BERKELEY_POLL);
        assertThat(decoded.senderId()).isEqualTo(1);
        assertThat(decoded.lamportTime()).isEqualTo(10L);
        assertThat(decoded.id()).isEqualTo(5L);
    }

    @Test
    @DisplayName("encodes and decodes Berkeley POLL_REPLY message")
    void encodeAndDecodeBerkeleyPollReply() throws Exception {
        ClockMessage reply = ClockMessage.pollReply(2, 12L, 5L, 45L);
        byte[] bytes = ClockProtocol.encode(reply);

        ClockMessage decoded = ClockProtocol.decode(bytes, bytes.length);

        assertThat(decoded.type()).isEqualTo(ClockMessageType.BERKELEY_POLL_REPLY);
        assertThat(decoded.senderId()).isEqualTo(2);
        assertThat(decoded.lamportTime()).isEqualTo(12L);
        assertThat(decoded.id()).isEqualTo(5L);
        assertThat(decoded.payloadValue()).isEqualTo(45L);
    }

    @Test
    @DisplayName("encodes and decodes Berkeley ADJUST message")
    void encodeAndDecodeBerkeleyAdjust() throws Exception {
        ClockMessage adjust = ClockMessage.adjust(1, 14L, 5L, -35L, true);
        byte[] bytes = ClockProtocol.encode(adjust);

        ClockMessage decoded = ClockProtocol.decode(bytes, bytes.length);

        assertThat(decoded.type()).isEqualTo(ClockMessageType.BERKELEY_ADJUST);
        assertThat(decoded.senderId()).isEqualTo(1);
        assertThat(decoded.lamportTime()).isEqualTo(14L);
        assertThat(decoded.id()).isEqualTo(5L);
        assertThat(decoded.payloadValue()).isEqualTo(-35L);
        assertThat(decoded.outlier()).isTrue();
    }

    @Test
    @DisplayName("encodes and decodes Berkeley ADJUST_ACK message")
    void encodeAndDecodeBerkeleyAdjustAck() throws Exception {
        ClockMessage ack = ClockMessage.adjustAck(3, 16L, 5L, 10L);
        byte[] bytes = ClockProtocol.encode(ack);

        ClockMessage decoded = ClockProtocol.decode(bytes, bytes.length);

        assertThat(decoded.type()).isEqualTo(ClockMessageType.BERKELEY_ADJUST_ACK);
        assertThat(decoded.senderId()).isEqualTo(3);
        assertThat(decoded.lamportTime()).isEqualTo(16L);
        assertThat(decoded.id()).isEqualTo(5L);
        assertThat(decoded.payloadValue()).isEqualTo(10L);
    }

    @Test
    @DisplayName("rejects empty or null datagrams")
    void rejectsEmptyOrNullDatagram() {
        assertThatThrownBy(() -> ClockProtocol.decode(null, 0))
                .isInstanceOf(ProtocolException.class);

        assertThatThrownBy(() -> ClockProtocol.decode(new byte[0], 0))
                .isInstanceOf(ProtocolException.class);

        byte[] whitespace = "   \n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ClockProtocol.decode(whitespace, whitespace.length))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    @DisplayName("rejects malformed datagrams with unknown tokens or corrupt numbers")
    void rejectsMalformedDatagrams() {
        byte[] badType = "UNKNOWN|1|2|3".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ClockProtocol.decode(badType, badType.length))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("Unknown message type");

        byte[] badNumber = "LAMPORT|abc|2|3|payload".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ClockProtocol.decode(badNumber, badNumber.length))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("Number format error");

        byte[] truncated = "POLL|1".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ClockProtocol.decode(truncated, truncated.length))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("requires 4 parts");
    }
}
