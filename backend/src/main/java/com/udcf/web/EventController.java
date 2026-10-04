package com.udcf.web;

import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.web.dto.ClusterEventDto;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Recent events from the ring buffer, causally ordered by
 * {@code (lamportTime, nodeId, sequence)}. Clients load this history once, then follow
 * {@code /topic/events} over STOMP.
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final ClusterEventBus bus;
    private final EventProperties properties;

    public EventController(ClusterEventBus bus, EventProperties properties) {
        this.bus = bus;
        this.properties = properties;
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
}
