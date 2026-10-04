package com.udcf.web.dto;

import com.udcf.web.UdcfSystemProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Wire form of {@code GET /api/system/info}.
 *
 * <p>{@code mode} is lowercase ({@code local} or {@code public}); {@code startedAt} is
 * ISO-8601.</p>
 */
public record SystemInfoDto(
        String name,
        String version,
        String mode,
        String startedAt,
        long uptimeSeconds,
        int clusterSize
) {

    public static final String NAME = "UDCF";

    public static SystemInfoDto from(UdcfSystemProperties properties, Instant startedAt, Instant now,
                                     int clusterSize) {
        return new SystemInfoDto(NAME, properties.version(), properties.mode().name().toLowerCase(Locale.ROOT),
                startedAt.toString(), Duration.between(startedAt, now).getSeconds(), clusterSize);
    }
}
