package com.udcf.modules.mapreduce.dto;

/**
 * Measured stage durations in milliseconds; each is {@code null} when that stage did not run
 * (never 0 for "not measured").
 *
 * <p>No dedicated test: a record; built and tested by RunReportMapperTest.</p>
 *
 * @param totalMillis the whole pipeline call; {@code null} only for a failed run
 */
public record StageTimingsDto(Double mapMillis, Double shuffleMillis, Double reduceMillis, Double totalMillis) {
}
