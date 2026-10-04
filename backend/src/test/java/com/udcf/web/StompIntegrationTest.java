package com.udcf.web;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.web.dto.ClusterEventDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.AbstractBrokerMessageHandler;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Step 1.2's done-when: a real STOMP client on {@code /ws} receives published events on the
 * right topics, a node crash reaches {@code /topic/cluster}, and a foreign origin cannot
 * open the socket.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StompIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    @LocalServerPort
    private int port;

    @Autowired
    private ClusterEventBus bus;

    @Autowired
    private Cluster cluster;

    @Autowired
    @Qualifier("simpleBrokerMessageHandler")
    private AbstractBrokerMessageHandler brokerHandler;

    private WebSocketStompClient client;
    private StompSession session;

    @BeforeEach
    void setUp() {
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void tearDown() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        client.stop();
    }

    private CompletableFuture<StompSession> connect(String origin) {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(origin);
        return client.connectAsync("ws://localhost:{port}/ws", headers, new StompSessionHandlerAdapter() { }, port);
    }

    private BlockingQueue<ClusterEventDto> subscribe(String destination) {
        BlockingQueue<ClusterEventDto> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return ClusterEventDto.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((ClusterEventDto) payload);
            }
        });
        return queue;
    }

    /** True once the simple broker has registered a subscription for {@code destination}. */
    private boolean brokerHasSubscription(String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination(destination);
        Message<byte[]> probe = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return !((SimpleBrokerMessageHandler) brokerHandler).getSubscriptionRegistry()
                .findSubscriptions(probe).isEmpty();
    }

    private static ClusterEventDto poll(BlockingQueue<ClusterEventDto> queue) throws InterruptedException {
        ClusterEventDto received = queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(received).as("message within %d s", TIMEOUT_SECONDS).isNotNull();
        return received;
    }

    @Test
    @DisplayName("events reach /topic/events and their module topic; a crash reaches /topic/cluster")
    void eventsReachTheRightTopics() throws Exception {
        session = connect(ALLOWED_ORIGIN).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        BlockingQueue<ClusterEventDto> events = subscribe("/topic/events");
        BlockingQueue<ClusterEventDto> moduleTopic = subscribe("/topic/modules/test-module");
        BlockingQueue<ClusterEventDto> clusterTopic = subscribe("/topic/cluster");
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() ->
                List.of("/topic/events", "/topic/modules/test-module", "/topic/cluster").stream()
                        .allMatch(this::brokerHasSubscription));

        try {
            ClusterEvent ping = bus.publish(EventDraft.of("test-module", 1, "PING", 5));

            ClusterEventDto expected = new ClusterEventDto(ping.sequence(), "test-module", 1, "PING", 5,
                    ping.wallTime().toString(), null, null, Map.of());
            assertThat(poll(events)).isEqualTo(expected);
            assertThat(poll(moduleTopic)).isEqualTo(expected);

            assertThat(cluster.crash(2)).isTrue();

            // /topic/cluster's first message is the crash, so it never received the PING.
            ClusterEventDto crashed = poll(clusterTopic);
            assertThat(crashed.type()).isEqualTo("NODE_CRASHED");
            assertThat(crashed.module()).isEqualTo("cluster");
            assertThat(crashed.nodeId()).isEqualTo(2);
            assertThat(poll(events).type()).isEqualTo("NODE_CRASHED");
            assertThat(moduleTopic).isEmpty();
        } finally {
            cluster.recover(2);
        }
    }

    @Test
    @DisplayName("a handshake from a foreign origin is rejected")
    void foreignOriginRejected() {
        CompletableFuture<StompSession> attempt = connect("http://evil.example");

        assertThatThrownBy(() -> attempt.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasStackTraceContaining("403");
    }
}
