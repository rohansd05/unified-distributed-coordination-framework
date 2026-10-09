package com.udcf.modules.mapreduce.dto;

/**
 * One job a run can choose, from the job registry.
 *
 * <p>No dedicated test: a record; tested through MapReduceModuleTest and MapReduceControllerTest.</p>
 *
 * @param id          the job id used in {@code POST /runs}, e.g. {@code word-count}
 * @param title       short sentence-case name, e.g. "Word count"
 * @param description the job's one-line question
 */
public record JobDto(String id, String title, String description) {
}
