package com.udcf.modules.matrix;

import com.udcf.modules.matrix.dto.GlossaryEntryDto;
import com.udcf.modules.matrix.dto.GlossaryEntryDto.Kind;
import com.udcf.modules.matrix.dto.MatrixTimingDto;
import com.udcf.modules.mpi.CollectiveOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The honesty texts: ASCII, exact wording where it carries values, and a glossary entry for every timing figure. */
class MatrixTextsTest {

    private static List<String> allTexts() {
        List<String> texts = new ArrayList<>(List.of(MatrixTexts.OTHER_ACTIVITY, MatrixTexts.PHASE_TIME_LABEL,
                MatrixTexts.PAYLOAD_GENERATION_NOTE, MatrixTexts.FIRST_RUN_NOTE, MatrixTexts.COMPUTE_ROUND_TRIP_BASIS,
                MatrixTexts.GATHER_LATENCY_BASIS, MatrixTexts.COLLECTIVE_IDS_NOTE, MatrixTexts.ALGORITHM_NOTE,
                MatrixTexts.CO_HOSTING.reason(), MatrixTexts.SERIES_RULE, MatrixTexts.RANKS_NOTE, MatrixTexts.WITHHELD_MISMATCH,
                MatrixTexts.fairness(0), MatrixTexts.fairness(1), MatrixTexts.fairness(2),
                MatrixTexts.experimentTwoSideEffect(7201, 7205), MatrixTexts.withheld(CollectiveOutcome.PARTIAL),
                MatrixTexts.withheld(CollectiveOutcome.FAILED), MatrixTexts.speedupBasis(MatrixTestResults.BASIS, 5, 1)));
        for (GlossaryEntryDto entry : MatrixTexts.GLOSSARY) {
            texts.add(entry.id());
            texts.add(entry.label());
            texts.add(entry.definition());
        }
        return texts;
    }

    @Test
    @DisplayName("every text and glossary entry is non-blank, single-line ASCII")
    void ascii() {
        for (String text : allTexts()) {
            assertThat(text).isNotBlank().doesNotContain("\n");
            assertThat(text.chars().allMatch(c -> c >= 32 && c < 127)).as(text).isTrue();
        }
        assertThat(MatrixTexts.CO_HOSTING.simulated()).isTrue();
    }

    @Test
    @DisplayName("the glossary has one entry per timing figure, with where it was measured or that it is derived")
    void glossaryCoversEveryFigure() {
        Map<String, Kind> kinds = MatrixTexts.GLOSSARY.stream()
                .collect(Collectors.toMap(GlossaryEntryDto::id, GlossaryEntryDto::kind));
        assertThat(kinds).hasSameSizeAs(MatrixTexts.GLOSSARY);   // ids are unique

        Function<Class<?>, List<String>> names = type -> Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName).toList();
        for (String measured : names.apply(MatrixTimingDto.Measured.class)) {
            assertThat(kinds).as(measured).containsEntry(measured, Kind.MEASURED_ON_ROOT);
        }
        for (String derived : names.apply(MatrixTimingDto.Derived.class)) {
            if (!derived.equals("speedupCeiling") && !derived.equals("speedupWithheldReason")) {
                assertThat(kinds).as(derived).containsEntry(derived, Kind.DERIVED);
            }
        }
        assertThat(kinds).containsEntry("computeNanos", Kind.MEASURED_ON_NODE)
                .containsEntry("roundTripNanos", Kind.MEASURED_ON_ROOT)
                .containsEntry("latencyNanos", Kind.MEASURED_ON_ROOT)
                .containsEntry("timeToFailureNanos", Kind.MEASURED_ON_ROOT);
    }

    @Test
    @DisplayName("texts with values: fairness, the Experiment 2 ports, the withheld reason, the collective id base")
    void textsWithValues() {
        assertThat(MatrixTexts.fairness(1)).isEqualTo("The timed sequential product runs after 1 warm-up product and the timed "
                + "parallel run after 2 full products (the warm-up and the sequential product), so the parallel run meets a "
                + "slightly warmer JIT and processor cache than the sequential product did.");
        assertThat(MatrixTexts.fairness(0)).contains("after 0 warm-up products").contains("after 1 full product (the "
                + "sequential product)");
        assertThat(MatrixTexts.experimentTwoSideEffect(7201, 7205)).contains("on its requests port (7201 to 7205 here)")
                .contains("a cluster reset does not either; a listener closes only when its node crashes or the backend stops");
        assertThat(MatrixTexts.withheld(CollectiveOutcome.PARTIAL))
                .isEqualTo("No speedup: the run was partial, so its parallel time is not the time of the whole product.");
        assertThat(MatrixTexts.COLLECTIVE_IDS_NOTE).startsWith("A run's scatter of A and broadcast of B use collective ids "
                + "1000000000 + 2 x run id and that + 1");
    }
}
