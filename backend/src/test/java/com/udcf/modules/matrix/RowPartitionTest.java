package com.udcf.modules.matrix;

import com.udcf.modules.matrix.RowPartition.RowBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RowPartitionTest {

    @Test
    @DisplayName("n = p: one row each")
    void oneRowEach() {
        assertThat(RowPartition.of(4, 4)).containsExactly(new RowBlock(0, 0, 1, 4), new RowBlock(1, 1, 1, 4),
                new RowBlock(2, 2, 1, 4), new RowBlock(3, 3, 1, 4));
    }

    @Test
    @DisplayName("n = p + 1: the first rank gets the extra row")
    void oneExtraRow() {
        assertThat(RowPartition.of(5, 4)).extracting(RowBlock::rowCount).containsExactly(2, 1, 1, 1);
        assertThat(RowPartition.of(5, 4)).extracting(RowBlock::rowStart).containsExactly(0, 2, 3, 4);
    }

    @Test
    @DisplayName("n = 10 over p = 4: rows 3, 3, 2, 2 at element offsets 0, 30, 60, 80, lengths 30, 30, 20, 20")
    void uneven() {
        List<RowBlock> blocks = RowPartition.of(10, 4);
        assertThat(blocks).extracting(RowBlock::rank).containsExactly(0, 1, 2, 3);
        assertThat(blocks).extracting(RowBlock::rowCount).containsExactly(3, 3, 2, 2);
        assertThat(blocks).extracting(RowBlock::rowStart).containsExactly(0, 3, 6, 8);
        assertThat(blocks).extracting(RowBlock::elementOffset).containsExactly(0, 30, 60, 80);
        assertThat(blocks).extracting(RowBlock::elementLength).containsExactly(30, 30, 20, 20);
    }

    @Test
    @DisplayName("for every n up to 40 and p up to n, the blocks tile [0, n*n) in rank order with whole rows")
    void tiles() {
        for (int n = 1; n <= 40; n++) {
            for (int p = 1; p <= n; p++) {
                int next = 0;
                for (RowBlock block : RowPartition.of(n, p)) {
                    assertThat(block.elementOffset()).isEqualTo(next);
                    assertThat(block.elementLength() % n).isZero();
                    assertThat(block.rowCount()).isBetween(n / p, n / p + 1);
                    next = block.elementOffset() + block.elementLength();
                }
                assertThat(next).isEqualTo(n * n);
            }
        }
    }

    @Test
    @DisplayName("fewer rows than ranks is refused")
    void fewerRowsThanRanks() {
        assertThatThrownBy(() -> RowPartition.of(3, 4)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("n must be at least the number of ranks: n = 3, p = 4");
        assertThatThrownBy(() -> new RowBlock(0, 2, 2, 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rows copies exactly the block's rows")
    void rows() {
        double[] matrix = {0, 1, 2, 3, 4, 5, 6, 7, 8};
        assertThat(RowPartition.rows(matrix, new RowBlock(1, 1, 2, 3))).containsExactly(3, 4, 5, 6, 7, 8);
        assertThatThrownBy(() -> RowPartition.rows(new double[8], new RowBlock(0, 0, 1, 3)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
