package com.udcf.modules.rmi.dto;

/**
 * One argument a method takes (besides the call context every method gets).
 *
 * <p>No dedicated test: a record; tested through RmiControllerTest.</p>
 *
 * @param type    {@code string} or {@code int}
 * @param minimum for {@code string} the shortest length in characters, for {@code int} the smallest value
 * @param maximum for {@code string} the longest length in characters, for {@code int} the largest value
 */
public record ParameterDto(String name, String type, String description, int minimum, int maximum) {
}
