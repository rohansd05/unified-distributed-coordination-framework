package com.udcf.web;

import com.udcf.core.cluster.Cluster;
import com.udcf.web.dto.HealthDto;
import com.udcf.web.dto.SystemInfoDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;

/**
 * Liveness and identity. {@code /health} also drives the frontend's cold-start banner.
 * Uptime is measured with the injected {@link Clock} from the moment this controller was
 * created.
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final UdcfSystemProperties properties;
    private final Cluster cluster;
    private final Clock clock;
    private final Instant startedAt;

    public SystemController(UdcfSystemProperties properties, Cluster cluster, Clock clock) {
        this.properties = properties;
        this.cluster = cluster;
        this.clock = clock;
        this.startedAt = Instant.now(clock);
    }

    @GetMapping("/health")
    public HealthDto health() {
        return HealthDto.up();
    }

    @GetMapping("/info")
    public SystemInfoDto info() {
        return SystemInfoDto.from(properties, startedAt, Instant.now(clock), cluster.size());
    }
}
