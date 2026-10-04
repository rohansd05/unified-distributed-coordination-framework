package com.udcf.web;

import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.web.dto.ClusterEventDto;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Forwards every cluster event to STOMP subscribers.
 *
 * <p>Each event goes to {@value #EVENTS_TOPIC}, and also to {@value #CLUSTER_TOPIC} when its
 * module is {@code "cluster"}, or to {@code /topic/modules/{module}} otherwise.</p>
 *
 * <p>Subscribes once the application is ready, so events published during startup (such
 * as {@code CLUSTER_STARTED}) stay in the event buffer only; clients load history over
 * REST. Runs on the bus's dispatcher thread, so a send failure is logged and never
 * propagates.</p>
 */
@Component
public class EventBroadcaster {

    public static final String EVENTS_TOPIC = "/topic/events";
    public static final String CLUSTER_TOPIC = "/topic/cluster";
    public static final String MODULE_TOPIC_PREFIX = "/topic/modules/";
    private static final String CLUSTER_MODULE = "cluster";
    private static final Logger log = LoggerFactory.getLogger(EventBroadcaster.class);

    private final ClusterEventBus bus;
    private final SimpMessagingTemplate template;
    private ClusterEventBus.Subscription subscription;   // guarded by this

    public EventBroadcaster(ClusterEventBus bus, SimpMessagingTemplate template) {
        this.bus = bus;
        this.template = template;
    }

    /** Starts forwarding. Idempotent. */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void subscribe() {
        if (subscription == null) {
            subscription = bus.subscribe(this::broadcast);
        }
    }

    /** Stops forwarding. Idempotent. */
    @PreDestroy
    public synchronized void unsubscribe() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }

    void broadcast(ClusterEvent event) {
        ClusterEventDto dto = ClusterEventDto.from(event);
        send(EVENTS_TOPIC, dto);
        send(CLUSTER_MODULE.equals(event.module()) ? CLUSTER_TOPIC : MODULE_TOPIC_PREFIX + event.module(), dto);
    }

    private void send(String destination, ClusterEventDto dto) {
        try {
            template.convertAndSend(destination, dto);
        } catch (RuntimeException e) {
            log.warn("Failed to send event #{} ({}/{}) to {}", dto.sequence(), dto.module(), dto.type(),
                    destination, e);
        }
    }
}
