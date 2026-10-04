package com.udcf.web;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over native WebSocket on {@code /ws} (no SockJS — simpler behind Render's proxy).
 *
 * <p>The browser subscribes to {@code /topic/events}, {@code /topic/cluster} and
 * {@code /topic/modules/{id}}; {@link EventBroadcaster} fills them. Handshake origins come
 * only from {@link UdcfWebProperties}. The simple broker sends heartbeats every 10 s so idle
 * connections survive proxies; {@code /app} is reserved for client-to-server messages.</p>
 *
 * <p>No dedicated test file: declarative framework configuration. StompIntegrationTest
 * exercises the endpoint, the topics and the origin check end to end.</p>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final long HEARTBEAT_MILLIS = 10_000;

    private final UdcfWebProperties properties;
    private final TaskScheduler heartbeatScheduler;

    public WebSocketConfig(UdcfWebProperties properties,
                           @Qualifier("udcfWsHeartbeatScheduler") TaskScheduler heartbeatScheduler) {
        this.properties = properties;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    /**
     * Dedicated scheduler for broker heartbeats. Static so Spring can create it without
     * first instantiating this class, which lets it be constructor-injected above.
     */
    @Bean
    public static ThreadPoolTaskScheduler udcfWsHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("udcf-ws-heartbeat-");
        scheduler.setDaemon(true);
        return scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(properties.allowedOrigins().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[] {HEARTBEAT_MILLIS, HEARTBEAT_MILLIS})
                .setTaskScheduler(heartbeatScheduler);
        registry.setApplicationDestinationPrefixes("/app");
    }
}
