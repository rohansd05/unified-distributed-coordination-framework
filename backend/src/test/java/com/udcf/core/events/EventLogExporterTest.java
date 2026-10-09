package com.udcf.core.events;

import com.udcf.modules.mapreduce.EventCategoryJob;
import com.udcf.modules.mapreduce.JobReport;
import com.udcf.modules.mapreduce.LatencyPerNodeJob;
import com.udcf.modules.mapreduce.LogFields;
import com.udcf.modules.mapreduce.MapReducePipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The event-log export (link L5): the line format, a full round trip through the E7a
 * {@link LogFields} parser, escaping, latency honesty, ordering, filters and limits.
 */
class EventLogExporterTest {

    private static final Instant NOW = Instant.parse("2026-10-09T08:41:03.276123Z");

    private ClusterEventBus bus;
    private EventLogExporter exporter;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(100, 100), Clock.fixed(NOW, ZoneOffset.UTC));
        exporter = new EventLogExporter(bus);
    }

    @AfterEach
    void tearDown() {
        bus.close();
    }

    /** What a parser of the exported format reads back from one line. */
    private record Parsed(Instant wallTime, long sequence, long lamportTime, int nodeId, String module,
                          String type, Integer peerId, Double latency, String message) {
    }

    private static Parsed parse(String line) {
        String wall = line.split("\\|")[0].trim();
        Instant wallTime = LocalDateTime.parse(wall, EventLogExporter.WALL_TIME).toInstant(ZoneOffset.UTC);
        String peer = LogFields.field(line, "peer");
        String latency = LogFields.field(line, "latency");
        String message = LogFields.field(line, "msg");
        return new Parsed(wallTime,
                Long.parseLong(LogFields.field(line, "seq")),
                Long.parseLong(LogFields.field(line, "lamport")),
                Integer.parseInt(LogFields.field(line, "node")),
                EventLogExporter.unescape(LogFields.field(line, "module")),
                EventLogExporter.unescape(LogFields.field(line, "category")),
                peer == null ? null : Integer.valueOf(peer),
                latency == null ? null : Double.valueOf(latency),
                message == null ? null : EventLogExporter.unescape(message));
    }

    private ClusterEvent publish(String module, int node, String type, long lamport, Integer peer, String message,
                                 Map<String, Object> data) {
        return bus.publish(new EventDraft(module, node, type, lamport, peer, message, data));
    }

    @Test
    @DisplayName("a line keeps the sample log's layout, so LogFields reads node, category and latency")
    void lineLayout() {
        publish("replication", 2, "PUSH_ACKED", 7, 3, "message received from peer", Map.of("latencyMillis", 10.48));

        String line = exporter.lines(null, null, 10).get(0);

        assertThat(line).isEqualTo("2026-10-09 08:41:03.276 | node=2 | category=PUSH_ACKED | latency=10.48 | seq=1"
                + " | lamport=7 | module=replication | peer=3 | msg=message received from peer");
        assertThat(LogFields.field(line, "node")).isEqualTo("2");
        assertThat(LogFields.field(line, "category")).isEqualTo("PUSH_ACKED");
        assertThat(LogFields.field(line, "latency")).isEqualTo("10.48");
    }

    @Test
    @DisplayName("export, parse with LogFields and compare every field, including a null peer, a null message and hostile text")
    void roundTrip() {
        List<ClusterEvent> events = new ArrayList<>();
        events.add(publish("mapreduce", 1, "TASK_SENT", 3, null, "pipe | then\nnew line\tand tab \\ back = equals",
                Map.of()));
        events.add(publish("replication", 2, "PUSH_ACKED", 4, 1, "  edge spaces  ", Map.of("latencyMillis", 12.25)));
        events.add(publish("cluster", 0, "CLUSTER_RESET", 5, null, null, null));
        events.add(publish("clocksync", 3, "X", 6, 2, "control \u0001 and   and \r and \\s literal", Map.of()));
        events.add(publish("election", 4, "Y", 7, null, "", Map.of()));
        events.add(publish("election", 5, "Z", 8, null, "latency=99 | node=9 | category=FAKE", Map.of()));

        List<String> lines = exporter.lines(null, null, 100);

        assertThat(lines).hasSize(events.size());
        for (int i = 0; i < events.size(); i++) {
            ClusterEvent e = events.get(i);
            Parsed p = parse(lines.get(i));
            assertThat(lines.get(i)).doesNotContain("\n").doesNotContain("\r").doesNotContain("\t");
            assertThat(p).isEqualTo(new Parsed(e.wallTime().truncatedTo(ChronoUnit.MILLIS), e.sequence(),
                    e.lamportTime(), e.nodeId(), e.module(), e.type(), e.peerId(),
                    EventLogExporter.measuredLatency(e), e.message()));
        }
        assertThat(parse(lines.get(0)).peerId()).isNull();
        assertThat(parse(lines.get(1)).latency()).isEqualTo(12.25);
        assertThat(parse(lines.get(2)).message()).isNull();
        assertThat(lines.get(2)).doesNotContain("peer=").doesNotContain("msg=").doesNotContain("latency=");
    }

    @Test
    @DisplayName("a message can never inject a field the log jobs would read")
    void noFieldInjection() {
        publish("x", 1, "REAL", 1, null, "latency=5 | node=9 | category=FAKE | latency=7", Map.of());

        String line = exporter.lines(null, null, 10).get(0);

        assertThat(LogFields.field(line, "latency")).isNull();
        assertThat(LogFields.field(line, "node")).isEqualTo("1");
        assertThat(LogFields.field(line, "category")).isEqualTo("REAL");
        List<String> emitted = new ArrayList<>();
        new LatencyPerNodeJob().map(line, (k, v) -> emitted.add(k));
        assertThat(emitted).isEmpty();
    }

    @Test
    @DisplayName("latency is written only for a measured, finite, non-negative latencyMillis without a simulated delay")
    void latencyOnlyWhenMeasured() {
        publish("r", 1, "A", 1, null, null, Map.of("latencyMillis", 12.5));
        publish("r", 1, "B", 2, null, null, Map.of("latencyMillis", 7));
        publish("r", 1, "C", 3, null, null, Map.of("latencyMillis", 460.0, "simulatedDelayMillis", 450));
        publish("r", 1, "D", 4, null, null, Map.of("latencyMillis", "fast"));
        publish("r", 1, "E", 5, null, null, Map.of("latencyMillis", -1.0));
        publish("r", 1, "F", 6, null, null, Map.of("latencyMillis", Double.NaN));
        publish("r", 1, "G", 7, null, null, Map.of("elapsedMillis", 3.0));

        List<String> latencies = exporter.lines(null, null, 10).stream()
                .map(line -> LogFields.field(line, "latency")).toList();

        assertThat(latencies).containsExactly("12.5", "7.0", null, null, null, null, null);
    }

    @Test
    @DisplayName("lines are in causal order (lamportTime, nodeId), not arrival order")
    void causalOrder() {
        publish("m", 2, "LATE", 9, null, null, Map.of());
        publish("m", 3, "TIE_HIGH", 5, null, null, Map.of());
        publish("m", 1, "TIE_LOW", 5, null, null, Map.of());
        publish("m", 1, "EARLY", 1, null, null, Map.of());

        assertThat(exporter.lines(null, null, 10)).extracting(line -> LogFields.field(line, "category"))
                .containsExactly("EARLY", "TIE_LOW", "TIE_HIGH", "LATE");
    }

    @Test
    @DisplayName("module and node filters and the limit work like GET /api/events (newest by arrival)")
    void filtersAndLimit() {
        for (int i = 1; i <= 6; i++) {
            publish(i % 2 == 0 ? "even" : "odd", i % 3 + 1, "E" + i, i, null, null, Map.of());
        }

        assertThat(exporter.lines("even", null, 10)).extracting(l -> LogFields.field(l, "category"))
                .containsExactly("E2", "E4", "E6");
        assertThat(exporter.lines(null, 1, 10)).extracting(l -> LogFields.field(l, "category"))
                .containsExactly("E3", "E6");
        assertThat(exporter.lines(null, null, 2)).extracting(l -> LogFields.field(l, "category"))
                .containsExactly("E5", "E6");
        assertThatThrownBy(() -> exporter.lines(null, null, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an empty log exports nothing; otherwise every line ends with a line feed")
    void emptyAndTerminators() {
        assertThat(exporter.lines(null, null, 10)).isEmpty();
        assertThat(exporter.export(null, null, 10)).isEmpty();

        publish("m", 1, "A", 1, null, "one", Map.of());
        publish("m", 1, "B", 2, null, "two", Map.of());

        String text = exporter.export(null, null, 10);
        assertThat(text).endsWith("\n");
        assertThat(text.split("\n", -1)).hasSize(3);
    }

    @Test
    @DisplayName("the E7a log jobs run on exported lines: categories are counted and latency is averaged from sum and count")
    void logJobsReadTheExport() throws Exception {
        publish("replication", 2, "PUSH_ACKED", 1, 1, "a", Map.of("latencyMillis", 10.0));
        publish("replication", 2, "PUSH_ACKED", 2, 1, "b", Map.of("latencyMillis", 20.0));
        publish("replication", 3, "PUSH_ACKED", 3, 1, "c", Map.of("latencyMillis", 460.0, "simulatedDelayMillis", 450));
        publish("election", 1, "LEADER_ELECTED", 4, null, "d", Map.of());

        List<String> lines = exporter.lines(null, null, 10);
        Map<String, String> categories = MapReducePipeline.runLocal(new EventCategoryJob(), lines, 2,
                new JobReport("event-category-count"));
        Map<String, String> latency = MapReducePipeline.runLocal(new LatencyPerNodeJob(), lines, 2,
                new JobReport("avg-latency-per-node"));

        assertThat(categories).containsExactlyInAnyOrderEntriesOf(Map.of("PUSH_ACKED", "3", "LEADER_ELECTED", "1"));
        assertThat(latency).containsExactlyInAnyOrderEntriesOf(Map.of("node-2", "30.0;2"));
    }

    @Test
    @DisplayName("escape covers every delimiter and control character, and unescape rejects broken escapes")
    void escaping() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("a|b", "a\\pb");
        cases.put("a\\b", "a\\\\b");
        cases.put("a\nb\rc\td", "a\\nb\\rc\\td");
        cases.put(" x ", "\\sx\\s");
        cases.put("a\u0000b\u007fc\u0085d", "a\\u0000b\\u007fc\\u0085d");
        cases.put("a b", "a b");
        cases.put("ünïcödé", "ünïcödé");
        Map<String, String> escaped = new HashMap<>();
        cases.forEach((raw, expected) -> escaped.put(raw, EventLogExporter.escape(raw)));

        cases.forEach((raw, expected) -> {
            assertThat(escaped.get(raw)).isEqualTo(expected);
            assertThat(EventLogExporter.unescape(expected)).isEqualTo(raw);
        });
        assertThatThrownBy(() -> EventLogExporter.unescape("bad\\")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventLogExporter.unescape("bad\\q")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventLogExporter.unescape("bad\\u12")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventLogExporter.unescape("bad\\uzzzz")).isInstanceOf(IllegalArgumentException.class);
    }
}
