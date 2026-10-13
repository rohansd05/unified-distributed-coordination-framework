package com.udcf.modules.matrix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpeedupBasisTest {

    @Test
    @DisplayName("the text names the JVM's available processors (not physical cores), the ceiling and small-size overhead")
    void text() {
        String text = new SpeedupBasis(() -> 8).text(5, 0);
        assertThat(text).contains("All 5 nodes run inside one Java process", "8 available processors (as reported by the JVM",
                "inside a container this can be lower", "cannot exceed 5", "speedup below 1", "measured on the node",
                "include JVM compile effects");
        assertThat(text).doesNotContain("physical");
        assertThat(text.chars().allMatch(c -> c < 128)).isTrue();
    }

    @Test
    @DisplayName("the processor count comes from the supplier and bounds the ceiling")
    void fromSupplier() {
        SpeedupBasis two = new SpeedupBasis(() -> 2);
        assertThat(two.processors()).isEqualTo(2);
        assertThat(two.ceiling(5)).isEqualTo(2);
        assertThat(two.ceiling(1)).isEqualTo(1);
        assertThat(two.text(5, 0)).contains("2 available processors", "cannot exceed 2");
        assertThat(new SpeedupBasis(() -> 1).text(1, 0)).contains("All 1 node run", "1 available processor (");
    }

    @Test
    @DisplayName("warm-up products are stated with their count")
    void warmup() {
        SpeedupBasis basis = new SpeedupBasis(() -> 4);
        assertThat(basis.text(3, 2)).contains("2 untimed warm-up products were run first").doesNotContain("compile effects");
        assertThat(basis.text(3, 1)).contains("1 untimed warm-up product was run first");
    }

    @Test
    @DisplayName("a processor count below 1, a rank count below 1 or a negative warm-up count is refused")
    void refused() {
        assertThatThrownBy(() -> new SpeedupBasis(() -> 0).processors()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SpeedupBasis(() -> 4).ceiling(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpeedupBasis(() -> 4).text(3, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
