package com.udcf.modules.matrix;

import com.udcf.modules.matrix.MatrixTiming.RankCompute;
import com.udcf.modules.matrix.RowPartition.RowBlock;
import com.udcf.modules.mpi.CollectiveOutcome;
import com.udcf.modules.mpi.VectorChecksum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rules of the records that carry them (null never 0, holes, copies). */
class MatrixRecordsTest {

    private static final RowBlock BLOCK = new RowBlock(0, 0, 1, 2);
    private static final String SUM = VectorChecksum.of(new double[]{1, 2});
    private static final String OTHER = VectorChecksum.of(new double[]{3});

    @Test
    @DisplayName("ResultBlock: a hole has neither values nor checksum, values are copied in and out, length checked")
    void resultBlock() {
        ResultBlock hole = ResultBlock.hole(BLOCK, 1);
        assertThat(hole.hole()).isTrue();
        assertThat(hole.values()).isNull();
        assertThat(hole.reportedChecksum()).isNull();
        assertThat(hole.toString()).contains("values=null");

        double[] values = {1, 2};
        ResultBlock block = new ResultBlock(BLOCK, 1, values, SUM);
        values[0] = 99;
        assertThat(block.values()).containsExactly(1, 2);
        block.values()[1] = 99;
        assertThat(block.values()).containsExactly(1, 2);
        assertThat(block).isEqualTo(new ResultBlock(BLOCK, 1, new double[]{1, 2}, SUM)).hasSameHashCodeAs(new ResultBlock(BLOCK, 1, new double[]{1, 2}, SUM));
        assertThat(block.toString()).contains("2 values");

        assertThatThrownBy(() -> new ResultBlock(BLOCK, 1, new double[]{1, 2}, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResultBlock(BLOCK, 1, null, SUM)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResultBlock(BLOCK, 1, new double[3], SUM)).hasMessage("block 0 must have 2 values, has 3");
        assertThatThrownBy(() -> new ResultBlock(BLOCK, 0, null, null)).hasMessage("nodeId must be >= 1, was 0");
    }

    @Test
    @DisplayName("MatrixVerification.of derives passed and checksumsMatch; the constructor refuses every inconsistency")
    void verification() {
        MatrixVerification complete = MatrixVerification.of(2, 2, 0, 0, 0, SUM, SUM);
        assertThat(complete.passed()).isTrue();
        assertThat(complete.checksumsMatch()).isTrue();
        assertThat(MatrixVerification.of(2, 1, 1, 0, 0, null, SUM).passed()).isNull();
        assertThat(MatrixVerification.of(2, 2, 0, 0, 0, OTHER, SUM).passed()).isFalse();

        assertThatThrownBy(() -> new MatrixVerification(2, 2, 0, 0, 4, 0, SUM, SUM, true, null))
                .hasMessageContaining("passed must be");
        assertThatThrownBy(() -> new MatrixVerification(2, 1, 1, 0, 2, 0, SUM, SUM, true, null))
                .hasMessageContaining("known exactly when no block is missing");
        assertThatThrownBy(() -> new MatrixVerification(2, 2, 0, 0, 4, 0, OTHER, SUM, true, false))
                .hasMessageContaining("compare the two checksums");
        assertThatThrownBy(() -> new MatrixVerification(2, 2, 1, 0, 4, 0, null, SUM, null, null))
                .hasMessageContaining("every row arrived");
        assertThatThrownBy(() -> new MatrixVerification(2, 2, 0, 0, 3, 0, SUM, SUM, true, true))
                .hasMessageContaining("inconsistent counts");
        assertThatThrownBy(() -> new MatrixVerification(2, 2, 0, 0, 4, 5, SUM, SUM, true, false))
                .hasMessageContaining("inconsistent counts");
        assertThatThrownBy(() -> MatrixVerification.of(2, 2, 0, 0, 0, SUM, "not a checksum"))
                .hasMessageContaining("sequentialChecksum must be a checksum");
    }

    @Test
    @DisplayName("Assembled: never REFUSED, a product exactly when nothing is missing, copied out")
    void assembled() {
        MatrixVerification complete = MatrixVerification.of(2, 2, 0, 0, 0, SUM, SUM);
        MatrixVerification missing = MatrixVerification.of(2, 1, 1, 0, 0, null, SUM);
        MatrixAssembly.Assembled ok = new MatrixAssembly.Assembled(new double[]{1, 2, 3, 4}, complete, CollectiveOutcome.COMPLETE);
        ok.product()[0] = 99;
        assertThat(ok.product()[0]).isEqualTo(1);
        assertThatThrownBy(() -> new MatrixAssembly.Assembled(null, complete, CollectiveOutcome.COMPLETE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatrixAssembly.Assembled(new double[4], missing, CollectiveOutcome.PARTIAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatrixAssembly.Assembled(null, missing, CollectiveOutcome.REFUSED))
                .hasMessage("a refused run is never assembled");
    }

    @Test
    @DisplayName("RankCompute and MatrixRefusal: unknown compute is null, never negative; no null reason or message")
    void smallRecords() {
        assertThat(new RankCompute(0, 1, 3, null).computeNanos()).isNull();
        assertThatThrownBy(() -> new RankCompute(0, 1, 3, -1L)).hasMessage("computeNanos must be >= 0, was -1");
        assertThatThrownBy(() -> new RankCompute(0, 0, 3, 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RankCompute(0, 1, 0, 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatrixRefusal(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MatrixRefusal(MatrixRefusal.Reason.SIZE_BELOW_MINIMUM, null)).isInstanceOf(NullPointerException.class);
    }
}
