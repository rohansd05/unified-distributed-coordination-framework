package com.udcf.modules.multithreading;

import com.udcf.modules.multithreading.dto.RequestResult;

/**
 * Seam for pushing request lifecycle events out of the node.
 *
 * <p>Step E2c wires this to the cluster event bus so the page updates live. Until then the
 * logging implementation keeps the processing service free of any transport dependency,
 * which also means RequestProcessingServiceTest can assert on events with a simple stub.</p>
 *
 * <p>No dedicated test: an interface.</p>
 */
public interface RequestEventPublisher {

    void requestAccepted(RequestResult result);

    void requestStarted(RequestResult result);

    void requestFinished(RequestResult result);
}
