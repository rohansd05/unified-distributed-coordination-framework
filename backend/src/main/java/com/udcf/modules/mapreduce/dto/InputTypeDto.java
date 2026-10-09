package com.udcf.modules.mapreduce.dto;

import com.udcf.modules.mapreduce.InputType;

/**
 * One input a run can choose.
 *
 * <p>No dedicated test: a record; tested through MapReduceControllerTest.</p>
 */
public record InputTypeDto(InputType id, String title, String description) {
}
