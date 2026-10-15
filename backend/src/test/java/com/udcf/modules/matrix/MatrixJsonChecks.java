package com.udcf.modules.matrix;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test-only checks of the Experiment 10 wire format: no JSON number a JavaScript number cannot hold exactly,
 * no NaN or infinity, and no seed derived from the user's seed (in decimal, unsigned or hex form) anywhere in
 * a body.
 */
final class MatrixJsonChecks {

    static final BigInteger MAX_SAFE = BigInteger.valueOf((1L << 53) - 1);

    private MatrixJsonChecks() {
    }

    /** Every number in {@code node} is an integer within +-(2^53 - 1) or a finite double. */
    static void assertSafeNumbers(JsonNode node) {
        if (node.isIntegralNumber()) {
            assertThat(node.bigIntegerValue().abs()).as("integer %s", node).isLessThanOrEqualTo(MAX_SAFE);
        } else if (node.isNumber()) {
            assertThat(Double.isFinite(node.doubleValue())).as("number %s", node).isTrue();
        } else if (node.isContainerNode()) {
            if (node.isObject()) {
                for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                    assertSafeNumbers(it.next().getValue());
                }
            } else {
                node.forEach(MatrixJsonChecks::assertSafeNumbers);
            }
        }
    }

    /** No seed derived from any of {@code userSeeds} appears in {@code body}, in any common form. */
    static void assertNoDerivedSeed(String body, List<Long> userSeeds) {
        for (long seed : userSeeds) {
            for (long derived : new long[] {MatrixGenerator.seedA(seed), MatrixGenerator.seedB(seed)}) {
                assertThat(body).as("derived seed of %d", seed)
                        .doesNotContain(Long.toString(derived))
                        .doesNotContain(Long.toUnsignedString(derived))
                        .doesNotContainIgnoringCase(Long.toHexString(derived));
            }
        }
        assertThat(body).doesNotContain("seedA").doesNotContain("seedB");
    }
}
