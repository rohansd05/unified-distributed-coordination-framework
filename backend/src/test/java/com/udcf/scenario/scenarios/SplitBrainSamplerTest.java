package com.udcf.scenario.scenarios;

import com.udcf.modules.faulttolerance.dto.SplitBrainDto;
import com.udcf.modules.faulttolerance.dto.ViolationDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SplitBrainSamplerTest {

    private static final ViolationDto DUPLICATE = new ViolationDto("DUPLICATE_PRIMARY", List.of(4, 5), 3, 3,
            "Nodes 4 and 5 both act as primary at epoch 3");

    private static SplitBrainDto passed(List<Integer> primaries) {
        return new SplitBrainDto(true, 3L, primaries, List.of(), 0);
    }

    @Test
    @DisplayName("counts samples and the most live primaries, keeps distinct violations, and counts a throwing read as an error")
    void summarisesSamples() {
        Deque<Object> script = new ArrayDeque<>(List.of(passed(List.of(5)), passed(List.of()),
                new SplitBrainDto(false, 3L, List.of(4, 5), List.of(DUPLICATE), 1),
                new SplitBrainDto(false, 3L, List.of(4, 5), List.of(DUPLICATE), 1),
                new IllegalStateException("node going down")));
        SplitBrainSampler sampler = new SplitBrainSampler(() -> {
            Object next = script.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (SplitBrainDto) next;
        }, 1000, System::nanoTime);

        for (int i = 0; i < 5; i++) {
            sampler.sample();
        }
        SplitBrainSampler.Summary summary = sampler.summary();

        assertThat(summary.samples()).isEqualTo(4);
        assertThat(summary.maxLivePrimaries()).isEqualTo(2);
        assertThat(summary.violations()).containsExactly(DUPLICATE.message());
        assertThat(summary.errors()).isEqualTo(1);
    }

    @Test
    @DisplayName("samples on its own daemon thread until stopped; stop is idempotent and the thread ends")
    void samplesUntilStopped() {
        AtomicInteger reads = new AtomicInteger();
        SplitBrainSampler sampler = new SplitBrainSampler(() -> {
            reads.incrementAndGet();
            return passed(List.of(5));
        }, 5, System::nanoTime);

        sampler.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> reads.get() >= 3);
        SplitBrainSampler.Summary first = sampler.stop();
        SplitBrainSampler.Summary again = sampler.stop();

        assertThat(first.samples()).isGreaterThanOrEqualTo(3);
        assertThat(first.maxLivePrimaries()).isEqualTo(1);
        assertThat(first.violations()).isEmpty();
        assertThat(again.samples()).isEqualTo(first.samples());
        assertThat(again.elapsedMillis()).isEqualTo(first.elapsedMillis());
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(t -> t.getName().equals(SplitBrainSampler.THREAD)
                && t.isAlive());
    }
}
