package com.udcf.scenario;

import java.util.List;

/**
 * A guided, narrated demonstration on the real cluster: an ordered list of steps that call the
 * modules' own beans and report what they observed.
 *
 * <p>Adding a scenario is add-only: write a Spring {@code @Component} that implements this
 * interface; {@link ScenarioEngine} collects every such bean. Every run starts with a clean
 * cluster reset done by the engine, so a scenario never resets the cluster itself.</p>
 *
 * <p>No dedicated test: an interface; the engine's handling is tested in ScenarioEngineTest.</p>
 */
public interface Scenario {

    /** Stable id used in URLs, for example {@code "leader-dies-mid-write"}. */
    String id();

    /** Sentence-case title, for example "The leader dies mid-write". */
    String title();

    /** One or two plain sentences on what the audience will see. */
    String summary();

    /** Module ids the scenario uses, for example {@code ["election", "faulttolerance"]}. */
    List<String> modules();

    /** Position in the list of scenarios (ascending). */
    int order();

    /** The steps, in order. The same list on every call. */
    List<ScenarioStep> steps();

    /**
     * Runs after the last step in a finally, also after a failed step or an unexpected error (for
     * example: stop the update stream, stop a sampler). Never a numbered step.
     *
     * @return one sentence for the end state, or null if there was nothing to clean up
     */
    default String cleanup(RunMemory memory) {
        return null;
    }
}
