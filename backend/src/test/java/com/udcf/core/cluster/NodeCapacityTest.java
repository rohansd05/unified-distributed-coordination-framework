package com.udcf.core.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the Experiment 6 capacity numbers from docs/HANDOFF.md Appendix B. */
class NodeCapacityTest {

    @Test
    @DisplayName("FAST has 4 threads and x1 work")
    void fast() {
        assertThat(NodeCapacity.FAST.threads()).isEqualTo(4);
        assertThat(NodeCapacity.FAST.workMultiplier()).isEqualTo(1);
    }

    @Test
    @DisplayName("MEDIUM has 2 threads and x2 work")
    void medium() {
        assertThat(NodeCapacity.MEDIUM.threads()).isEqualTo(2);
        assertThat(NodeCapacity.MEDIUM.workMultiplier()).isEqualTo(2);
    }

    @Test
    @DisplayName("SLOW has 1 thread and x4 work")
    void slow() {
        assertThat(NodeCapacity.SLOW.threads()).isEqualTo(1);
        assertThat(NodeCapacity.SLOW.workMultiplier()).isEqualTo(4);
    }
}
