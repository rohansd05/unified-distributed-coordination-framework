package com.udcf.modules.mpi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The generator against reference values computed outside Java: a short Python script (SplitMix64
 * with 64-bit masks, the same double mapping and seed derivation), quoted in the E9a section of
 * docs/tracks/track-c-jai.md. The SplitMix64 outputs for seed 0 are also the published reference.
 */
class PayloadGeneratorTest {

    @Test
    @DisplayName("SplitMix64 gives the reference outputs for seed 0: e220a8397b1dcdaf, 6e789e6aa1b965f4")
    void splitMix64ReferenceOutputs() {
        assertThat(PayloadGenerator.mix(PayloadGenerator.GOLDEN_GAMMA)).isEqualTo(0xe220a8397b1dcdafL);
        assertThat(PayloadGenerator.mix(2 * PayloadGenerator.GOLDEN_GAMMA)).isEqualTo(0x6e789e6aa1b965f4L);
    }

    @Test
    @DisplayName("vector(42, 3) is exactly the Python reference")
    void vectorMatchesPython() {
        assertThat(PayloadGenerator.vector(42, 3))
                .containsExactly(483.1297575436465, -680.1792142461597, -442.79773948972263);
    }

    @Test
    @DisplayName("a contribution uses seed ^ (nodeId * gamma): node 2, 2 values, seed 42 is the Python reference")
    void contributionMatchesPython() {
        assertThat(PayloadGenerator.contributionSeed(42, 2)).isEqualTo(0x3c6ef372fe94f800L);
        assertThat(PayloadGenerator.contribution(2, 2, 42)).containsExactly(-251.37171903782416, -208.8065498773675);
    }

    @Test
    @DisplayName("the same arguments always give the same values; another seed or node gives others")
    void deterministicAndDistinct() {
        assertThat(PayloadGenerator.vector(7, 50)).isEqualTo(PayloadGenerator.vector(7, 50));
        assertThat(PayloadGenerator.vector(7, 50)).isNotEqualTo(PayloadGenerator.vector(8, 50));
        assertThat(PayloadGenerator.contribution(1, 50, 7)).isNotEqualTo(PayloadGenerator.contribution(2, 50, 7));
        assertThat(PayloadGenerator.vector(7, 50)).startsWith(PayloadGenerator.vector(7, 10));
    }

    @Test
    @DisplayName("every value lies in [-1000, 1000) and is finite")
    void valuesInRange() {
        double[] values = PayloadGenerator.vector(123456789L, 10_000);
        assertThat(Arrays.stream(values).allMatch(v -> Double.isFinite(v) && v >= -1000.0 && v < 1000.0)).isTrue();
        assertThat(PayloadGenerator.toDouble(0L)).isEqualTo(-1000.0);
        assertThat(PayloadGenerator.toDouble(-1L)).isLessThan(1000.0);
    }

    @Test
    @DisplayName("a negative count or a node id below 1 is rejected; count 0 is empty")
    void invalidArguments() {
        assertThat(PayloadGenerator.vector(1, 0)).isEmpty();
        assertThatThrownBy(() -> PayloadGenerator.vector(1, -1)).hasMessage("count must be >= 0, was -1");
        assertThatThrownBy(() -> PayloadGenerator.contribution(0, 1, 1)).hasMessage("nodeId must be >= 1, was 0");
    }
}
