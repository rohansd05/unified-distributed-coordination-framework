package com.udcf.integration;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterResetService;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.module.ModuleBusyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import static org.awaitility.Awaitility.await;

/**
 * Shared base of the Phase 9A link tests (step 9A.2): the real backend with five nodes on real sockets on
 * 127.0.0.1, driven through the modules' own Java entry points (the methods the controllers call) and checked
 * only on observable state: events on the bus, counters and served overviews.
 *
 * <p>Each subclass has its own context and its own port bases in Nidhi's 9A.2 block 27100-27899 (below
 * 32768, hard rule 10), so a socket still closing after one class can never collide with the next. The
 * context is closed after each class, which stops every node service.</p>
 *
 * <p>Waiting is always a poll with a deadline ({@link #DEADLINE}, generous for CI), never a fixed sleep.</p>
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class IntegrationTestSupport {

    /** Upper bound of every wait; well above Experiment 8's worst failover (about 10.3 s). */
    static final Duration DEADLINE = Duration.ofSeconds(30);

    @Autowired
    Cluster cluster;

    @Autowired
    ClusterEventBus bus;

    @Autowired
    ClusterResetService resetService;

    /** Clean slate. A reset is refused while a module is BUSY (for example a recovery election): retry. */
    void resetCluster() {
        await().atMost(DEADLINE).pollInterval(Duration.ofMillis(200)).ignoreException(ModuleBusyException.class)
                .until(() -> {
                    resetService.reset();
                    return true;
                });
    }

    /** Every event the bus holds, in arrival (sequence) order. */
    List<ClusterEvent> allEvents() {
        return bus.query(null, null, Integer.MAX_VALUE).stream()
                .sorted(Comparator.comparingLong(ClusterEvent::sequence)).toList();
    }

    /** The events of {@code module} and {@code type}, in arrival order. */
    List<ClusterEvent> events(String module, String type) {
        return allEvents().stream().filter(e -> e.module().equals(module) && e.type().equals(type)).toList();
    }

    /** Polls until at least one event of {@code module} and {@code type} matches, then returns the first. */
    ClusterEvent awaitEvent(String module, String type, Predicate<ClusterEvent> match) {
        return await().atMost(DEADLINE).until(() -> events(module, type).stream().filter(match).findFirst(),
                found -> found.isPresent()).orElseThrow();
    }

    static long number(ClusterEvent event, String key) {
        Object value = event.data().get(key);
        if (!(value instanceof Number n)) {
            throw new AssertionError(event.type() + " has no numeric data." + key + ": " + event.data());
        }
        return n.longValue();
    }
}
