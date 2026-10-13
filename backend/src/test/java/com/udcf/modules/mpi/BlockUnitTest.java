package com.udcf.modules.mpi;

import com.udcf.core.clock.LamportClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The block unit added in E10b: unit 1 is exactly Experiment 9's partition; a larger unit gives blocks of
 * whole units (Experiment 10 scatters an n x n matrix in whole rows); the request and the executor use it.
 */
class BlockUnitTest {

    private static final MpiProperties PROPERTIES = new MpiProperties(1000, 10_000, 20, 8, 300);
    private static final List<Integer> NODES = List.of(1, 2, 3, 4);
    private static final Set<Integer> LIVE = Set.of(1, 2, 3, 4);

    private static LongSupplier stepping() {
        AtomicLong now = new AtomicLong();
        return () -> now.getAndAdd(1000);
    }

    private static CollectiveResult run(CollectiveRequest request, ScriptedCollectiveTransport transport) {
        return new CollectiveExecutor(PROPERTIES, stepping()).execute(request, new LamportClock(), transport);
    }

    @Test
    @DisplayName("of(n, p, 1) equals of(n, p) for every n up to 40 and p up to n")
    void unitOneIsTheOldPartition() {
        for (int n = 1; n <= 40; n++) {
            for (int p = 1; p <= n; p++) {
                assertThat(BlockPartition.of(n, p, 1)).as("n=%d p=%d", n, p).isEqualTo(BlockPartition.of(n, p));
            }
        }
    }

    @Test
    @DisplayName("a unit scales the partition of the units: 100 values in units of 10 over 4 ranks are 30, 30, 20, 20 at 0, 30, 60, 80")
    void unitScalesBlocks() {
        assertThat(BlockPartition.of(100, 4, 10)).containsExactly(new BlockPartition.Block(0, 0, 30),
                new BlockPartition.Block(1, 30, 30), new BlockPartition.Block(2, 60, 20), new BlockPartition.Block(3, 80, 20));
        assertThat(BlockPartition.of(12, 3, 4)).extracting(BlockPartition.Block::length).containsExactly(4, 4, 4);
    }

    @Test
    @DisplayName("a unit below 1, n not a multiple of the unit, or fewer units than ranks is rejected")
    void badUnitsRejected() {
        assertThatThrownBy(() -> BlockPartition.of(10, 2, 0)).hasMessage("unit must be >= 1, was 0");
        assertThatThrownBy(() -> BlockPartition.of(10, 2, 3)).hasMessage("n must be a multiple of the unit: n = 10, unit = 3");
        assertThatThrownBy(() -> BlockPartition.of(30, 4, 10)).hasMessage("n must be at least the number of ranks: n = 3, p = 4");
    }

    @Test
    @DisplayName("the old seven-argument request has block unit 1; a unit below 1 or a count that is not a multiple is rejected")
    void requestUnit() {
        CollectiveRequest old = new CollectiveRequest(7, CollectiveOperation.SCATTER, 1, NODES, LIVE, 10, 42);
        assertThat(old.blockUnit()).isEqualTo(1);
        assertThat(old).isEqualTo(new CollectiveRequest(7, CollectiveOperation.SCATTER, 1, NODES, LIVE, 10, 42, 1));
        assertThatThrownBy(() -> new CollectiveRequest(7, CollectiveOperation.SCATTER, 1, NODES, LIVE, 10, 42, 0))
                .hasMessage("blockUnit must be >= 1, was 0");
        assertThatThrownBy(() -> new CollectiveRequest(7, CollectiveOperation.SCATTER, 1, NODES, LIVE, 10, 42, 3))
                .hasMessage("count must be a multiple of blockUnit: count 10, blockUnit 3");
    }

    @Test
    @DisplayName("scatter with unit 10: each rank gets whole units (30, 30, 20, 20); COMPLETE and the blocks tile the vector")
    void scatterInUnits() {
        ScriptedCollectiveTransport transport = new ScriptedCollectiveTransport(PROPERTIES, 1, 2, 3, 4);
        CollectiveResult result = run(new CollectiveRequest(7, CollectiveOperation.SCATTER, 1, NODES, LIVE, 100, 42, 10), transport);

        double[] vector = PayloadGenerator.vector(42, 100);
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        assertThat(result.transfers()).extracting(TransferRecord::offset).containsExactly(0, 30, 60, 80);
        assertThat(result.transfers()).extracting(TransferRecord::elementCount).containsExactly(30, 30, 20, 20);
        assertThat(transport.node(3).received(7)).get()
                .isEqualTo(BlockPartition.slice(vector, new BlockPartition.Block(2, 60, 20)));
        assertThat(result.verification().wholeVectorMatches()).isTrue();
    }

    @Test
    @DisplayName("gather with unit 10 contributes whole units; a broadcast ignores the unit")
    void gatherAndBroadcastInUnits() {
        CollectiveResult gather = run(new CollectiveRequest(7, CollectiveOperation.GATHER, 1, NODES, LIVE, 100, 42, 10),
                new ScriptedCollectiveTransport(PROPERTIES, 1, 2, 3, 4));
        assertThat(gather.gathered()).extracting(GatheredBlock::length).containsExactly(30, 30, 20, 20);
        assertThat(gather.assembled()).hasSize(100);

        CollectiveResult broadcast = run(new CollectiveRequest(8, CollectiveOperation.BROADCAST, 1, NODES, LIVE, 100, 42, 10),
                new ScriptedCollectiveTransport(PROPERTIES, 1, 2, 3, 4));
        assertThat(broadcast.transfers()).extracting(TransferRecord::elementCount).containsOnly(100);
    }

    @Test
    @DisplayName("fewer units than live participants is refused: 30 values in units of 10 over 4 ranks")
    void tooFewUnitsRefused() {
        ScriptedCollectiveTransport transport = new ScriptedCollectiveTransport(PROPERTIES, 1, 2, 3, 4);
        CollectiveResult result = run(new CollectiveRequest(7, CollectiveOperation.SCATTER, 1, NODES, LIVE, 30, 42, 10), transport);

        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.REFUSED);
        assertThat(result.refusal().reason()).isEqualTo(RefusalReason.COUNT_BELOW_PARTICIPANTS);
        assertThat(transport.sent()).isEmpty();
    }
}
