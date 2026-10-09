package com.udcf.modules.mapreduce;

import com.udcf.core.events.EventLogExporter;
import com.udcf.modules.mapreduce.dto.UploadDto;
import com.udcf.web.InvalidParameterException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Builds a run's {@link RunInput} from the bundled sample, an uploaded file or the live event
 * log.
 *
 * <p><b>Upload</b> (checked on the request thread, so a bad file gets HTTP 400 and changes
 * nothing). The file stays in memory only: it is never written to disk, logged or put into an
 * event. Each problem is reported as an {@link InvalidParameterException} on an
 * {@code upload.*} field: a missing upload, a missing or unsafe file name, a name not ending
 * in .txt, a content type other than {@code text/plain} (an empty type is accepted, as some
 * browsers send none), content that is not Base64, an empty file, a file over the cap, bytes
 * that are not UTF-8, and a NUL or any other control character except tab, line feed and
 * carriage return. A leading byte-order mark is removed. Only the last path segment of the
 * file name is kept for display.</p>
 *
 * <p><b>Event log.</b> One snapshot through {@link EventLogExporter} (at most the event buffer
 * size), then the same byte cap as an upload: the newest lines in causal order are kept and the
 * number left out is reported, never hidden.</p>
 */
final class RunInputLoader {

    static final String SAMPLE_RESOURCE = "mapreduce/sample-text.txt";
    static final String SAMPLE_NAME = "Bundled sample text";
    static final String EVENT_LOG_NAME = "Live cluster event log";
    static final int MAX_FILE_NAME_LENGTH = 100;
    static final String NO_EVENTS_NOTICE = "No events have been recorded yet; use another page first.";

    private final int capBytes;
    private final long capBase64Chars;
    private final EventLogExporter exporter;
    private final int eventLimit;
    private final RunInput sample;

    /**
     * @param eventLimit most events read for the event-log input (the event buffer size)
     * @throws IllegalStateException if the bundled sample is missing from the classpath
     */
    RunInputLoader(MapReduceModuleProperties properties, EventLogExporter exporter, int eventLimit) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.capBytes = properties.uploadMaxBytes();
        this.capBase64Chars = properties.uploadBase64Chars();
        this.exporter = Objects.requireNonNull(exporter, "exporter must not be null");
        if (eventLimit < 1) {
            throw new IllegalArgumentException("eventLimit must be >= 1, was " + eventLimit);
        }
        this.eventLimit = eventLimit;
        this.sample = loadSample();
    }

    /** The bundled sample text (loaded once). */
    RunInput sample() {
        return sample;
    }

    /**
     * Checks and decodes an uploaded file.
     *
     * @throws InvalidParameterException naming the {@code upload.*} field and the problem
     */
    RunInput upload(UploadDto upload) {
        if (upload == null) {
            throw new InvalidParameterException("upload", "is required when inputType is UPLOAD");
        }
        String displayName = displayName(upload.fileName());
        checkContentType(upload.contentType());

        String base64 = upload.contentBase64();
        if (base64 == null) {
            throw new InvalidParameterException("upload.contentBase64", "is required");
        }
        if (base64.length() > capBase64Chars) {
            throw tooLarge();
        }
        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new InvalidParameterException("upload.contentBase64", "is not valid Base64");
        }
        if (bytes.length == 0) {
            throw new InvalidParameterException("upload.contentBase64", "the file is empty");
        }
        if (bytes.length > capBytes) {
            throw tooLarge();
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new InvalidParameterException("upload.contentBase64", "the file is not valid UTF-8 text");
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        checkControlCharacters(text);
        return new RunInput(InputType.UPLOAD, displayName, splitLines(text), bytes.length, null, null);
    }

    /** One snapshot of the live event log, capped to the newest lines that fit the byte cap. */
    RunInput eventLog() {
        List<String> all = exporter.lines(null, null, eventLimit);
        if (all.isEmpty()) {
            return new RunInput(InputType.EVENT_LOG, EVENT_LOG_NAME, List.of(), 0, 0, NO_EVENTS_NOTICE);
        }
        List<String> kept = new ArrayList<>();
        long bytes = 0;
        for (int i = all.size() - 1; i >= 0; i--) {
            long lineBytes = all.get(i).getBytes(StandardCharsets.UTF_8).length + 1L;   // + line feed
            if (bytes + lineBytes > capBytes) {
                break;
            }
            bytes += lineBytes;
            kept.add(all.get(i));
        }
        Collections.reverse(kept);
        int dropped = all.size() - kept.size();
        String notice = dropped == 0 ? null
                : "The event log was larger than the " + capBytes + "-byte input limit, so the oldest "
                + dropped + (dropped == 1 ? " line was" : " lines were") + " left out.";
        return new RunInput(InputType.EVENT_LOG, EVENT_LOG_NAME, kept, bytes, dropped, notice);
    }

    /** Splits text into lines on LF, CRLF or CR; a final line terminator adds no empty line. */
    static List<String> splitLines(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(List.of(text.split("\r\n|\r|\n", -1)));
        if (lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /** The last path segment of the browser's file name, without control characters. */
    static String displayName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new InvalidParameterException("upload.fileName", "is required");
        }
        String name = fileName.substring(Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1);
        StringBuilder clean = new StringBuilder(name.length());
        name.codePoints().filter(cp -> !Character.isISOControl(cp)).forEach(clean::appendCodePoint);
        name = clean.toString().strip();
        if (name.isEmpty()) {
            throw new InvalidParameterException("upload.fileName", "has no usable file name");
        }
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            throw new InvalidParameterException("upload.fileName",
                    "must be at most " + MAX_FILE_NAME_LENGTH + " characters long");
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".txt") || name.length() == ".txt".length()) {
            throw new InvalidParameterException("upload.fileName", "must be a .txt file");
        }
        return name;
    }

    private static void checkContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return;
        }
        String base = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (!base.equals("text/plain")) {
            throw new InvalidParameterException("upload.contentType", "must be text/plain, was " + base);
        }
    }

    private static void checkControlCharacters(String text) {
        int line = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                continue;
            }
            if (c == '\t' || c == '\r') {
                continue;
            }
            if (c == 0) {
                throw new InvalidParameterException("upload.contentBase64",
                        "the file contains a NUL character on line " + line + ", so it is not a text file");
            }
            if (Character.isISOControl(c)) {
                throw new InvalidParameterException("upload.contentBase64", String.format(Locale.ROOT,
                        "the file contains the control character U+%04X on line %d; only tab, line feed "
                                + "and carriage return are allowed", (int) c, line));
            }
        }
    }

    private InvalidParameterException tooLarge() {
        return new InvalidParameterException("upload.contentBase64",
                "the file is larger than the limit of " + capBytes + " bytes");
    }

    private static RunInput loadSample() {
        try (InputStream in = RunInputLoader.class.getClassLoader().getResourceAsStream(SAMPLE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Bundled MapReduce sample is missing: " + SAMPLE_RESOURCE);
            }
            byte[] bytes = in.readAllBytes();
            String text = new String(bytes, StandardCharsets.UTF_8);
            return new RunInput(InputType.SAMPLE, SAMPLE_NAME, splitLines(text), bytes.length, null, null);
        } catch (IOException e) {
            throw new IllegalStateException("Bundled MapReduce sample could not be read: " + SAMPLE_RESOURCE, e);
        }
    }
}
