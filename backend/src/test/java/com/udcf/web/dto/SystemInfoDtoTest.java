package com.udcf.web.dto;

import com.udcf.web.RunMode;
import com.udcf.web.UdcfSystemProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the system info wire form: lowercase mode, ISO-8601 start time, uptime. */
class SystemInfoDtoTest {

    private static final Instant STARTED = Instant.parse("2026-01-01T10:00:00Z");

    @Test
    @DisplayName("from maps name, version, mode, start time, uptime and cluster size")
    void systemInfoMapping() {
        SystemInfoDto dto = SystemInfoDto.from(new UdcfSystemProperties("0.1.0", RunMode.LOCAL),
                STARTED, STARTED.plusSeconds(90), 5);

        assertThat(dto).isEqualTo(new SystemInfoDto("UDCF", "0.1.0", "local", "2026-01-01T10:00:00Z", 90, 5));
    }

    @Test
    @DisplayName("mode is lowercase for both run modes")
    void modeIsLowercase() {
        assertThat(SystemInfoDto.from(new UdcfSystemProperties("1", RunMode.PUBLIC), STARTED, STARTED, 3).mode())
                .isEqualTo("public");
        assertThat(SystemInfoDto.from(new UdcfSystemProperties("1", RunMode.LOCAL), STARTED, STARTED, 3).mode())
                .isEqualTo("local");
    }

    @Test
    @DisplayName("startedAt is ISO-8601 and uptime counts whole seconds")
    void startedAtIsIso8601() {
        Instant precise = Instant.parse("2026-01-01T10:00:00.250Z");

        SystemInfoDto dto = SystemInfoDto.from(new UdcfSystemProperties("1", RunMode.LOCAL),
                precise, precise.plusMillis(2_900), 3);

        assertThat(Instant.parse(dto.startedAt())).isEqualTo(precise);
        assertThat(dto.uptimeSeconds()).isEqualTo(2);
    }
}
