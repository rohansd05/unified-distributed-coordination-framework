package com.udcf.modules.mpi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checksums against values computed outside Java (Python: hashlib.sha256 over struct.pack('>d', x)
 * of each element; the script is quoted in the E9a section of docs/tracks/track-c-jai.md).
 */
class VectorChecksumTest {

    @Test
    @DisplayName("SHA-256 over big-endian IEEE-754 bits matches Python for [1.0, -2.5, 0.1] and for vector(42, 3)")
    void matchesPython() {
        assertThat(VectorChecksum.of(new double[] {1.0, -2.5, 0.1}))
                .isEqualTo("6f832defc151a64dca84cd818b05520644ea11ea684ca73d358db618a5cc09ba");
        assertThat(VectorChecksum.of(PayloadGenerator.vector(42, 3)))
                .isEqualTo("5f1b64cf99f6cdb58eb25806ebba71ba679616a9a96c76d9b517872850d3e047");
        assertThat(VectorChecksum.of(PayloadGenerator.contribution(2, 2, 42)))
                .isEqualTo("4056f0d181a7ebe6ce20dfe4981408784dee8ca4c94e3088d850afd82dba1419");
    }

    @Test
    @DisplayName("an empty vector has the checksum of no bytes")
    void emptyVector() {
        assertThat(VectorChecksum.of(new double[0])).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    @DisplayName("the order and every bit count: swapping two values or flipping one bit changes it")
    void orderAndBitsCount() {
        String base = VectorChecksum.of(new double[] {1.0, 2.0});
        assertThat(VectorChecksum.of(new double[] {2.0, 1.0})).isNotEqualTo(base);
        assertThat(VectorChecksum.of(new double[] {1.0, Math.nextUp(2.0)})).isNotEqualTo(base);
        assertThat(VectorChecksum.of(new double[] {0.0})).isNotEqualTo(VectorChecksum.of(new double[] {-0.0}));
    }

    @Test
    @DisplayName("isChecksum accepts exactly 64 lower-case hex characters")
    void isChecksum() {
        String good = VectorChecksum.of(new double[] {1.0});
        assertThat(VectorChecksum.isChecksum(good)).isTrue();
        assertThat(VectorChecksum.isChecksum(good.toUpperCase())).isFalse();
        assertThat(VectorChecksum.isChecksum(good.substring(1))).isFalse();
        assertThat(VectorChecksum.isChecksum(null)).isFalse();
    }
}
