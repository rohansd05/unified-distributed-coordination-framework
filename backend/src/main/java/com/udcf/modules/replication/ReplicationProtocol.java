package com.udcf.modules.replication;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The line protocol of a node's replication service: one request and one reply per TCP
 * connection. A message is a header line, followed, for messages that carry items, by one
 * line per item and a closing {@code END} line.
 *
 * <pre>
 * request  REPLICATE|&lt;senderId&gt;|&lt;lamport&gt;|&lt;senderEpoch&gt;            + 1 item line + END
 * request  SYNC|&lt;senderId&gt;|&lt;lamport&gt;|&lt;senderEpoch&gt;|&lt;n&gt;              + n item lines + END
 * request  READ|&lt;senderId&gt;|&lt;lamport&gt;|&lt;keyB64&gt;
 * request  DUMP|&lt;senderId&gt;|&lt;lamport&gt;|&lt;afterKeyB64, empty for the start&gt;|&lt;limit&gt;
 * reply    ACK|&lt;nodeId&gt;|&lt;lamport&gt;|&lt;storeEpoch&gt;|&lt;ApplyResult&gt;
 * reply    SYNCED|&lt;nodeId&gt;|&lt;lamport&gt;|&lt;storeEpoch&gt;|&lt;pushed&gt;|&lt;applied&gt;|&lt;alreadyCurrent&gt;|&lt;stale&gt;|&lt;staleEpoch&gt;
 * reply    VALUE|&lt;nodeId&gt;|&lt;lamport&gt;|&lt;storeEpoch&gt;|&lt;0 or 1&gt;              + item lines + END
 * reply    DUMPED|&lt;nodeId&gt;|&lt;lamport&gt;|&lt;storeEpoch&gt;|&lt;n&gt;|&lt;more 0 or 1&gt;  + n item lines + END
 * reply    ERROR|&lt;nodeId&gt;|&lt;lamport&gt;|&lt;printable message&gt;
 * item     ITEM|&lt;keyB64&gt;|&lt;valueB64&gt;|&lt;lamportTime&gt;|&lt;originNode&gt;|&lt;epoch&gt;
 * </pre>
 *
 * <p><b>Wire safety.</b> Keys and values travel as Base64 of their UTF-16 code units, two
 * big-endian bytes per {@code char}, converted by hand rather than through a charset, so every
 * string {@link DataItem} accepts round-trips exactly, including {@code ;}, {@code ~},
 * {@code |}, the empty value and an unpaired surrogate (a charset would replace it). A Base64
 * field never contains {@code |}, so no field is ever split inside a key or value. The legacy
 * {@code split(";")} on raw values is gone.</p>
 *
 * <p><b>Limits.</b> Every line is printable ASCII ending in {@code \n} (a trailing {@code \r}
 * is ignored), at most {@value #MAX_LINE_LENGTH} characters; a message carries at most
 * {@value #MAX_ITEMS_PER_MESSAGE} items, and the number of item lines must match its header.
 * Every message carries the sender's Lamport time (link L4). Malformed input, including an
 * item whose epoch is above the sender's, is a {@link ProtocolException}.</p>
 *
 * <p>Pure: no sockets of its own. Covered by ReplicationProtocolTest.</p>
 */
public final class ReplicationProtocol {

    /** Longest accepted line, in characters, excluding the terminator. */
    public static final int MAX_LINE_LENGTH = 8192;

    /** Most items in one message (a SYNC request, a DUMPED page). */
    public static final int MAX_ITEMS_PER_MESSAGE = 1000;

    /** Every replication socket binds and connects here (docs/HANDOFF.md 6.3). */
    public static final InetAddress LOOPBACK = loopback();

    static final String REPLICATE = "REPLICATE";
    static final String SYNC = "SYNC";
    static final String READ = "READ";
    static final String DUMP = "DUMP";
    static final String ACK = "ACK";
    static final String SYNCED = "SYNCED";
    static final String VALUE = "VALUE";
    static final String DUMPED = "DUMPED";
    static final String ERROR = "ERROR";
    static final String ITEM = "ITEM";
    static final String END = "END";

    /** The four requests a node serves. */
    public enum RequestType { REPLICATE, SYNC, READ, DUMP }

    /**
     * One decoded request.
     *
     * @param type        the request
     * @param senderId    the sender's node id, or 0 for a cluster-level client
     * @param lamportTime the sender's Lamport time (link L4)
     * @param senderEpoch the sender's epoch; REPLICATE and SYNC only, else 0
     * @param key         the key to READ, or the key a DUMP page starts after (null for the
     *                    first page); else null
     * @param limit       the most items a DUMP page may hold; else 0
     * @param items       the items of a REPLICATE (one) or SYNC (0 to the maximum); else empty
     */
    public record Request(RequestType type, int senderId, long lamportTime, long senderEpoch, String key, int limit,
                          List<DataItem> items) {

        public Request {
            Objects.requireNonNull(type, "type must not be null");
            items = List.copyOf(items);
        }
    }

    private ReplicationProtocol() {
    }

    // ------------------------------------------------------------------ items

    public static String encodeItem(DataItem item) {
        return ITEM + "|" + encodeText(item.key()) + "|" + encodeText(item.value()) + "|" + item.lamportTime() + "|"
                + item.originNode() + "|" + item.epoch();
    }

    /** @throws ProtocolException if the line is not a valid item */
    public static DataItem decodeItem(String line) throws ProtocolException {
        String[] f = fields(line, 6);
        expect(f, ITEM);
        try {
            return new DataItem(decodeText(f[1], "key"), decodeText(f[2], "value"), parseLong(f[3], "lamportTime"),
                    parseInt(f[4], "originNode"), parseLong(f[5], "epoch"));
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Invalid item: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ requests

    public static List<String> replicate(int senderId, long lamportTime, long senderEpoch, DataItem item) {
        requireSenderEpoch(senderEpoch, List.of(item));
        return withItems(REPLICATE + "|" + senderId + "|" + lamportTime + "|" + senderEpoch, List.of(item));
    }

    /** @throws IllegalArgumentException for more than the maximum, or an item epoch above {@code senderEpoch} */
    public static List<String> sync(int senderId, long lamportTime, long senderEpoch, List<DataItem> items) {
        requireCount(items.size());
        requireSenderEpoch(senderEpoch, items);
        return withItems(SYNC + "|" + senderId + "|" + lamportTime + "|" + senderEpoch + "|" + items.size(), items);
    }

    public static List<String> read(int senderId, long lamportTime, String key) {
        return List.of(READ + "|" + senderId + "|" + lamportTime + "|" + encodeText(key));
    }

    /** @param afterKey the last key of the previous page, or null for the first page */
    public static List<String> dump(int senderId, long lamportTime, String afterKey, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1, was " + limit);
        }
        requireCount(limit);
        return List.of(DUMP + "|" + senderId + "|" + lamportTime + "|" + (afterKey == null ? "" : encodeText(afterKey))
                + "|" + limit);
    }

    /**
     * Reads one whole request.
     *
     * @return the request, or null if the stream ended before any character
     * @throws ProtocolException for anything malformed; the rest of the message is not read
     */
    public static Request readRequest(InputStream in) throws IOException {
        String header = readLine(in);
        if (header == null) {
            return null;
        }
        String type = header.split("\\|", 2)[0];
        switch (type) {
            case REPLICATE -> {
                String[] f = fields(header, 4);
                long senderEpoch = parseEpoch(f[3], "senderEpoch");
                return new Request(RequestType.REPLICATE, parseInt(f[1], "senderId"), parseLong(f[2], "lamportTime"),
                        senderEpoch, null, 0, readItems(in, 1, senderEpoch));
            }
            case SYNC -> {
                String[] f = fields(header, 5);
                long senderEpoch = parseEpoch(f[3], "senderEpoch");
                int count = parseCount(f[4]);
                return new Request(RequestType.SYNC, parseInt(f[1], "senderId"), parseLong(f[2], "lamportTime"),
                        senderEpoch, null, 0, readItems(in, count, senderEpoch));
            }
            case READ -> {
                String[] f = fields(header, 4);
                return new Request(RequestType.READ, parseInt(f[1], "senderId"), parseLong(f[2], "lamportTime"), 0,
                        decodeKey(f[3]), 0, List.of());
            }
            case DUMP -> {
                String[] f = fields(header, 5);
                int limit = parseCount(f[4]);
                if (limit < 1) {
                    throw new ProtocolException("limit must be >= 1");
                }
                return new Request(RequestType.DUMP, parseInt(f[1], "senderId"), parseLong(f[2], "lamportTime"), 0,
                        f[3].isEmpty() ? null : decodeKey(f[3]), limit, List.of());
            }
            default -> throw new ProtocolException("Unknown request type '" + fit(type, 40) + "'");
        }
    }

    // ------------------------------------------------------------------ replies

    public static String ack(int nodeId, long lamportTime, long storeEpoch, ApplyResult result) {
        return ACK + "|" + nodeId + "|" + lamportTime + "|" + storeEpoch + "|" + result.name();
    }

    public static String synced(int nodeId, long lamportTime, long storeEpoch, AntiEntropyResult r) {
        return SYNCED + "|" + nodeId + "|" + lamportTime + "|" + storeEpoch + "|" + r.pushed() + "|" + r.applied()
                + "|" + r.alreadyCurrent() + "|" + r.stale() + "|" + r.staleEpoch();
    }

    public static List<String> value(int nodeId, long lamportTime, long storeEpoch, Optional<DataItem> item) {
        List<DataItem> items = item.map(List::of).orElse(List.of());
        return withItems(VALUE + "|" + nodeId + "|" + lamportTime + "|" + storeEpoch + "|" + items.size(), items);
    }

    public static List<String> dumped(int nodeId, long lamportTime, long storeEpoch, List<DataItem> items, boolean more) {
        requireCount(items.size());
        return withItems(DUMPED + "|" + nodeId + "|" + lamportTime + "|" + storeEpoch + "|" + items.size() + "|"
                + (more ? 1 : 0), items);
    }

    /** An ERROR reply; the message is made printable and cut to fit one line. */
    public static String error(int nodeId, long lamportTime, String message) {
        String head = ERROR + "|" + nodeId + "|" + lamportTime + "|";
        return head + fit(message == null ? "" : message, MAX_LINE_LENGTH - head.length());
    }

    /** @throws ProtocolException for an ERROR reply or a line that is not a valid ACK */
    public static AckReply decodeAck(String header) throws ProtocolException {
        String[] f = replyFields(header, ACK, 5);
        try {
            return new AckReply(parseInt(f[1], "nodeId"), parseLong(f[2], "lamportTime"), parseEpoch(f[3], "storeEpoch"),
                    ApplyResult.valueOf(f[4]));
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Invalid ACK: " + e.getMessage());
        }
    }

    /** @throws ProtocolException for an ERROR reply or a line that is not a valid SYNCED */
    public static SyncReply decodeSynced(String header) throws ProtocolException {
        String[] f = replyFields(header, SYNCED, 9);
        try {
            return new SyncReply(parseInt(f[1], "nodeId"), parseLong(f[2], "lamportTime"), parseEpoch(f[3], "storeEpoch"),
                    new AntiEntropyResult(parseInt(f[4], "pushed"), parseInt(f[5], "applied"),
                            parseInt(f[6], "alreadyCurrent"), parseInt(f[7], "stale"), parseInt(f[8], "staleEpoch")));
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Invalid SYNCED: " + e.getMessage());
        }
    }

    /** Decodes a VALUE header and reads its item lines from {@code in}. */
    public static ReadReply readValue(String header, InputStream in) throws IOException {
        String[] f = replyFields(header, VALUE, 5);
        int count = parseInt(f[4], "count");
        if (count > 1) {
            throw new ProtocolException("A VALUE carries 0 or 1 items, was " + count);
        }
        long storeEpoch = parseEpoch(f[3], "storeEpoch");
        List<DataItem> items = readItems(in, count, Long.MAX_VALUE);
        try {
            return new ReadReply(parseInt(f[1], "nodeId"), parseLong(f[2], "lamportTime"), storeEpoch,
                    items.stream().findFirst());
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Invalid VALUE: " + e.getMessage());
        }
    }

    /** Decodes a DUMPED header and reads its item lines from {@code in}. */
    public static DumpPage readDumped(String header, InputStream in) throws IOException {
        String[] f = replyFields(header, DUMPED, 6);
        int count = parseCount(f[4]);
        boolean more = switch (f[5]) {
            case "0" -> false;
            case "1" -> true;
            default -> throw new ProtocolException("more must be 0 or 1, was '" + fit(f[5], 20) + "'");
        };
        long storeEpoch = parseEpoch(f[3], "storeEpoch");
        List<DataItem> items = readItems(in, count, Long.MAX_VALUE);
        try {
            return new DumpPage(parseInt(f[1], "nodeId"), parseLong(f[2], "lamportTime"), storeEpoch, items, more);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Invalid DUMPED: " + e.getMessage());
        }
    }

    /**
     * The Lamport time a header carries (its third field), if it has a valid one. Lets a
     * receiver apply Lamport rule 3 even to a message it then rejects, such as an ERROR reply.
     */
    public static OptionalLong lamportTimeOf(String line) {
        String[] parts = line == null ? new String[0] : line.split("\\|", 4);
        if (parts.length < 3) {
            return OptionalLong.empty();
        }
        try {
            long value = Long.parseLong(parts[2]);
            return value < 0 ? OptionalLong.empty() : OptionalLong.of(value);
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    // ------------------------------------------------------------------ lines

    /**
     * Reads one line, without its terminator.
     *
     * @return the line, or null if the stream ended before any character
     * @throws ProtocolException if the line is longer than {@value #MAX_LINE_LENGTH} characters
     *                           or holds a byte that is not printable ASCII; the rest of the
     *                           line is not read
     */
    public static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return stripCarriageReturn(line);
            }
            if (line.size() == MAX_LINE_LENGTH + 1) {   // + 1 leaves room for a trailing \r
                throw new ProtocolException("Line longer than " + MAX_LINE_LENGTH + " characters");
            }
            if ((b < 0x20 || b > 0x7E) && b != '\r') {
                throw new ProtocolException("Line holds a byte that is not printable ASCII (0x"
                        + Integer.toHexString(b) + ")");
            }
            line.write(b);
        }
        return line.size() == 0 ? null : stripCarriageReturn(line);
    }

    /** Writes each line plus {@code \n} as ASCII, then flushes once. */
    public static void writeLines(OutputStream out, List<String> lines) throws IOException {
        StringBuilder message = new StringBuilder();
        for (String line : lines) {
            if (line.length() > MAX_LINE_LENGTH || !line.equals(fit(line, line.length()))) {
                throw new IllegalArgumentException("not a printable protocol line of at most " + MAX_LINE_LENGTH
                        + " characters");
            }
            message.append(line).append('\n');
        }
        out.write(message.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    // ------------------------------------------------------------------ helpers

    /** Base64 of the UTF-16 code units, two big-endian bytes per char: lossless for any string. */
    static String encodeText(String text) {
        byte[] bytes = new byte[text.length() * 2];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            bytes[2 * i] = (byte) (c >>> 8);
            bytes[2 * i + 1] = (byte) c;
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    static String decodeText(String field, String name) throws ProtocolException {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(field);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(name + " is not valid Base64");
        }
        if (bytes.length % 2 != 0) {
            throw new ProtocolException(name + " has an odd number of bytes");
        }
        char[] chars = new char[bytes.length / 2];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = (char) (((bytes[2 * i] & 0xFF) << 8) | (bytes[2 * i + 1] & 0xFF));
        }
        return new String(chars);
    }

    private static String decodeKey(String field) throws ProtocolException {
        String key = decodeText(field, "key");
        if (key.isBlank() || key.length() > DataItem.MAX_KEY_LENGTH) {
            throw new ProtocolException("key must be 1-" + DataItem.MAX_KEY_LENGTH + " characters and not blank");
        }
        return key;
    }

    /** Reads exactly {@code count} item lines and then END; any other shape is refused. */
    private static List<DataItem> readItems(InputStream in, int count, long senderEpoch) throws IOException {
        List<DataItem> items = new ArrayList<>(count);
        while (true) {
            String line = readLine(in);
            if (line == null) {
                throw new ProtocolException("Message ended after " + items.size() + " of " + count
                        + " item lines, without END");
            }
            if (line.equals(END)) {
                break;
            }
            if (items.size() == count) {
                throw new ProtocolException("More than the " + count + " item lines the header announced");
            }
            DataItem item = decodeItem(line);
            if (item.epoch() > senderEpoch) {
                throw new ProtocolException("Item '" + fit(item.key(), 64) + "' has epoch " + item.epoch()
                        + ", above the sender's epoch " + senderEpoch);
            }
            items.add(item);
        }
        if (items.size() != count) {
            throw new ProtocolException("Expected " + count + " item lines, got " + items.size());
        }
        return items;
    }

    private static List<String> withItems(String header, List<DataItem> items) {
        List<String> lines = new ArrayList<>(items.size() + 2);
        lines.add(header);
        items.forEach(item -> lines.add(encodeItem(item)));
        lines.add(END);
        return lines;
    }

    private static void requireCount(int count) {
        if (count > MAX_ITEMS_PER_MESSAGE) {
            throw new IllegalArgumentException("at most " + MAX_ITEMS_PER_MESSAGE + " items per message, was " + count);
        }
    }

    private static void requireSenderEpoch(long senderEpoch, List<DataItem> items) {
        if (senderEpoch < DataStore.INITIAL_EPOCH) {
            throw new IllegalArgumentException("senderEpoch must be >= " + DataStore.INITIAL_EPOCH + ", was " + senderEpoch);
        }
        for (DataItem item : items) {
            if (item.epoch() > senderEpoch) {
                throw new IllegalArgumentException("item '" + item.key() + "' has epoch " + item.epoch()
                        + ", above the sender's epoch " + senderEpoch);
            }
        }
    }

    private static String[] fields(String line, int count) throws ProtocolException {
        if (line == null) {
            throw new ProtocolException("Empty message");
        }
        String[] parts = line.split("\\|", -1);
        if (parts.length != count) {
            throw new ProtocolException("Expected " + count + " '|'-separated fields, got " + parts.length);
        }
        return parts;
    }

    private static void expect(String[] f, String type) throws ProtocolException {
        if (!type.equals(f[0])) {
            throw new ProtocolException("Expected " + type + ", got '" + fit(f[0], 40) + "'");
        }
    }

    /** Splits a reply header; an ERROR reply becomes a ProtocolException carrying its message. */
    private static String[] replyFields(String header, String type, int count) throws ProtocolException {
        if (header != null && header.startsWith(ERROR + "|")) {
            String[] parts = header.split("\\|", 4);
            String node = parts.length > 1 ? parts[1] : "?";
            String message = parts.length > 3 ? parts[3] : "";
            throw new ProtocolException("Node " + node + " answered with an error: " + message);
        }
        String[] f = fields(header, count);
        expect(f, type);
        return f;
    }

    private static int parseCount(String field) throws ProtocolException {
        int count = parseInt(field, "count");
        if (count > MAX_ITEMS_PER_MESSAGE) {
            throw new ProtocolException("At most " + MAX_ITEMS_PER_MESSAGE + " items per message, was " + count);
        }
        return count;
    }

    private static long parseEpoch(String field, String name) throws ProtocolException {
        long epoch = parseLong(field, name);
        if (epoch < DataStore.INITIAL_EPOCH) {
            throw new ProtocolException(name + " must be >= " + DataStore.INITIAL_EPOCH + ", was " + epoch);
        }
        return epoch;
    }

    private static int parseInt(String field, String name) throws ProtocolException {
        try {
            int value = Integer.parseInt(field);
            if (value < 0) {
                throw new ProtocolException(name + " must be >= 0, was " + value);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ProtocolException(name + " is not a number: '" + fit(field, 40) + "'");
        }
    }

    private static long parseLong(String field, String name) throws ProtocolException {
        try {
            long value = Long.parseLong(field);
            if (value < 0) {
                throw new ProtocolException(name + " must be >= 0, was " + value);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ProtocolException(name + " is not a number: '" + fit(field, 40) + "'");
        }
    }

    private static String stripCarriageReturn(ByteArrayOutputStream line) throws ProtocolException {
        String text = line.toString(StandardCharsets.US_ASCII);
        text = text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
        if (text.length() > MAX_LINE_LENGTH || text.indexOf('\r') >= 0) {
            throw new ProtocolException("Line longer than " + MAX_LINE_LENGTH + " characters, or a stray carriage return");
        }
        return text;
    }

    /** Printable ASCII only (anything else becomes a space), cut to {@code max} characters. */
    private static String fit(String text, int max) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), Math.max(0, max)));
        for (int i = 0; i < text.length() && out.length() < max; i++) {
            char c = text.charAt(i);
            out.append(c >= 0x20 && c <= 0x7E ? c : ' ');
        }
        return out.toString();
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByAddress("localhost", new byte[]{127, 0, 0, 1});
        } catch (UnknownHostException e) {
            throw new IllegalStateException("cannot build 127.0.0.1", e);   // impossible: 4 bytes is valid
        }
    }
}
