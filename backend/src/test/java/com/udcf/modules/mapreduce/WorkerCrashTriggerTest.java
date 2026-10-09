package com.udcf.modules.mapreduce;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/** The crash plan: exactly one crash, only for the chosen worker, with the task type known. */
class WorkerCrashTriggerTest {

    private static final IntUnaryOperator PORTS = id -> 24500 + id;

    @Test
    @DisplayName("the first lookup of the chosen worker crashes it once; the port is still returned")
    void crashesOnce() throws Exception {
        AtomicInteger crashes = new AtomicInteger();
        List<String> told = new CopyOnWriteArrayList<>();
        WorkerCrashTrigger trigger = new WorkerCrashTrigger(3, id -> crashes.incrementAndGet() == 1,
                (id, type, crashed) -> told.add(id + ":" + type + ":" + crashed));
        IntUnaryOperator resolver = trigger.resolver(PORTS);
        TaskTransport transport = trigger.wrap((id, type, job, payload) -> String.valueOf(resolver.applyAsInt(id)));

        assertThat(transport.executeTask(2, TaskType.MAP, "j", "p")).isEqualTo("24502");
        assertThat(trigger.triggered()).isFalse();
        assertThat(transport.executeTask(3, TaskType.REDUCE, "j", "p")).isEqualTo("24503");
        assertThat(transport.executeTask(3, TaskType.MAP, "j", "p")).isEqualTo("24503");

        assertThat(crashes).hasValue(1);
        assertThat(told).containsExactly("3:REDUCE:true");
        assertThat(trigger.triggered()).isTrue();
        assertThat(trigger.nodeCrashed()).isTrue();
        assertThat(trigger.taskType()).isEqualTo(TaskType.REDUCE);
    }

    @Test
    @DisplayName("many concurrent attempts to the chosen worker still crash it exactly once")
    void concurrent() throws Exception {
        AtomicInteger crashes = new AtomicInteger();
        WorkerCrashTrigger trigger = new WorkerCrashTrigger(2, id -> {
            crashes.incrementAndGet();
            return true;
        }, (id, type, crashed) -> { });
        IntUnaryOperator resolver = trigger.resolver(PORTS);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < 100; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    resolver.applyAsInt(2);
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(crashes).hasValue(1);
    }

    @Test
    @DisplayName("a node already down is reported as not crashed by the module; no plan never crashes")
    void alreadyDownAndNoPlan() {
        WorkerCrashTrigger down = new WorkerCrashTrigger(4, id -> false, (id, type, crashed) -> { });
        down.resolver(PORTS).applyAsInt(4);
        assertThat(down.triggered()).isTrue();
        assertThat(down.nodeCrashed()).isFalse();
        assertThat(down.taskType()).isNull();   // looked up outside a wrapped attempt

        AtomicInteger crashes = new AtomicInteger();
        WorkerCrashTrigger none = new WorkerCrashTrigger(null, id -> crashes.incrementAndGet() > 0, (id, t, c) -> { });
        for (int id = 1; id <= 5; id++) {
            none.resolver(PORTS).applyAsInt(id);
        }
        assertThat(crashes).hasValue(0);
        assertThat(none.triggered()).isFalse();
        assertThat(none.workerId()).isNull();
    }
}
