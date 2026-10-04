package com.udcf.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * System identity reported by {@code GET /api/system/info}, bound from {@code udcf.system.*}.
 *
 * <p>{@code version} is filled from the pom at build time ({@code @project.version@});
 * {@code mode} is {@code local} by default and overridden by the public profile.
 * Registered by {@code @ConfigurationPropertiesScan}.</p>
 *
 * <p>No dedicated behaviour; UdcfSystemPropertiesTest checks binding and validation.</p>
 *
 * @param version application version
 * @param mode    deployment mode
 */
@Validated
@ConfigurationProperties("udcf.system")
public record UdcfSystemProperties(@NotBlank String version, @NotNull RunMode mode) {
}
