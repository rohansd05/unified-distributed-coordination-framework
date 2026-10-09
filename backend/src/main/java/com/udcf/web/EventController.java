package com.udcf.web;

import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventLogExporter;
import com.udcf.core.events.EventProperties;
import com.udcf.web.dto.ClusterEventDto;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Recent events from the ring buffer, causally ordered by
 * {@code (lamportTime, nodeId, sequence)}. Clients load this history once, then follow
 * {@code /topic/events} over STOMP.
 *
 * <p>{@code GET /api/events/export} returns the same selection as plain text in the MapReduce
 * log format (link L5, see {@link EventLogExporter}).</p>
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final ClusterEventBus bus;
    private final EventProperties properties;
    private final EventLogExporter exporter;

    public EventController(ClusterEventBus bus, EventProperties properties, EventLogExporter exporter) {
        this.bus = bus;
        this.properties = properties;
        this.exporter = exporter;
    }

    /**
     * @param module filter by module id; omitted or blank means every module
     * @param node   filter by node id (0 = cluster-level); omitted means every node
     * @param limit  most recent events by arrival, 1 to the buffer size
     */
    @GetMapping
    public List<ClusterEventDto> events(@RequestParam(required = false) String module,
                                        @RequestParam(required = false) @Min(0) Integer node,
                                        @RequestParam(defaultValue = "200") @Min(1) int limit) {
        if (limit > properties.bufferSize()) {
            throw new InvalidParameterException("limit",
                    "must be less than or equal to " + properties.bufferSize());
        }
        String moduleFilter = module == null || module.isBlank() ? null : module;
        return bus.query(moduleFilter, node, limit).stream().map(ClusterEventDto::from).toList();
    }

    /**
     * The event log as {@code text/plain; charset=UTF-8}, one event per line in the MapReduce
     * log format (link L5). Same parameters as {@link #events}; the maximum and the default
     * limit are both the buffer size, so the export is always bounded.
     */
    @GetMapping("/export")
    public ResponseEntity<String> export(@RequestParam(required = false) String module,
                                         @RequestParam(required = false) @Min(0) Integer node,
                                         @RequestParam(required = false) @Min(1) Integer limit) {
        int max = properties.bufferSize();
        if (limit != null && limit > max) {
            throw new InvalidParameterException("limit", "must be less than or equal to " + max);
        }
        String moduleFilter = module == null || module.isBlank() ? null : module;
        String text = exporter.export(moduleFilter, node, limit == null ? max : limit);
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(text);
    }
}
