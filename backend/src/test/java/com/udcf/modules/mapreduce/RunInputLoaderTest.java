package com.udcf.modules.mapreduce;

import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.core.events.EventLogExporter;
import com.udcf.core.events.EventProperties;
import com.udcf.modules.mapreduce.dto.UploadDto;
import com.udcf.web.InvalidParameterException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three inputs: the bundled sample, every upload check (each with its field and message),
 * and the event-log snapshot with its byte cap.
 */
class RunInputLoaderTest {

    private static final int CAP = 4096;

    private ClusterEventBus bus;
    private RunInputLoader loader;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(100, 100), Clock.systemUTC());
        loader = new RunInputLoader(new MapReduceModuleProperties(CAP, 10, 5, 100), new EventLogExporter(bus), 100);
    }

    @AfterEach
    void tearDown() {
        bus.close();
    }

    private static UploadDto upload(String name, String type, byte[] bytes) {
        return new UploadDto(name, type, Base64.getEncoder().encodeToString(bytes));
    }

    private static UploadDto upload(String text) {
        return upload("notes.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));
    }

    private void assertRejected(UploadDto upload, String field, String messagePart) {
        assertThatThrownBy(() -> loader.upload(upload))
                .isInstanceOfSatisfying(InvalidParameterException.class, e -> {
                    assertThat(e.parameter()).isEqualTo(field);
                    assertThat(e.getMessage()).contains(messagePart);
                });
    }

    @Test
    @DisplayName("the bundled sample is a main resource, byte for byte the same as the E7a test copy")
    void sample() throws Exception {
        RunInput sample = loader.sample();
        byte[] mainCopy = Files.readAllBytes(Path.of("src/main/resources/mapreduce/sample-text.txt"));
        byte[] testCopy = Files.readAllBytes(Path.of("src/test/resources/mapreduce/sample-text.txt"));

        assertThat(mainCopy).isEqualTo(testCopy);

        assertThat(sample.type()).isEqualTo(InputType.SAMPLE);
        assertThat(sample.displayName()).isEqualTo("Bundled sample text");
        assertThat(sample.bytes()).isEqualTo(testCopy.length);
        assertThat(sample.lines()).isNotEmpty();
        assertThat(sample.droppedLines()).isNull();
        assertThat(sample.notice()).isNull();
    }

    @Test
    @DisplayName("a valid upload: byte-order mark removed, CRLF and CR split, last path segment kept as the name")
    void validUpload() {
        byte[] bytes = "\uFEFFfirst line\r\nsecond\rthird\n".getBytes(StandardCharsets.UTF_8);

        RunInput input = loader.upload(upload("C:\\fakepath\\folder/My notes.TXT", "text/plain; charset=utf-8", bytes));

        assertThat(input.type()).isEqualTo(InputType.UPLOAD);
        assertThat(input.displayName()).isEqualTo("My notes.TXT");
        assertThat(input.lines()).containsExactly("first line", "second", "third");
        assertThat(input.bytes()).isEqualTo(bytes.length);
        assertThat(input.droppedLines()).isNull();
        assertThat(loader.upload(upload("a.txt", null, "x".getBytes(StandardCharsets.UTF_8))).lines())
                .containsExactly("x");
        assertThat(loader.upload(upload("a.txt", "", "tab\there".getBytes(StandardCharsets.UTF_8))).lines())
                .containsExactly("tab\there");
    }

    @Test
    @DisplayName("an upload at exactly the cap is accepted; one byte over is refused")
    void capBoundary() {
        assertThat(loader.upload(upload("a".repeat(CAP))).bytes()).isEqualTo(CAP);
        assertRejected(upload("a".repeat(CAP + 1)), "upload.contentBase64", "larger than the limit of 4096 bytes");
        assertRejected(new UploadDto("a.txt", "text/plain", "QUFB".repeat(2000)), "upload.contentBase64",
                "larger than the limit");
    }

    @Test
    @DisplayName("each bad upload is refused with its field and a clear message")
    void rejections() {
        assertThatThrownBy(() -> loader.upload(null)).isInstanceOfSatisfying(InvalidParameterException.class,
                e -> assertThat(e.parameter()).isEqualTo("upload"));
        assertRejected(upload(null, "text/plain", "x".getBytes()), "upload.fileName", "is required");
        assertRejected(upload("   ", "text/plain", "x".getBytes()), "upload.fileName", "is required");
        assertRejected(upload("dir/", "text/plain", "x".getBytes()), "upload.fileName", "no usable file name");
        assertRejected(upload("notes.csv", "text/plain", "x".getBytes()), "upload.fileName", "must be a .txt file");
        assertRejected(upload(".txt", "text/plain", "x".getBytes()), "upload.fileName", "must be a .txt file");
        assertRejected(upload("x".repeat(101) + ".txt", "text/plain", "x".getBytes()), "upload.fileName",
                "at most 100 characters");
        assertRejected(upload("a.txt", "application/pdf", "x".getBytes()), "upload.contentType", "must be text/plain");
        assertRejected(new UploadDto("a.txt", "text/plain", null), "upload.contentBase64", "is required");
        assertRejected(new UploadDto("a.txt", "text/plain", "not base64!"), "upload.contentBase64", "not valid Base64");
        assertRejected(upload("a.txt", "text/plain", new byte[0]), "upload.contentBase64", "the file is empty");
        assertRejected(upload("a.txt", "text/plain", new byte[]{'a', (byte) 0xC3, (byte) 0x28}),
                "upload.contentBase64", "not valid UTF-8");
        assertRejected(upload("a.txt", "text/plain", new byte[]{'a', 0, 'b'}), "upload.contentBase64",
                "NUL character on line 1");
        assertRejected(upload("one\ntwo\u0007"), "upload.contentBase64", "U+0007 on line 2");
        assertRejected(upload("c1 \u0085 control"), "upload.contentBase64", "U+0085");
    }

    @Test
    @DisplayName("the display name never keeps path components or control characters")
    void displayName() {
        assertThat(RunInputLoader.displayName("../../etc/passwd.txt")).isEqualTo("passwd.txt");
        assertThat(RunInputLoader.displayName("..\\..\\secret.txt")).isEqualTo("secret.txt");
        assertThat(RunInputLoader.displayName("bad\u0000na\nme.txt")).isEqualTo("badname.txt");
    }

    @Test
    @DisplayName("the event log is one snapshot in causal order; with no events the input is empty with an honest notice")
    void eventLog() {
        RunInput empty = loader.eventLog();
        assertThat(empty.lines()).isEmpty();
        assertThat(empty.droppedLines()).isZero();
        assertThat(empty.notice()).isEqualTo("No events have been recorded yet; use another page first.");

        bus.publish(EventDraft.of("m", 1, "A", 2));
        bus.publish(EventDraft.of("m", 1, "B", 1));
        RunInput input = loader.eventLog();
        assertThat(input.type()).isEqualTo(InputType.EVENT_LOG);
        assertThat(input.displayName()).isEqualTo("Live cluster event log");
        assertThat(input.lines()).extracting(l -> LogFields.field(l, "category")).containsExactly("B", "A");
        assertThat(input.droppedLines()).isZero();
        assertThat(input.notice()).isNull();
        bus.publish(EventDraft.of("m", 1, "C", 3));
        assertThat(input.lines()).hasSize(2);   // a snapshot does not grow
    }

    @Test
    @DisplayName("over the byte cap the newest lines are kept and the number left out is reported")
    void eventLogCap() {
        for (int i = 1; i <= 5; i++) {
            bus.publish(EventDraft.of("m", 1, "E" + i, i));
        }
        EventLogExporter exporter = new EventLogExporter(bus);
        int lineBytes = exporter.lines(null, null, 100).get(0).getBytes(StandardCharsets.UTF_8).length + 1;
        int cap = 3 * lineBytes + lineBytes / 2;
        RunInputLoader capped = new RunInputLoader(new MapReduceModuleProperties(cap, 10, 5, 100), exporter, 100);

        RunInput input = capped.eventLog();

        assertThat(input.lines()).extracting(l -> LogFields.field(l, "category")).containsExactly("E3", "E4", "E5");
        assertThat(input.droppedLines()).isEqualTo(2);
        assertThat(input.bytes()).isEqualTo(3L * lineBytes);
        assertThat(input.notice()).isEqualTo("The event log was larger than the " + cap
                + "-byte input limit, so the oldest 2 lines were left out.");
    }

    @Test
    @DisplayName("lines split on LF, CRLF and CR; a final terminator adds no empty line; blank lines are kept")
    void splitLines() {
        assertThat(RunInputLoader.splitLines("")).isEmpty();
        assertThat(RunInputLoader.splitLines("a\n\nb\r\n")).containsExactly("a", "", "b");
        assertThat(RunInputLoader.splitLines("\n")).containsExactly("");
        assertThat(RunInputLoader.splitLines("x")).isEqualTo(List.of("x"));
    }
}
