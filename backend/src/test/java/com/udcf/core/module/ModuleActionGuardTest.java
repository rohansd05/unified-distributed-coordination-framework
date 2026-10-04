package com.udcf.core.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Guards the one-action-at-a-time rule behind HTTP 409. */
class ModuleActionGuardTest {

    @Test
    @DisplayName("begin holds the guard and close releases it")
    void beginAndCloseRelease() {
        ModuleActionGuard guard = new ModuleActionGuard("election");
        assertThat(guard.isBusy()).isFalse();
        assertThat(guard.currentAction()).isEmpty();

        try (ModuleActionGuard.ActionTicket ticket = guard.begin("run-election")) {
            assertThat(ticket.action()).isEqualTo("run-election");
            assertThat(guard.isBusy()).isTrue();
            assertThat(guard.currentAction()).contains("run-election");
        }

        assertThat(guard.isBusy()).isFalse();
        assertThat(guard.currentAction()).isEmpty();
    }

    @Test
    @DisplayName("a second begin while busy throws ModuleBusyException naming the current action")
    void secondBeginWhileBusy() {
        ModuleActionGuard guard = new ModuleActionGuard("election");
        guard.begin("run-election");

        assertThatThrownBy(() -> guard.begin("reset"))
                .isInstanceOf(ModuleBusyException.class)
                .hasMessageContaining("run-election")
                .satisfies(e -> {
                    ModuleBusyException busy = (ModuleBusyException) e;
                    assertThat(busy.moduleId()).isEqualTo("election");
                    assertThat(busy.actionInProgress()).isEqualTo("run-election");
                });
    }

    @Test
    @DisplayName("close is idempotent and a stale ticket never releases a newer action")
    void closeIsIdempotent() {
        ModuleActionGuard guard = new ModuleActionGuard("election");
        ModuleActionGuard.ActionTicket first = guard.begin("first");
        first.close();
        ModuleActionGuard.ActionTicket second = guard.begin("second");

        first.close();

        assertThat(guard.currentAction()).contains("second");
        second.close();
        second.close();
        assertThat(guard.isBusy()).isFalse();
    }

    @Test
    @DisplayName("a blank action is rejected")
    void blankActionRejected() {
        ModuleActionGuard guard = new ModuleActionGuard("election");

        assertThatIllegalArgumentException().isThrownBy(() -> guard.begin(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> guard.begin(null));
        assertThat(guard.isBusy()).isFalse();
    }

    @Test
    @DisplayName("20 concurrent begin calls give exactly one winner")
    void exactlyOneConcurrentWinner() throws Exception {
        int threads = 20;
        ModuleActionGuard guard = new ModuleActionGuard("election");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                String action = "action-" + t;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        guard.begin(action);
                        return true;
                    } catch (ModuleBusyException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    winners++;
                }
            }

            assertThat(winners).isEqualTo(1);
            assertThat(guard.isBusy()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
