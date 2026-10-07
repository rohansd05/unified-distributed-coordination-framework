package com.udcf.modules.multithreading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RequestRegistryTest {

    private DistributedRequest request(String id) {
        return new DistributedRequest(id, 1, WorkloadType.CPU_HASH, 10);
    }

    @Test
    @DisplayName("stores and retrieves a request by id")
    void storesAndRetrieves() {
        RequestRegistry registry = new RequestRegistry(10);
        DistributedRequest request = request("a1");

        registry.register(request);

        assertThat(registry.find("a1")).containsSame(request);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("returns empty for an unknown id rather than null")
    void returnsEmptyForUnknownId() {
        assertThat(new RequestRegistry(10).find("missing")).isEmpty();
    }

    @Test
    @DisplayName("evicts the oldest entries once capacity is exceeded")
    void evictsOldestBeyondCapacity() {
        RequestRegistry registry = new RequestRegistry(3);

        for (int i = 1; i <= 5; i++) {
            registry.register(request("r" + i));
        }

        assertThat(registry.size()).isEqualTo(3);
        assertThat(registry.find("r1")).isEmpty();
        assertThat(registry.find("r2")).isEmpty();
        assertThat(registry.find("r5")).isPresent();
    }

    @Test
    @DisplayName("returns recent requests newest first, capped at the limit")
    void returnsRecentNewestFirst() throws InterruptedException {
        RequestRegistry registry = new RequestRegistry(10);

        registry.register(request("old"));
        Thread.sleep(2);
        registry.register(request("mid"));
        Thread.sleep(2);
        registry.register(request("new"));

        assertThat(registry.recent(2))
                .extracting(DistributedRequest::getId)
                .containsExactly("new", "mid");
    }

    @Test
    @DisplayName("counts include every status, with zero for unused ones")
    void countsIncludeEveryStatus() {
        RequestRegistry registry = new RequestRegistry(10);
        DistributedRequest completed = request("c1");
        completed.markStarted("t1");
        completed.markCompleted("done");
        registry.register(completed);
        registry.register(request("q1"));

        Map<RequestStatus, Long> counts = registry.countByStatus();

        assertThat(counts).containsEntry(RequestStatus.COMPLETED, 1L)
                .containsEntry(RequestStatus.QUEUED, 1L)
                .containsEntry(RequestStatus.FAILED, 0L)
                .containsEntry(RequestStatus.REJECTED, 0L)
                .hasSize(RequestStatus.values().length);
    }

    @Test
    @DisplayName("clear removes everything")
    void clearRemovesEverything() {
        RequestRegistry registry = new RequestRegistry(10);
        registry.register(request("a"));

        registry.clear();

        assertThat(registry.size()).isZero();
        assertThat(registry.recent(10)).isEmpty();
    }

    @Test
    @DisplayName("stays consistent under concurrent registration")
    void survivesConcurrentRegistration() throws InterruptedException {
        RequestRegistry registry = new RequestRegistry(500);
        int threads = 8;
        int perThread = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            final int threadIndex = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        registry.register(request("t" + threadIndex + "-" + i));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(registry.size()).isEqualTo(threads * perThread);
    }

    @Test
    @DisplayName("a clear racing with registrations never leaves the index and the eviction order out of step")
    void clearRacingWithRegistrationStaysConsistent() throws InterruptedException {
        RequestRegistry registry = new RequestRegistry(50);
        AtomicBoolean stop = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            for (int i = 0; !stop.get(); i++) {
                registry.register(request("w" + i));
            }
        });
        writer.start();
        for (int i = 0; i < 2000; i++) {
            registry.clear();
        }
        stop.set(true);
        writer.join();

        // An id left in the index but missing from the eviction order would never be evicted,
        // and the registry would keep more than its capacity from here on.
        for (int i = 0; i < 200; i++) {
            registry.register(request("after" + i));
        }
        assertThat(registry.size()).isEqualTo(50);
        assertThat(registry.recent(0)).hasSize(50)
                .allMatch(request -> request.getId().startsWith("after"));
    }
}
