package com.udcf.scenario.scenarios;

import com.udcf.scenario.RunMemory;
import com.udcf.scenario.Scenario;
import com.udcf.scenario.ScenarioStep;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * "The leader dies mid-write": Experiment 8's synchronous stream runs on the leader elected by
 * Experiment 4; the leader node crashes; detection, election, promotion and the restored service
 * follow, and no confirmed update is lost.
 */
@Component
public class LeaderDiesMidWriteScenario implements Scenario {

    public static final String ID = "leader-dies-mid-write";

    private final FailoverSteps failover;
    private final List<ScenarioStep> steps;

    public LeaderDiesMidWriteScenario(FailoverSteps failover) {
        this.failover = failover;
        this.steps = List.of(failover.startSynchronousStream(), failover.crashLeader(), failover.detection(),
                failover.election(), failover.promotion(), failover.serviceRestored());
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "The leader dies mid-write";
    }

    @Override
    public String summary() {
        return "Updates are being written to the leader when it crashes. Watch the cluster notice, elect a new "
                + "leader and hand it the data, without losing a single confirmed update.";
    }

    @Override
    public List<String> modules() {
        return List.of("election", "replication", "faulttolerance");
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public List<ScenarioStep> steps() {
        return steps;
    }

    @Override
    public String cleanup(RunMemory memory) {
        return failover.stopStream(memory);
    }
}
