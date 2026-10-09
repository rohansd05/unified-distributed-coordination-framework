package com.udcf.modules.mapreduce;

import java.util.List;

/**
 * A run's input, ready for the pipeline.
 *
 * <p>No dedicated test: a record without behaviour; built and tested by RunInputLoaderTest.</p>
 *
 * @param type         where it came from
 * @param displayName  a safe name for the report (never a path, never the file content)
 * @param lines        the input lines, without line terminators
 * @param bytes        UTF-8 size of the input text
 * @param droppedLines event-log lines left out by the byte cap; {@code null} for other inputs
 * @param notice       a plain sentence about the input, or {@code null}
 */
public record RunInput(
        InputType type,
        String displayName,
        List<String> lines,
        long bytes,
        Integer droppedLines,
        String notice
) {
    public RunInput {
        lines = List.copyOf(lines);
    }
}
