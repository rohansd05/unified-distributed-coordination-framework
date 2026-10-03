package com.udcf.threadpool;

import com.udcf.dto.RequestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default publisher: writes lifecycle events to the log.
 *
 * <p>Replaced by a STOMP-backed implementation once the WebSocket layer lands; this one
 * is annotated so it can be superseded without touching the processing service.</p>
 */
@Component
public class LoggingRequestEventPublisher implements RequestEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingRequestEventPublisher.class);

    @Override
    public void requestAccepted(RequestResult result) {
        log.debug("Request {} accepted on node {}", result.id(), result.nodeId());
    }

    @Override
    public void requestStarted(RequestResult result) {
        log.debug("Request {} started on thread {}", result.id(), result.threadName());
    }

    @Override
    public void requestFinished(RequestResult result) {
        log.debug("Request {} finished with status {} in {} ms",
                result.id(), result.status(), result.totalMillis());
    }
}
