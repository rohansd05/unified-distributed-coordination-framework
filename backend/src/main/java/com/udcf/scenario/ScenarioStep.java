package com.udcf.scenario;

import java.time.Duration;
import java.util.Objects;

/**
 * One step of a {@link Scenario}: a plain narration sentence for the audience, and a body that
 * performs an action and waits for its expected consequence.
 *
 * <p>No dedicated test: a record; the engine's handling is tested in ScenarioEngineTest.</p>
 *
 * @param narration static, sentence case, no numbers (numbers come from observation, in the
 *                  step's message and facts)
 * @param deadline  how long the step's waits may take in total; null for the configured default
 * @param body      the action and the expectation
 */
public record ScenarioStep(String narration, Duration deadline, StepBody body) {

    /**
     * The action and expectation of a step. It returns the step's message, built only from what
     * it observed; it signals an unmet expectation with {@link StepContext#fail(String)}.
     */
    @FunctionalInterface
    public interface StepBody {
        String run(StepContext step) throws Exception;
    }

    public ScenarioStep {
        if (narration == null || narration.isBlank()) {
            throw new IllegalArgumentException("narration must not be blank");
        }
        Objects.requireNonNull(body, "body must not be null");
        if (deadline != null && (deadline.isNegative() || deadline.isZero())) {
            throw new IllegalArgumentException("deadline must be positive");
        }
    }

    /** A step with the configured default deadline. */
    public static ScenarioStep of(String narration, StepBody body) {
        return new ScenarioStep(narration, null, body);
    }
}
