package com.udcf.modules.multithreading;

import com.udcf.modules.multithreading.dto.RequestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default publisher: writes lifecycle events to the log.
 *
 * <p>Step E2c replaces it with a publisher that sends lifecycle events to the cluster event
 * bus; because the processing service only sees {@link RequestEventPublisher}, that swap
 * needs no change there.</p>
 *
 * <p>No dedicated test: it only writes debug log lines.</p>
 */
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
