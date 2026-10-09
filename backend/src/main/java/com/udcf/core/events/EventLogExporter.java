package com.udcf.core.events;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Writes the cluster event log in the line format the MapReduce log jobs read (link L5),
 * for {@code GET /api/events/export} and for Experiment 7's live event-log input.
 *
 * <p>One event per line, in causal order {@code (lamportTime, nodeId, sequence)}:</p>
 *
 * <pre>
 * &lt;yyyy-MM-dd HH:mm:ss.SSS, UTC&gt; | node=&lt;nodeId&gt; | category=&lt;type&gt;[ | latency=&lt;ms&gt;] | seq=&lt;sequence&gt; | lamport=&lt;lamportTime&gt; | module=&lt;module&gt;[ | peer=&lt;peerId&gt;][ | msg=&lt;message&gt;]
 * </pre>
 *
 * <p>The leading timestamp and the {@code node}, {@code category} and {@code latency} fields
 * keep the layout of the committed sample log, so {@code LogFields} reads both the same way.
 * A field in square brackets is written only when the event has a value for it: a null peer,
 * a null message or an unmeasured latency is left out, and {@code LogFields} then returns
 * {@code null} for it. Nothing is invented.</p>
 *
 * <p><b>Latency.</b> Written only from a numeric {@code data.latencyMillis} (a measured round
 * trip, today from Experiment 5), and never for an event that also carries
 * {@code simulatedDelayMillis}: a simulated delay must not enter a measured average (R7).
 * {@code data} is not exported otherwise.</p>
 *
 * <p><b>Escaping.</b> String values ({@code category}, {@code module}, {@code msg}) can never
 * break the line or inject a field: {@code \} becomes {@code \\}, {@code |} becomes
 * {@code \p}, line feed {@code \n}, carriage return {@code \r}, tab {@code \t}, any other
 * control character (and U+2028, U+2029) {@code \}{@code uXXXX}, and a leading or trailing
 * space {@code \s} (because {@code LogFields} trims values). {@link #unescape} reverses it.
 * The message is written as {@code msg=...}, so text like {@code "latency=5"} in a message is
 * never read as a field.</p>
 */
@Component
public class EventLogExporter {

    /** Wall time of each line: the sample log's layout, always in UTC. Display only. */
    public static final DateTimeFormatter WALL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC);

    /** Event data key whose numeric value is exported as {@code latency=}. */
    public static final String LATENCY_KEY = "latencyMillis";

    /** Event data key marking a latency that includes a simulated delay; such latency is not exported. */
    public static final String SIMULATED_DELAY_KEY = "simulatedDelayMillis";

    private static final String SEPARATOR = " | ";

    private final ClusterEventBus bus;

    public EventLogExporter(ClusterEventBus bus) {
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
    }

    /**
     * The most recent matching events by arrival, up to {@code limit}, as lines in causal order
     * (the same selection as {@code GET /api/events}).
     *
     * @param module filter by module id, or {@code null} for every module
     * @param nodeId filter by node id, or {@code null} for every node
     * @param limit  maximum number of lines, at least 1
     */
    public List<String> lines(String module, Integer nodeId, int limit) {
        return bus.query(module, nodeId, limit).stream().map(EventLogExporter::format).toList();
    }

    /** {@link #lines} as one text, every line ending in a line feed; empty when there are no events. */
    public String export(String module, Integer nodeId, int limit) {
        StringBuilder text = new StringBuilder();
        for (String line : lines(module, nodeId, limit)) {
            text.append(line).append('\n');
        }
        return text.toString();
    }

    /** One event as one line (see the class description). */
    public static String format(ClusterEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        StringBuilder line = new StringBuilder(128);
        line.append(WALL_TIME.format(event.wallTime()));
        line.append(SEPARATOR).append("node=").append(event.nodeId());
        line.append(SEPARATOR).append("category=").append(escape(event.type()));
        Double latency = measuredLatency(event);
        if (latency != null) {
            line.append(SEPARATOR).append("latency=").append(BigDecimal.valueOf(latency).toPlainString());
        }
        line.append(SEPARATOR).append("seq=").append(event.sequence());
        line.append(SEPARATOR).append("lamport=").append(event.lamportTime());
        line.append(SEPARATOR).append("module=").append(escape(event.module()));
        if (event.peerId() != null) {
            line.append(SEPARATOR).append("peer=").append(event.peerId());
        }
        if (event.message() != null) {
            line.append(SEPARATOR).append("msg=").append(escape(event.message()));
        }
        return line.toString();
    }

    /**
     * The event's measured latency in milliseconds, or {@code null} if it has none: no numeric,
     * finite, non-negative {@code latencyMillis}, or a {@code simulatedDelayMillis} entry.
     */
    public static Double measuredLatency(ClusterEvent event) {
        Map<String, Object> data = event.data();
        if (data.containsKey(SIMULATED_DELAY_KEY)) {
            return null;
        }
        if (data.get(LATENCY_KEY) instanceof Number number) {
            double value = number.doubleValue();
            if (Double.isFinite(value) && value >= 0) {
                return value;
            }
        }
        return null;
    }

    /** Escapes one value so it cannot break the line or inject a field (see the class description). */
    public static String escape(String value) {
        Objects.requireNonNull(value, "value must not be null");
        StringBuilder out = new StringBuilder(value.length() + 8);
        int last = value.length() - 1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '|' -> out.append("\\p");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case ' ' -> out.append(i == 0 || i == last ? "\\s" : " ");
                default -> {
                    if (Character.isISOControl(c) || c == ' ' || c == ' ') {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * Reverses {@link #escape}.
     *
     * @throws IllegalArgumentException on a backslash not followed by a valid escape
     */
    public static String unescape(String value) {
        Objects.requireNonNull(value, "value must not be null");
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (i + 1 >= value.length()) {
                throw new IllegalArgumentException("dangling escape at the end of the value");
            }
            char next = value.charAt(++i);
            switch (next) {
                case '\\' -> out.append('\\');
                case 'p' -> out.append('|');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 's' -> out.append(' ');
                case 'u' -> {
                    if (i + 4 >= value.length()) {
                        throw new IllegalArgumentException("incomplete \\u escape");
                    }
                    try {
                        out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("invalid \\u escape", e);
                    }
                    i += 4;
                }
                default -> throw new IllegalArgumentException("unknown escape \\" + next);
            }
        }
        return out.toString();
    }
}
