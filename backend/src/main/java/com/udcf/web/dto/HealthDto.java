package com.udcf.web.dto;

/**
 * Liveness answer for {@code GET /api/system/health}.
 *
 * <p>No dedicated test: a plain record; SystemControllerTest checks the response.</p>
 */
public record HealthDto(String status) {

    public static HealthDto up() {
        return new HealthDto("UP");
    }
}
