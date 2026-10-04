package com.udcf.web;

import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.core.events.EventProperties;
import com.udcf.web.dto.ClusterEventDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** Guards topic routing and that a failed send never escapes onto the bus's thread. */
class EventBroadcasterTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Instant WALL = Instant.parse("2026-01-01T00:00:00Z");

    private ClusterEventBus bus;
    private SimpMessagingTemplate template;
    private EventBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(100, 100), Clock.systemUTC());
        template = mock(SimpMessagingTemplate.class);
        broadcaster = new EventBroadcaster(bus, template);
    }

    @AfterEach
    void tearDown() {
        broadcaster.unsubscribe();
        bus.close();
    }

    private static ClusterEvent event(String module) {
        return new ClusterEvent(7, module, 2, "T", 3, WALL, null, null, null);
    }

    @Test
    @DisplayName("a module event goes to /topic/events and /topic/modules/{module} only")
    void moduleEventRouting() {
        ClusterEvent event = event("election");

        broadcaster.broadcast(event);

        ClusterEventDto dto = ClusterEventDto.from(event);
        verify(template).convertAndSend("/topic/events", dto);
        verify(template).convertAndSend("/topic/modules/election", dto);
        verifyNoMoreInteractions(template);
    }

    @Test
    @DisplayName("a cluster event goes to /topic/events and /topic/cluster only")
    void clusterEventRouting() {
        ClusterEvent event = event("cluster");

        broadcaster.broadcast(event);

        ClusterEventDto dto = ClusterEventDto.from(event);
        verify(template).convertAndSend("/topic/events", dto);
        verify(template).convertAndSend("/topic/cluster", dto);
        verifyNoMoreInteractions(template);
    }

    @Test
    @DisplayName("a send failure is logged, never thrown, and the other topic is still tried")
    void sendFailureDoesNotThrow() {
        doThrow(new MessageDeliveryException("broker down (expected by this test)"))
                .when(template).convertAndSend(anyString(), any(Object.class));
        ClusterEvent event = event("election");

        assertThatCode(() -> broadcaster.broadcast(event)).doesNotThrowAnyException();

        verify(template).convertAndSend(eq("/topic/events"), any(Object.class));
        verify(template).convertAndSend(eq("/topic/modules/election"), any(Object.class));
    }

    @Test
    @DisplayName("forwards bus events after subscribe and stops after unsubscribe")
    void subscribeAndUnsubscribe() {
        broadcaster.subscribe();
        broadcaster.subscribe();   // idempotent: still one subscription

        ClusterEvent first = bus.publish(EventDraft.of("election", 1, "FIRST", 1));
        await().atMost(TIMEOUT).untilAsserted(() ->
                verify(template).convertAndSend("/topic/modules/election", ClusterEventDto.from(first)));

        broadcaster.unsubscribe();
        List<ClusterEvent> marker = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(marker::add);
        ClusterEvent second = bus.publish(EventDraft.of("election", 1, "SECOND", 2));

        await().atMost(TIMEOUT).until(() -> marker.contains(second));
        verify(template, never()).convertAndSend(anyString(),
                argThat((Object payload) -> payload instanceof ClusterEventDto dto && dto.sequence() == second.sequence()));
        verify(template).convertAndSend("/topic/events", ClusterEventDto.from(first));
    }
}
