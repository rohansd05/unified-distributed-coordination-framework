package com.udcf.modules.faulttolerance;

import com.udcf.modules.faulttolerance.FailoverRoleSelector.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The module-local primary selector (lowest eligible id, README section 6). Pure. */
class FailoverRoleSelectorTest {

    @Test
    @DisplayName("picks the lowest eligible node id")
    void picksLowestEligible() {
        List<Candidate> candidates = List.of(new Candidate(3, true), new Candidate(1, false), new Candidate(2, true),
                new Candidate(5, true));
        assertThat(FailoverRoleSelector.select(candidates, Set.of())).contains(2);
    }

    @Test
    @DisplayName("never picks an excluded node, such as the failed primary")
    void skipsExcluded() {
        List<Candidate> candidates = List.of(new Candidate(1, true), new Candidate(2, true), new Candidate(3, true));
        assertThat(FailoverRoleSelector.select(candidates, Set.of(1, 2))).contains(3);
    }

    @Test
    @DisplayName("empty when no node is eligible and not excluded")
    void emptyWhenNone() {
        assertThat(FailoverRoleSelector.select(List.of(new Candidate(1, false), new Candidate(2, true)), Set.of(2)))
                .isEqualTo(Optional.empty());
        assertThat(FailoverRoleSelector.select(List.of(), Set.of())).isEmpty();
    }
}
