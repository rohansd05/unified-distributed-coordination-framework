package com.udcf.modules.multithreading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Guards the requests wire format, its limits and its validation. */
class RequestsProtocolTest {

    private static WorkReply reply(String detail) {
        return new WorkReply(2, 41, "ab12cd34", RequestStatus.COMPLETED, "udcf-worker-n2-1",
                1.25, 30.5, 31.75, detail);
    }

    @Test
    @DisplayName("a request survives encoding and decoding")
    void requestRoundTrip() throws ProtocolException {
        WorkRequest request = new WorkRequest(0, 17, WorkloadType.MIXED, 250);

        String line = RequestsProtocol.encode(request);

        assertThat(line).isEqualTo("WORK|0|17|MIXED;250");
        assertThat(RequestsProtocol.decodeRequest(line)).isEqualTo(request);
    }

    @Test
    @DisplayName("a reply survives encoding and decoding, including a detail with ';' and '|'")
    void replyRoundTrip() throws ProtocolException {
        WorkReply reply = reply("hash=dead; waited=4ms | ok");

        String line = RequestsProtocol.encode(reply);

        assertThat(line).startsWith("COMPLETED|2|41|ab12cd34;udcf-worker-n2-1;1.25;30.5;31.75;");
        assertThat(RequestsProtocol.decodeReply(line)).isEqualTo(reply);
    }

    @Test
    @DisplayName("a rejected reply has no thread, and decodes back to a null thread name")
    void rejectedReplyHasNoThread() throws ProtocolException {
        WorkReply rejected = new WorkReply(3, 9, "ff00ff00", RequestStatus.REJECTED, null, 0, 0, 0,
                "Queue full - node at capacity");

        WorkReply decoded = RequestsProtocol.decodeReply(RequestsProtocol.encode(rejected));

        assertThat(decoded.threadName()).isNull();
        assertThat(decoded).isEqualTo(rejected);
    }

    @Test
    @DisplayName("line breaks and non-ASCII characters in the detail become spaces")
    void detailIsMadePrintable() throws ProtocolException {
        String line = RequestsProtocol.encode(reply("first\nsecond\r\tthird é"));

        assertThat(line).doesNotContain("\n").doesNotContain("\r");
        assertThat(RequestsProtocol.decodeReply(line).detail()).isEqualTo("first second  third  ");
    }

    @Test
    @DisplayName("a very long detail or error message is cut so the line fits 1024 characters")
    void longTextIsCut() {
        String huge = "x".repeat(5000);

        assertThat(RequestsProtocol.encode(reply(huge))).hasSize(RequestsProtocol.MAX_LINE_LENGTH);
        assertThat(RequestsProtocol.error(1, 5, huge)).hasSize(RequestsProtocol.MAX_LINE_LENGTH)
                .startsWith("ERROR|1|5|");
    }

    @ParameterizedTest(name = "rejects \"{0}\"")
    @ValueSource(strings = {
            "", "WORK", "WORK|0|1", "PING|0|1|CPU_HASH;5", "WORK|0|1|CPU_HASH", "WORK|0|1|CPU_HASH;5;6",
            "WORK|0|1|SLEEP;5", "WORK|x|1|CPU_HASH;5", "WORK|0|y|CPU_HASH;5", "WORK|0|1|CPU_HASH;z",
            "WORK|0|1|CPU_HASH;0", "WORK|0|1|CPU_HASH;5001", "WORK|-1|1|CPU_HASH;5", "WORK|0|-1|CPU_HASH;5"
    })
    @DisplayName("an invalid request line is refused with ProtocolException")
    void rejectsInvalidRequests(String line) {
        assertThatThrownBy(() -> RequestsProtocol.decodeRequest(line)).isInstanceOf(ProtocolException.class);
    }

    @Test
    @DisplayName("a request accepts the payload limits 1 and 5000")
    void acceptsPayloadLimits() throws ProtocolException {
        assertThat(RequestsProtocol.decodeRequest("WORK|3|1|CPU_HASH;1").payloadSize()).isEqualTo(1);
        assertThat(RequestsProtocol.decodeRequest("WORK|3|1|IO_SIMULATED;5000").payloadSize()).isEqualTo(5000);
    }

    @Test
    @DisplayName("an ERROR reply, or a reply that is not one, is refused with ProtocolException")
    void rejectsErrorAndInvalidReplies() {
        assertThatThrownBy(() -> RequestsProtocol.decodeReply("ERROR|2|7|Expected WORK"))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("Expected WORK");
        assertThatThrownBy(() -> RequestsProtocol.decodeReply("QUEUED|2|7|id;t;0;0;0;"))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> RequestsProtocol.decodeReply("COMPLETED|2|7|id;t;0;0"))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> RequestsProtocol.decodeReply("COMPLETED|0|7|id;t;0;0;0;"))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> RequestsProtocol.decodeReply("COMPLETED|2|7|id;t;zero;0;0;"))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    @DisplayName("lamportTimeOf reads the third field, even of an ERROR line, and is empty otherwise")
    void readsLamportTime() {
        assertThat(RequestsProtocol.lamportTimeOf("ERROR|2|77|bad")).hasValue(77);
        assertThat(RequestsProtocol.lamportTimeOf("COMPLETED|2|12|id;t;0;0;0;")).hasValue(12);
        assertThat(RequestsProtocol.lamportTimeOf("garbage")).isEmpty();
        assertThat(RequestsProtocol.lamportTimeOf("ERROR|2|-4|bad")).isEmpty();
        assertThat(RequestsProtocol.lamportTimeOf(null)).isEmpty();
    }

    @Test
    @DisplayName("numbers use '.' as the decimal separator whatever the default locale")
    void numbersIgnoreTheLocale() throws ProtocolException {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            String line = RequestsProtocol.encode(reply("ok"));

            assertThat(line).contains(";1.25;30.5;31.75;");
            assertThat(RequestsProtocol.decodeReply(line).totalMillis()).isEqualTo(31.75);
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    @DisplayName("readLine returns lines without their terminator, accepts CRLF, and null at the end")
    void readsLines() throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream("first\r\nsecond\nlast".getBytes(StandardCharsets.US_ASCII));

        assertThat(RequestsProtocol.readLine(in)).isEqualTo("first");
        assertThat(RequestsProtocol.readLine(in)).isEqualTo("second");
        assertThat(RequestsProtocol.readLine(in)).isEqualTo("last");
        assertThat(RequestsProtocol.readLine(in)).isNull();
    }

    @Test
    @DisplayName("readLine accepts 1024 characters and refuses 1025")
    void readLineEnforcesTheMaximum() throws IOException {
        String longest = "a".repeat(RequestsProtocol.MAX_LINE_LENGTH);
        assertThat(RequestsProtocol.readLine(stream(longest + "\n"))).isEqualTo(longest);

        assertThatThrownBy(() -> RequestsProtocol.readLine(stream(longest + "a\n")))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("1024");
    }

    @Test
    @DisplayName("writeLine ends the line with LF only, and refuses a line break or an oversized line")
    void writesLines() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        RequestsProtocol.writeLine(out, "WORK|0|1|CPU_HASH;5");

        assertThat(out.toString(StandardCharsets.US_ASCII)).isEqualTo("WORK|0|1|CPU_HASH;5\n");
        assertThatIllegalArgumentException().isThrownBy(() -> RequestsProtocol.writeLine(out, "a\nb"));
        assertThatIllegalArgumentException().isThrownBy(() -> RequestsProtocol.writeLine(out, "a".repeat(1025)));
    }

    @Test
    @DisplayName("records refuse values the protocol cannot carry")
    void recordsValidate() {
        assertThatIllegalArgumentException().isThrownBy(() -> new WorkRequest(-1, 0, WorkloadType.CPU_HASH, 5));
        assertThatIllegalArgumentException().isThrownBy(() -> new WorkRequest(0, -1, WorkloadType.CPU_HASH, 5));
        assertThatIllegalArgumentException().isThrownBy(() -> new WorkRequest(0, 0, WorkloadType.CPU_HASH, 0));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new WorkReply(1, 1, "id", RequestStatus.PROCESSING, null, 0, 0, 0, ""));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new WorkReply(1, 1, " ", RequestStatus.COMPLETED, null, 0, 0, 0, ""));
        assertThatIllegalArgumentException().isThrownBy(() ->
                RequestsProtocol.encode(new WorkReply(1, 1, "a;b", RequestStatus.COMPLETED, null, 0, 0, 0, "")));
        assertThat(new WorkReply(1, 1, "id", RequestStatus.FAILED, "", 0, 0, 0, null))
                .satisfies(reply -> {
                    assertThat(reply.threadName()).isNull();
                    assertThat(reply.detail()).isEmpty();
                });
    }

    @Test
    @DisplayName("sockets use 127.0.0.1")
    void loopbackIsIpv4() {
        assertThat(RequestsProtocol.LOOPBACK.getHostAddress()).isEqualTo("127.0.0.1");
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII));
    }
}
