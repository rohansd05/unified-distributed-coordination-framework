package com.udcf.modules.mpi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RankAssignmentTest {

    @Test
    @DisplayName("participants 1-5, live {1,2,4,5}, root 4: ranks are nodes 1, 2, 4, 5, the root is rank 2, node 3 is skipped")
    void workedExample() {
        RankAssignment ranks = RankAssignment.assign(List.of(1, 2, 3, 4, 5), Set.of(1, 2, 4, 5), 4);
        assertThat(ranks.refusal()).isEmpty();
        assertThat(ranks.nodes()).containsExactly(1, 2, 4, 5);
        assertThat(ranks.rootRank()).isEqualTo(2);
        assertThat(ranks.skipped()).containsExactly(3);
        assertThat(ranks.size()).isEqualTo(4);
    }

    @Test
    @DisplayName("ranks follow ascending node id whatever the input order")
    void orderIndependent() {
        RankAssignment ranks = RankAssignment.assign(List.of(5, 3, 1, 4), Set.of(1, 3, 4, 5), 5);
        assertThat(ranks.nodes()).containsExactly(1, 3, 4, 5);
        assertThat(ranks.rootRank()).isEqualTo(3);
    }

    @Test
    @DisplayName("a root that is down refuses the collective with ROOT_DOWN, and still lists the skipped nodes")
    void rootDown() {
        RankAssignment ranks = RankAssignment.assign(List.of(1, 2, 3), Set.of(1, 2), 3);
        assertThat(ranks.refusal()).get().extracting(Refusal::reason).isEqualTo(RefusalReason.ROOT_DOWN);
        assertThat(ranks.refusal().get().message()).isEqualTo("The root, node 3, is down.");
        assertThat(ranks.nodes()).isEmpty();
        assertThat(ranks.skipped()).containsExactly(3);
        assertThatThrownBy(ranks::rootRank).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a root that is not a participant refuses with ROOT_NOT_PARTICIPANT; no participant refuses with NO_PARTICIPANTS")
    void rootNotParticipantAndEmpty() {
        assertThat(RankAssignment.assign(List.of(1, 2), Set.of(1, 2, 6), 6).refusal()).get()
                .extracting(Refusal::reason).isEqualTo(RefusalReason.ROOT_NOT_PARTICIPANT);
        assertThat(RankAssignment.assign(List.of(), Set.of(1), 1).refusal()).get()
                .extracting(Refusal::reason).isEqualTo(RefusalReason.NO_PARTICIPANTS);
    }

    @Test
    @DisplayName("the root alone is a valid collective of one rank")
    void rootAlone() {
        RankAssignment ranks = RankAssignment.assign(List.of(2, 3), Set.of(2), 2);
        assertThat(ranks.nodes()).containsExactly(2);
        assertThat(ranks.rootRank()).isZero();
        assertThat(ranks.skipped()).containsExactly(3);
    }

    @Test
    @DisplayName("a duplicate, null or non-positive participant is a programming error")
    void invalidParticipants() {
        assertThatThrownBy(() -> RankAssignment.assign(List.of(1, 1), Set.of(1), 1)).hasMessage("participant 1 is listed twice");
        assertThatThrownBy(() -> RankAssignment.assign(List.of(0), Set.of(1), 1))
                .hasMessage("every participant must be a node id >= 1, was 0");
        assertThatThrownBy(() -> RankAssignment.assign(Arrays.asList(1, null), Set.of(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
