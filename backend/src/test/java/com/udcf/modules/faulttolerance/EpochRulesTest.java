package com.udcf.modules.faulttolerance;

import com.udcf.modules.replication.ApplyResult;
import com.udcf.modules.replication.DataItem;
import com.udcf.modules.replication.DataStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.udcf.modules.faulttolerance.FailoverRole.BACKUP;
import static com.udcf.modules.faulttolerance.FailoverRole.PRIMARY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Experiment 8 epoch rules as pure functions. */
class EpochRulesTest {

    @Test
    @DisplayName("a stale epoch is refused, an equal one accepted, a higher one adopted")
    void judgesIncomingEpochs() {
        assertThat(EpochRules.judge(3, 2)).isEqualTo(EpochVerdict.REFUSE_STALE);
        assertThat(EpochRules.judge(3, 3)).isEqualTo(EpochVerdict.ACCEPT);
        assertThat(EpochRules.judge(3, 4)).isEqualTo(EpochVerdict.ADOPT_HIGHER);
    }

    @Test
    @DisplayName("epochs never decrease whatever is observed")
    void epochsNeverDecrease() {
        long known = 1;
        for (long observed : new long[] {3, 2, 1, 5, 4, 5}) {
            long after = EpochRules.afterObserving(known, observed);
            assertThat(after).isGreaterThanOrEqualTo(known).isGreaterThanOrEqualTo(observed);
            known = after;
        }
        assertThat(known).isEqualTo(5);
    }

    @Test
    @DisplayName("only a primary demotes, and only on a strictly higher epoch")
    void demotionOnHigherEpoch() {
        assertThat(EpochRules.mustDemote(PRIMARY, 2, 3)).isTrue();
        assertThat(EpochRules.mustDemote(PRIMARY, 2, 2)).isFalse();
        assertThat(EpochRules.mustDemote(PRIMARY, 2, 1)).isFalse();
        assertThat(EpochRules.mustDemote(BACKUP, 2, 3)).isFalse();
    }

    @Test
    @DisplayName("epochs below the initial epoch are rejected")
    void rejectsEpochsBelowInitial() {
        assertThatThrownBy(() -> EpochRules.judge(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EpochRules.judge(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EpochRules.afterObserving(1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EpochRules.mustDemote(PRIMARY, 0, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("judge agrees with the shared replication store's epoch fence")
    void judgeMatchesDataStoreFence() {
        for (long known = 1; known <= 4; known++) {
            for (long incoming = 1; incoming <= 4; incoming++) {
                DataStore store = new DataStore();
                store.observeEpoch(known);
                ApplyResult result = store.apply(new DataItem("k", "v", 1, 1, incoming), incoming);
                EpochVerdict verdict = EpochRules.judge(known, incoming);

                assertThat(result == ApplyResult.STALE_EPOCH)
                        .as("known %d, incoming %d", known, incoming)
                        .isEqualTo(verdict == EpochVerdict.REFUSE_STALE);
                assertThat(store.epoch()).isEqualTo(EpochRules.afterObserving(known, incoming));
            }
        }
    }

    @Test
    @DisplayName("the current primary is the one reporting itself at the highest epoch")
    void currentPrimaryPrefersSelfReportAtHighestEpoch() {
        List<RoleReport> replies = List.of(
                new RoleReport(1, BACKUP, 3, 4),
                new RoleReport(2, PRIMARY, 2, null),
                new RoleReport(4, PRIMARY, 3, 4));
        assertThat(EpochRules.currentPrimary(replies)).contains(4);
    }

    @Test
    @DisplayName("without a self report, the belief at the highest epoch names the primary; ties go to the lowest id")
    void currentPrimaryFromBeliefsAndTieBreak() {
        assertThat(EpochRules.currentPrimary(List.of(
                new RoleReport(1, BACKUP, 3, 4),
                new RoleReport(2, BACKUP, 2, 5)))).contains(4);
        assertThat(EpochRules.currentPrimary(List.of(
                new RoleReport(3, PRIMARY, 3, null),
                new RoleReport(2, PRIMARY, 3, null)))).contains(2);
        assertThat(EpochRules.currentPrimary(List.of(new RoleReport(1, BACKUP, 3, null)))).isEmpty();
        assertThat(EpochRules.currentPrimary(List.of())).isEmpty();
    }

    @Test
    @DisplayName("a recovered old primary that sees a higher epoch demotes and resyncs from the new primary")
    void recoveredPrimaryDemotes() {
        RejoinDecision decision = EpochRules.resolveRejoin(5, PRIMARY, 2, List.of(
                new RoleReport(3, BACKUP, 3, 4),
                new RoleReport(4, PRIMARY, 3, null)));

        assertThat(decision.action()).isEqualTo(RejoinDecision.Action.DEMOTE_AND_RESYNC);
        assertThat(decision.epoch()).isEqualTo(3);
        assertThat(decision.primaryId()).isEqualTo(4);
        assertThat(decision.resync()).isTrue();
        assertThat(decision.viewConfirmed()).isFalse();
    }

    @Test
    @DisplayName("a recovered backup adopts a higher epoch; with no higher epoch both roles stay")
    void backupAdoptsAndStayCases() {
        RejoinDecision adopt = EpochRules.resolveRejoin(2, BACKUP, 2, List.of(new RoleReport(4, PRIMARY, 3, null)));
        assertThat(adopt.action()).isEqualTo(RejoinDecision.Action.ADOPT_AND_RESYNC);
        assertThat(adopt.epoch()).isEqualTo(3);
        assertThat(adopt.primaryId()).isEqualTo(4);

        RejoinDecision primaryStays = EpochRules.resolveRejoin(5, PRIMARY, 3, List.of(new RoleReport(1, BACKUP, 3, 5)));
        assertThat(primaryStays.action()).isEqualTo(RejoinDecision.Action.STAY);
        assertThat(primaryStays.primaryId()).isEqualTo(5);
        assertThat(primaryStays.viewConfirmed()).isTrue();

        RejoinDecision backupStays = EpochRules.resolveRejoin(1, BACKUP, 3, List.of(
                new RoleReport(2, BACKUP, 2, 9),
                new RoleReport(5, PRIMARY, 3, null)));
        assertThat(backupStays.action()).isEqualTo(RejoinDecision.Action.STAY);
        assertThat(backupStays.epoch()).isEqualTo(3);
        assertThat(backupStays.primaryId()).isEqualTo(5);
    }

    @Test
    @DisplayName("no answers means no answer, and a higher epoch naming nobody leaves the primary unknown")
    void noAnswerAndUnknownPrimary() {
        RejoinDecision none = EpochRules.resolveRejoin(5, PRIMARY, 2, List.of());
        assertThat(none.action()).isEqualTo(RejoinDecision.Action.NO_ANSWER);
        assertThat(none.epoch()).isEqualTo(2);
        assertThat(none.primaryId()).isNull();
        assertThat(none.viewConfirmed()).isFalse();
        assertThat(none.resync()).isFalse();

        RejoinDecision unknown = EpochRules.resolveRejoin(5, PRIMARY, 2, List.of(new RoleReport(1, BACKUP, 3, null)));
        assertThat(unknown.action()).isEqualTo(RejoinDecision.Action.DEMOTE_AND_RESYNC);
        assertThat(unknown.primaryId()).isNull();
    }

    @Test
    @DisplayName("a node cannot answer its own role query, and one node cannot answer twice")
    void rejectsSelfAndDuplicateAnswers() {
        assertThatThrownBy(() -> EpochRules.resolveRejoin(5, PRIMARY, 2, List.of(new RoleReport(5, PRIMARY, 2, null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EpochRules.currentPrimary(List.of(
                new RoleReport(1, BACKUP, 2, null), new RoleReport(1, BACKUP, 3, null))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
