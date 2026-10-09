package com.udcf.modules.faulttolerance;

import com.udcf.modules.replication.ApplyResult;
import com.udcf.modules.replication.ConsistencyModel;
import com.udcf.modules.replication.DataItem;
import com.udcf.modules.replication.WriteResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Validation of the E8b records: unknown values are null, never 0, and the pairs that go together do. */
class FailoverRecordsTest {

    private static WriteResult result() {
        return new WriteResult(new DataItem("setting-0001", "v1", 5, 2, 3), ConsistencyModel.ASYNCHRONOUS,
                ApplyResult.APPLIED, 0.4, 400, List.of(1, 3), new CompletableFuture<>());
    }

    @Test
    @DisplayName("an accepted update names its node, epoch and result; one that gave up names none")
    void updateOutcome() {
        UpdateOutcome accepted = UpdateOutcome.accepted(SystemUpdate.numbered(1), 2, 3, result());
        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.epoch()).isEqualTo(3L);
        assertThat(accepted.model()).isEqualTo(ConsistencyModel.ASYNCHRONOUS);
        UpdateOutcome gaveUp = UpdateOutcome.gaveUp(SystemUpdate.numbered(1), ConsistencyModel.SYNCHRONOUS, 40);
        assertThat(gaveUp.nodeId()).isNull();
        assertThat(gaveUp.epoch()).isNull();
        assertThat(gaveUp.result()).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> new UpdateOutcome(SystemUpdate.numbered(1),
                ConsistencyModel.SYNCHRONOUS, UpdateOutcome.Status.GAVE_UP, 2, null, 1, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new UpdateOutcome(SystemUpdate.numbered(1),
                ConsistencyModel.SYNCHRONOUS, UpdateOutcome.Status.ACCEPTED, 2, 3L, -1, result()));
    }

    @Test
    @DisplayName("a node cannot serve without acting as primary; unknown epoch and belief stay null")
    void nodeFailoverState() {
        NodeFailoverState unknown = new NodeFailoverState(4, "UP", false, false, false, null, null, null);
        assertThat(unknown.epoch()).isNull();
        assertThat(unknown.believedPrimaryId()).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> new NodeFailoverState(4, "UP", true, false, true, 3L,
                4, RejoinState.READY));
    }

    @Test
    @DisplayName("a snapshot has a primary epoch exactly when it has a primary, and copies its lists")
    void failoverSnapshot() {
        List<NodeFailoverState> nodes = new ArrayList<>();
        FailoverSnapshot empty = new FailoverSnapshot(false, FailoverPhase.STEADY, null, null, 1, nodes, null,
                List.of(), 0, 0);
        nodes.add(new NodeFailoverState(1, "UP", true, false, false, 1L, null, RejoinState.READY));
        assertThat(empty.nodes()).isEmpty();
        assertThat(empty.latestRun()).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> new FailoverSnapshot(true, FailoverPhase.STEADY, 1, null,
                2, List.of(), null, List.of(), 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new FailoverSnapshot(true, FailoverPhase.STEADY, null,
                null, 2, List.of(), null, List.of(), -1, 0));
    }

    @Test
    @DisplayName("role-query answers are copied, and describe keeps a null belief as null")
    void roleQueryAnswers() {
        List<RoleReport> reports = new ArrayList<>(List.of(new RoleReport(2, FailoverRole.BACKUP, 3, null)));
        RoleQuery.Answers answers = new RoleQuery.Answers(reports, List.of(4));
        reports.clear();
        assertThat(answers.reports()).hasSize(1);
        assertThat(answers.describe()).singleElement()
                .satisfies(entry -> assertThat(entry).containsEntry("believedPrimaryId", null).containsEntry("epoch", 3L));
    }
}
