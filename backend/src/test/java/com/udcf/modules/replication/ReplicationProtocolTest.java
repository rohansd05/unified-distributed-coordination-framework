package com.udcf.modules.replication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The replication wire format, without sockets. */
class ReplicationProtocolTest {

    private static InputStream stream(List<String> lines) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ReplicationProtocol.writeLines(out, lines);
        return new ByteArrayInputStream(out.toByteArray());
    }

    private static InputStream raw(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static DataItem item(String key, String value) {
        return new DataItem(key, value, 7, 2, 1);
    }

    @Test
    @DisplayName("keys and values containing ';', '~' and '|' round-trip exactly, and never appear raw on the wire")
    void itemRoundTripsSeparators() throws ProtocolException {
        DataItem item = new DataItem("a;b~c|d", "x;y~z;~;~|", 41, 3, 2);

        String line = ReplicationProtocol.encodeItem(item);

        assertThat(ReplicationProtocol.decodeItem(line)).isEqualTo(item);
        assertThat(line).doesNotContain(";").doesNotContain("~");
        assertThat(line.split("\\|", -1)).hasSize(6);
    }

    @Test
    @DisplayName("Unicode, an empty value and an unpaired surrogate round-trip exactly")
    void itemRoundTripsUnicodeEmptyAndLoneSurrogate() throws ProtocolException {
        List<DataItem> items = List.of(
                item("é漢😀", "value é漢😀"),
                item("empty", ""),
                item("lone\uD800", "high\uD800 low\uDC00 end"));
        for (DataItem item : items) {
            assertThat(ReplicationProtocol.decodeItem(ReplicationProtocol.encodeItem(item))).isEqualTo(item);
        }
    }

    @Test
    @DisplayName("the longest key and value fit one line and round-trip")
    void maxLengthItemRoundTrips() throws IOException {
        DataItem item = item("k".repeat(DataItem.MAX_KEY_LENGTH), "￿".repeat(DataItem.MAX_VALUE_LENGTH));

        String line = ReplicationProtocol.encodeItem(item);

        assertThat(line.length()).isLessThanOrEqualTo(ReplicationProtocol.MAX_LINE_LENGTH);
        assertThat(ReplicationProtocol.readRequest(stream(ReplicationProtocol.replicate(1, 5, 1, item))).items())
                .containsExactly(item);
    }

    @Test
    @DisplayName("every request round-trips through readRequest")
    void requestsRoundTrip() throws IOException {
        DataItem a = item("a", "1");
        DataItem b = item("b;~", "2");

        ReplicationProtocol.Request replicate = ReplicationProtocol.readRequest(stream(ReplicationProtocol.replicate(3, 10, 2, a)));
        ReplicationProtocol.Request sync = ReplicationProtocol.readRequest(stream(ReplicationProtocol.sync(3, 11, 2, List.of(a, b))));
        ReplicationProtocol.Request emptySync = ReplicationProtocol.readRequest(stream(ReplicationProtocol.sync(3, 12, 2, List.of())));
        ReplicationProtocol.Request read = ReplicationProtocol.readRequest(stream(ReplicationProtocol.read(0, 13, "b;~")));
        ReplicationProtocol.Request firstPage = ReplicationProtocol.readRequest(stream(ReplicationProtocol.dump(0, 14, null, 50)));
        ReplicationProtocol.Request nextPage = ReplicationProtocol.readRequest(stream(ReplicationProtocol.dump(0, 15, "a", 50)));

        assertThat(replicate).isEqualTo(new ReplicationProtocol.Request(ReplicationProtocol.RequestType.REPLICATE, 3, 10, 2, null, 0, List.of(a)));
        assertThat(sync).isEqualTo(new ReplicationProtocol.Request(ReplicationProtocol.RequestType.SYNC, 3, 11, 2, null, 0, List.of(a, b)));
        assertThat(emptySync.items()).isEmpty();
        assertThat(read).isEqualTo(new ReplicationProtocol.Request(ReplicationProtocol.RequestType.READ, 0, 13, 0, "b;~", 0, List.of()));
        assertThat(firstPage).isEqualTo(new ReplicationProtocol.Request(ReplicationProtocol.RequestType.DUMP, 0, 14, 0, null, 50, List.of()));
        assertThat(nextPage.key()).isEqualTo("a");
        assertThat(ReplicationProtocol.readRequest(raw(""))).isNull();
    }

    @Test
    @DisplayName("every reply round-trips; an ERROR reply becomes a ProtocolException carrying its message")
    void repliesRoundTrip() throws IOException {
        DataItem a = item("a", "x;~");
        AntiEntropyResult merged = new AntiEntropyResult(5, 2, 1, 1, 1);

        assertThat(ReplicationProtocol.decodeAck(ReplicationProtocol.ack(2, 30, 3, ApplyResult.STALE)))
                .isEqualTo(new AckReply(2, 30, 3, ApplyResult.STALE));
        assertThat(ReplicationProtocol.decodeSynced(ReplicationProtocol.synced(2, 31, 3, merged)))
                .isEqualTo(new SyncReply(2, 31, 3, merged));
        List<String> value = ReplicationProtocol.value(2, 32, 3, Optional.of(a));
        assertThat(ReplicationProtocol.readValue(value.get(0), stream(value.subList(1, value.size()))))
                .isEqualTo(new ReadReply(2, 32, 3, Optional.of(a)));
        List<String> absent = ReplicationProtocol.value(2, 33, 3, Optional.empty());
        assertThat(ReplicationProtocol.readValue(absent.get(0), stream(absent.subList(1, absent.size()))).item()).isEmpty();
        List<String> dumped = ReplicationProtocol.dumped(2, 34, 3, List.of(a, item("b", "")), true);
        assertThat(ReplicationProtocol.readDumped(dumped.get(0), stream(dumped.subList(1, dumped.size()))))
                .isEqualTo(new DumpPage(2, 34, 3, List.of(a, item("b", "")), true));

        String error = ReplicationProtocol.error(4, 35, "bad | thing\nhere");
        assertThat(ReplicationProtocol.lamportTimeOf(error)).hasValue(35);
        assertThatThrownBy(() -> ReplicationProtocol.decodeAck(error))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("Node 4").hasMessageContaining("bad | thing here");
    }

    @Test
    @DisplayName("malformed items are refused: bad Base64, odd byte count, wrong field count, negative numbers, invalid DataItem")
    void malformedItemsRefused() {
        String good = ReplicationProtocol.encodeItem(item("k", "v"));
        String[] f = good.split("\\|", -1);
        List<String> bad = List.of(
                "ITEM|!!!|" + f[2] + "|7|2|1",
                "ITEM|QQ==|" + f[2] + "|7|2|1",                   // one byte: odd count
                "ITEM|" + f[1] + "|" + f[2] + "|7|2",
                "ITEM|" + f[1] + "|" + f[2] + "|-1|2|1",
                "ITEM|" + f[1] + "|" + f[2] + "|7|0|1",           // originNode 0
                "ITEM|" + f[1] + "|" + f[2] + "|7|2|0",           // epoch 0
                "ITEM||" + f[2] + "|7|2|1",                       // empty key
                "ITEM|" + ReplicationProtocol.encodeText("a\nb") + "|" + f[2] + "|7|2|1",   // control character
                "THING|" + f[1] + "|" + f[2] + "|7|2|1");
        for (String line : bad) {
            assertThatThrownBy(() -> ReplicationProtocol.decodeItem(line)).as(line).isInstanceOf(ProtocolException.class);
        }
    }

    @Test
    @DisplayName("malformed requests are refused: unknown type, wrong fields, bad numbers, epoch 0, too many items, limit 0")
    void malformedRequestsRefused() {
        List<String> bad = List.of(
                "HELLO\n",
                "READ|1|2\n",
                "READ|x|2|" + ReplicationProtocol.encodeText("k") + "\n",
                "READ|1|-2|" + ReplicationProtocol.encodeText("k") + "\n",
                "READ|1|2|" + ReplicationProtocol.encodeText(" ") + "\n",
                "REPLICATE|1|2|0\n" + ReplicationProtocol.encodeItem(item("k", "v")) + "\nEND\n",
                "SYNC|1|2|1|1001\nEND\n",
                "DUMP|1|2||0\n",
                "DUMP|1|2||1001\n");
        for (String message : bad) {
            assertThatThrownBy(() -> ReplicationProtocol.readRequest(raw(message)))
                    .as(message).isInstanceOf(ProtocolException.class);
        }
    }

    @Test
    @DisplayName("a SYNC whose item lines do not match its count, or that never ends, is refused")
    void syncCountMustMatch() {
        String a = ReplicationProtocol.encodeItem(item("a", "1"));
        String b = ReplicationProtocol.encodeItem(item("b", "2"));

        assertThatThrownBy(() -> ReplicationProtocol.readRequest(raw("SYNC|1|2|1|2\n" + a + "\nEND\n")))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("Expected 2 item lines, got 1");
        assertThatThrownBy(() -> ReplicationProtocol.readRequest(raw("SYNC|1|2|1|1\n" + a + "\n" + b + "\nEND\n")))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("More than the 1 item lines");
        assertThatThrownBy(() -> ReplicationProtocol.readRequest(raw("SYNC|1|2|1|1\n" + a + "\n")))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("without END");
        assertThatThrownBy(() -> ReplicationProtocol.readRequest(raw("REPLICATE|1|2|1\nEND\n")))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    @DisplayName("more than 1000 items cannot be encoded or decoded in one message")
    void itemCountIsBounded() {
        List<DataItem> tooMany = new ArrayList<>();
        for (int i = 0; i <= ReplicationProtocol.MAX_ITEMS_PER_MESSAGE; i++) {
            tooMany.add(item("k" + i, ""));
        }
        assertThatThrownBy(() -> ReplicationProtocol.sync(1, 1, 1, tooMany)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReplicationProtocol.dumped(1, 1, 1, tooMany, false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ReplicationProtocol.sync(1, 1, 1, tooMany.subList(0, ReplicationProtocol.MAX_ITEMS_PER_MESSAGE)))
                .hasSize(ReplicationProtocol.MAX_ITEMS_PER_MESSAGE + 2);
    }

    @Test
    @DisplayName("an item epoch above the sender's epoch is refused when encoding and when decoding")
    void itemEpochAboveSenderEpochRefused() {
        DataItem epochThree = new DataItem("k", "v", 1, 1, 3);
        String line = ReplicationProtocol.encodeItem(epochThree);

        assertThatThrownBy(() -> ReplicationProtocol.replicate(1, 1, 2, epochThree)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReplicationProtocol.readRequest(raw("REPLICATE|1|1|2\n" + line + "\nEND\n")))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("above the sender's epoch 2");
    }

    @Test
    @DisplayName("readLine is bounded at 8192 characters and refuses bytes that are not printable ASCII")
    void readLineBounded() throws IOException {
        String longest = "x".repeat(ReplicationProtocol.MAX_LINE_LENGTH);

        assertThat(ReplicationProtocol.readLine(raw(longest + "\r\n"))).isEqualTo(longest);
        assertThatThrownBy(() -> ReplicationProtocol.readLine(raw(longest + "xx\n")))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("longer than 8192");
        assertThatThrownBy(() -> ReplicationProtocol.readLine(raw(longest + "x\n")))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> ReplicationProtocol.readLine(raw("café\n"))).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> ReplicationProtocol.readLine(raw("a\rb\n"))).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> ReplicationProtocol.writeLines(new ByteArrayOutputStream(), List.of("a\nb")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("lamportTimeOf reads the third field, or nothing for a short or invalid line")
    void lamportTimeOf() {
        assertThat(ReplicationProtocol.lamportTimeOf("ACK|1|42|1|APPLIED")).hasValue(42);
        assertThat(ReplicationProtocol.lamportTimeOf("ACK|1")).isEmpty();
        assertThat(ReplicationProtocol.lamportTimeOf("ACK|1|x")).isEmpty();
        assertThat(ReplicationProtocol.lamportTimeOf("ACK|1|-3")).isEmpty();
        assertThat(ReplicationProtocol.lamportTimeOf(null)).isEmpty();
    }

    @Test
    @DisplayName("malformed replies are refused: wrong type, unknown result, counts that do not add up, VALUE with 2 items, bad more flag")
    void malformedRepliesRefused() {
        assertThatThrownBy(() -> ReplicationProtocol.decodeAck("SYNCED|1|2|1|APPLIED")).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> ReplicationProtocol.decodeAck("ACK|1|2|1|MAYBE")).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> ReplicationProtocol.decodeAck("ACK|0|2|1|APPLIED")).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> ReplicationProtocol.decodeSynced("SYNCED|1|2|1|5|1|1|1|1")).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> ReplicationProtocol.readValue("VALUE|1|2|1|2", raw("END\n"))).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> ReplicationProtocol.readDumped("DUMPED|1|2|1|0|2", raw("END\n"))).isInstanceOf(ProtocolException.class);
        String b = ReplicationProtocol.encodeItem(item("b", ""));
        String a = ReplicationProtocol.encodeItem(item("a", ""));
        assertThatThrownBy(() -> ReplicationProtocol.readDumped("DUMPED|1|2|1|2|0", raw(b + "\n" + a + "\nEND\n")))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("ascending");
    }
}
