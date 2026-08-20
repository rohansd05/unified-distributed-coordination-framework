package com.udcf.threadpool;

import com.udcf.dto.RequestResult;

/**
 * Seam for pushing request lifecycle events out of the node.
 *
 * <p>Phase 1A wires this to STOMP so the dashboard updates live. Until then the logging
 * implementation keeps the processing service free of any transport dependency, which
 * also means RequestProcessingServiceTest can assert on events with a simple stub.</p>
 */
public interface RequestEventPublisher {

    void requestAccepted(RequestResult result);

    void requestStarted(RequestResult result);

    void requestFinished(RequestResult result);
}
