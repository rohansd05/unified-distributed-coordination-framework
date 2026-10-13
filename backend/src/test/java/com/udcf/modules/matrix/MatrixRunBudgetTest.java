package com.udcf.modules.matrix;

import com.udcf.modules.mpi.MpiProperties;
import com.udcf.modules.rmi.RmiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The worst-case run time and its terms, from the settings (bounds and allowances, never measurements). */
class MatrixRunBudgetTest {

    private static final MpiProperties MPI = new MpiProperties(100_000, 10_000, 20, 8, 300);

    @Test
    @DisplayName("the configured values: transfer 3 x (1000 + 3000) = 12000, allowance (1 + 1) x 2000, worst case 26001 ms")
    void configured() {
        MatrixRunBudget budget = MatrixRunBudget.of(RmiTestSupport.PROPERTIES, RmiTestSupport.TRANSPORT, MPI,
                new MatrixRunProperties(1, 2000, 4));

        assertThat(budget).isEqualTo(new MatrixRunBudget(1000, 3000, 10_000, 2000, 1));
        assertThat(budget.transferWorstMillis()).isEqualTo(12_000);
        assertThat(budget.computeWaitMillis()).isEqualTo(12_000);
        assertThat(budget.productAllowanceMillis()).isEqualTo(4000);
        assertThat(budget.worstCaseRunMillis()).isEqualTo(26_001);
    }

    @Test
    @DisplayName("every term counts: no warm-up gives 24001; a longer deadline or call timeout adds exactly its share")
    void terms() {
        assertThat(new MatrixRunBudget(1000, 3000, 10_000, 2000, 0).worstCaseRunMillis()).isEqualTo(24_001);
        assertThat(new MatrixRunBudget(1000, 3000, 20_000, 2000, 1).worstCaseRunMillis()).isEqualTo(36_001);
        assertThat(new MatrixRunBudget(1000, 4000, 10_000, 2000, 1).worstCaseRunMillis()).isEqualTo(29_001);
        assertThat(new MatrixRunBudget(1000, 3000, 10_000, 2500, 3).worstCaseRunMillis()).isEqualTo(10_000 + 10_000 + 12_000 + 1);
    }

    @Test
    @DisplayName("timeouts below 1 ms and a negative warm-up are rejected")
    void validation() {
        assertThatThrownBy(() -> new MatrixRunBudget(0, 3000, 10_000, 2000, 1)).hasMessage("every timeout must be >= 1 ms");
        assertThatThrownBy(() -> new MatrixRunBudget(1000, 3000, 10_000, 2000, -1)).hasMessage("warmupProducts must be >= 0, was -1");
    }
}
