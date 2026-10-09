package com.udcf.modules.mapreduce.dto;

/**
 * One result key of a run.
 *
 * <p>No dedicated test: a record; built and tested by RunReportMapperTest.</p>
 *
 * @param key           the result key
 * @param display       the job's own formatting of the value, e.g. "12.40 ms average over 5 events"
 *                      or "No latency measured"
 * @param count         the counted number (word or event count; for the latency job the number of
 *                      measured events); {@code null} if the value is not a count
 * @param averageMillis latency job only: sum divided by count, computed once from the reduced
 *                      {@code sum;count} (never an average of averages); {@code null} when the
 *                      count is 0 and for every other job
 */
public record ResultRowDto(String key, String display, Long count, Double averageMillis) {
}
