package com.udcf.modules.mapreduce.dto;

/**
 * Request part: the uploaded .txt file, sent as JSON so it stays in memory (R3: never written
 * to disk, never logged, never echoed into events). Checked by the module, which answers 400
 * with an {@code upload.*} field message for each problem.
 *
 * <p>No dedicated test: a record; validation is tested in RunInputLoaderTest and
 * MapReduceControllerTest.</p>
 *
 * @param fileName      the browser's file name; only its last path segment is kept, and it must end in .txt
 * @param contentType   the browser's type for the file: {@code text/plain} or empty
 * @param contentBase64 the file's bytes in standard Base64
 */
public record UploadDto(String fileName, String contentType, String contentBase64) {

    /** Never prints the content, so a stray log line cannot leak the file. */
    @Override
    public String toString() {
        return "UploadDto[fileName=" + fileName + ", contentType=" + contentType + ", contentBase64=("
                + (contentBase64 == null ? "null" : contentBase64.length() + " chars") + ")]";
    }
}
