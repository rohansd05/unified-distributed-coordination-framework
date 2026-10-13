package com.udcf.modules.mpi;

import com.udcf.modules.mpi.BlockPartition.Block;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockPartitionTest {

    @Test
    @DisplayName("n = 10 over p = 4: lengths 3, 3, 2, 2 at offsets 0, 3, 6, 8")
    void tenOverFour() {
        assertThat(BlockPartition.of(10, 4)).containsExactly(
                new Block(0, 0, 3), new Block(1, 3, 3), new Block(2, 6, 2), new Block(3, 8, 2));
    }

    @Test
    @DisplayName("n = p gives one element each; p = 1 gives everything to rank 0")
    void edges() {
        assertThat(BlockPartition.of(4, 4)).extracting(Block::length).containsExactly(1, 1, 1, 1);
        assertThat(BlockPartition.of(7, 1)).containsExactly(new Block(0, 0, 7));
    }

    @Test
    @DisplayName("n = 3 over p = 4 is rejected: a rank would get nothing")
    void fewerElementsThanRanks() {
        assertThatThrownBy(() -> BlockPartition.of(3, 4)).hasMessage("n must be at least the number of ranks: n = 3, p = 4");
        assertThatThrownBy(() -> BlockPartition.of(3, 0)).hasMessage("p must be >= 1, was 0");
        assertThatThrownBy(() -> new Block(0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("for many n and p the blocks tile [0, n) in rank order and differ in length by at most one")
    void blocksTile() {
        for (int p = 1; p <= 7; p++) {
            for (int n = p; n <= 40; n++) {
                List<Block> blocks = BlockPartition.of(n, p);
                int next = 0;
                for (int rank = 0; rank < p; rank++) {
                    Block block = blocks.get(rank);
                    assertThat(block.rank()).isEqualTo(rank);
                    assertThat(block.offset()).isEqualTo(next);
                    assertThat(block.length()).isBetween(n / p, n / p + 1);
                    next = block.end();
                }
                assertThat(next).isEqualTo(n);
            }
        }
    }

    @Test
    @DisplayName("slice copies the block's part; the slices concatenate to the original")
    void sliceCopies() {
        double[] values = PayloadGenerator.vector(5, 10);
        List<Block> blocks = BlockPartition.of(10, 4);
        double[] joined = new double[10];
        for (Block block : blocks) {
            double[] part = BlockPartition.slice(values, block);
            System.arraycopy(part, 0, joined, block.offset(), part.length);
        }
        assertThat(joined).isEqualTo(values);
        double[] first = BlockPartition.slice(values, blocks.get(0));
        first[0] = 0;
        assertThat(values[0]).isNotEqualTo(0);
        assertThatThrownBy(() -> BlockPartition.slice(new double[2], new Block(0, 1, 2))).isInstanceOf(IllegalArgumentException.class);
    }
}
