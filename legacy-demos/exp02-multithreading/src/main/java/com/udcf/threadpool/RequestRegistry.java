package com.udcf.threadpool;

import com.udcf.model.DistributedRequest;
import com.udcf.model.RequestStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Bounded, thread-safe store of recent requests handled by this node.
 *
 * <p>Deliberately in memory rather than PostgreSQL. Section 21 of the project context
 * says not to push ephemeral per-request telemetry into the database when Prometheus
 * already owns time series; this registry only exists so the UI can show the last few
 * hundred requests and their assigned threads.</p>
 *
 * <p>Oldest entries are evicted once {@link #capacity} is exceeded, so a long
 * demonstration run cannot exhaust heap.</p>
 */
@Component
public class RequestRegistry {

    private static final int DEFAULT_CAPACITY = 500;

    private final int capacity;
    private final Map<String, DistributedRequest> byId = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> insertionOrder = new ConcurrentLinkedDeque<>();

    @Autowired
    public RequestRegistry() {
        this(DEFAULT_CAPACITY);
    }

    /** Package-visible so tests can use a small capacity to prove eviction. */
    RequestRegistry(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public void register(DistributedRequest request) {
        byId.put(request.getId(), request);
        insertionOrder.addLast(request.getId());
        evictOverflow();
    }

    private void evictOverflow() {
        while (byId.size() > capacity) {
            String oldest = insertionOrder.pollFirst();
            if (oldest == null) {
                return;
            }
            byId.remove(oldest);
        }
    }

    public Optional<DistributedRequest> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Most recently submitted first, capped at {@code limit}. */
    public List<DistributedRequest> recent(int limit) {
        List<DistributedRequest> snapshot = new ArrayList<>(byId.values());
        snapshot.sort(Comparator.comparing(DistributedRequest::getSubmittedAt).reversed());
        if (limit <= 0 || limit >= snapshot.size()) {
            return snapshot;
        }
        return new ArrayList<>(snapshot.subList(0, limit));
    }

    /** Counts by lifecycle state, with every state present so the UI never sees gaps. */
    public Map<RequestStatus, Long> countByStatus() {
        Map<RequestStatus, Long> counts = new EnumMap<>(RequestStatus.class);
        for (RequestStatus status : RequestStatus.values()) {
            counts.put(status, 0L);
        }
        for (DistributedRequest request : byId.values()) {
            counts.merge(request.getStatus(), 1L, Long::sum);
        }
        return counts;
    }

    public int size() {
        return byId.size();
    }

    public int getCapacity() {
        return capacity;
    }

    public void clear() {
        byId.clear();
        insertionOrder.clear();
    }
}
