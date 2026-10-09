package com.udcf.modules.faulttolerance;

import com.udcf.modules.replication.ConsistencyModel;
import com.udcf.modules.replication.DataItem;
import com.udcf.modules.replication.DataStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static com.udcf.modules.faulttolerance.AcknowledgedLedgerTest.write;
import static com.udcf.modules.faulttolerance.FailoverRole.BACKUP;
import static com.udcf.modules.faulttolerance.FailoverRole.PRIMARY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validation and factories of the Experiment 8 value records. */
class FaultToleranceRecordsTest {

    @Test
    @DisplayName("numbered updates use the legacy key format with ASCII digits whatever the default locale")
    void numberedUpdateUsesLocaleRoot() {
        Locale before = Locale.getDefault();
        Locale thaiDigits = Locale.forLanguageTag("th-TH-u-nu-thai");
        try {
            Locale.setDefault(thaiDigits);
            assertThat(String.format("%04d", 1)).as("precondition: this locale formats non-ASCII digits")
                    .isNotEqualTo("0001");

            SystemUpdate update = SystemUpdate.numbered(1);
            assertThat(update.sequence()).isEqualTo(1);
            assertThat(update.key()).isEqualTo("setting-0001");
            assertThat(update.value()).isEqualTo("v1");
            assertThat(SystemUpdate.numbered(12345).key()).isEqualTo("setting-12345");
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    @DisplayName("a system update follows the replication protocol limits")
    void systemUpdateValidation() {
        assertThatThrownBy(() -> new SystemUpdate(0, "k", "v")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SystemUpdate(1, " ", "v")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SystemUpdate(1, "k", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SystemUpdate(1, "k".repeat(DataItem.MAX_KEY_LENGTH + 1), "v"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("role reports: a primary believes in itself; epochs start at the initial epoch")
    void roleReportValidation() {
        assertThat(new RoleReport(4, PRIMARY, 3, null).pointsAt()).isEqualTo(4);
        assertThat(new RoleReport(1, BACKUP, 3, 4).pointsAt()).isEqualTo(4);
        assertThat(new RoleReport(1, BACKUP, 3, null).pointsAt()).isNull();
        assertThatThrownBy(() -> new RoleReport(4, PRIMARY, 3, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RoleReport(4, BACKUP, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RoleReport(0, BACKUP, 1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RoleReport(1, null, 1, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rejoin decisions and promotions validate their fields")
    void decisionAndPromotionValidation() {
        assertThatThrownBy(() -> new RejoinDecision(RejoinDecision.Action.NO_ANSWER, 2, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RejoinDecision(RejoinDecision.Action.STAY, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Promotion(4, DataStore.INITIAL_EPOCH, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Promotion(4, 3, 4, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Promotion(0, 3, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Promotion(4, 3, 5, 7).previousPrimaryId()).isEqualTo(5);
    }

    @Test
    @DisplayName("a failover run needs a crash source exactly with a crash instant, and an epoch exactly with a new primary")
    void failoverRunValidation() {
        assertThatThrownBy(() -> new FailoverRun(1, 5, 2, 10L, null, null, null, null, null, null, null, null, null,
                null, null, FailoverRun.Outcome.IN_PROGRESS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FailoverRun(1, 5, 2, null, InstantSource.ACTION, null, null, null, null, null,
                null, null, null, null, null, FailoverRun.Outcome.IN_PROGRESS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FailoverRun(1, 5, 2, null, null, null, null, 1L, 4, null, null, null, null, null,
                null, FailoverRun.Outcome.IN_PROGRESS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FailoverRun(0, 5, 2, null, null, null, null, null, null, null, null, null, null,
                null, null, FailoverRun.Outcome.IN_PROGRESS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FailoverRun(1, 5, 2, null, null, null, null, null, null, null, null, null, null,
                null, null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("an acknowledgement copies the write result and never delays a synchronous write")
    void acknowledgedUpdateValidation() {
        AcknowledgedUpdate entry = AcknowledgedUpdate.from(7, write("k", 3, 2, ConsistencyModel.ASYNCHRONOUS, 450), 99L);
        assertThat(entry.key()).isEqualTo("k");
        assertThat(entry.model()).isEqualTo(ConsistencyModel.ASYNCHRONOUS);
        assertThat(entry.simulatedDelayMillis()).isEqualTo(450);
        assertThat(entry.acknowledgedAtNanos()).isEqualTo(99L);

        DataItem item = new DataItem("k", "v", 1, 1, 1);
        assertThatThrownBy(() -> new AcknowledgedUpdate(1, item, ConsistencyModel.SYNCHRONOUS, 5, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AcknowledgedUpdate(0, item, ConsistencyModel.SYNCHRONOUS, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AcknowledgedUpdate(1, item, ConsistencyModel.ASYNCHRONOUS, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a data loss report keeps its counts consistent and its simulated marker tied to the delay")
    void dataLossReportValidation() {
        assertThatThrownBy(() -> new DataLossReport(3, 2, List.of("a"), 2, 0, false, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DataLossReport(3, 2, List.of("a", "b"), 1, 0, false, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DataLossReport(1, 2, List.of("a", "b"), 2, 0, false, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DataLossReport(3, null, List.of(), null, null, false, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DataLossReport(3, 0, List.of(), 0, 0, false, 0,
                DataLossReport.NotAssessed.NO_PRIMARY_STORE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DataLossReport(3, 0, List.of(), 0, 0, true, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DataLossReport(3, 0, List.of(), 0, 0, false, 450, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new DataLossReport(3, 1, List.of("a"), 0, 1, true, 450, null).assessed()).isTrue();
    }

    @Test
    @DisplayName("attempt outcomes and retry decisions validate their shape")
    void retryRecordsValidation() {
        assertThatThrownBy(() -> new AttemptOutcome(AttemptOutcome.Kind.ACCEPTED, 1, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AttemptOutcome.unreachable(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(AttemptOutcome.notPrimary(1, 2).primaryHint()).isEqualTo(2);

        assertThatThrownBy(() -> new RetryDecision(RetryDecision.Action.SEND, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryDecision(RetryDecision.Action.DISCOVER, 3, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryDecision(RetryDecision.Action.DONE, null, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryDecision.send(2, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(RetryDecision.giveUp().isFinal()).isTrue();
        assertThat(RetryDecision.discover(10).isFinal()).isFalse();
    }

    @Test
    @DisplayName("split-brain snapshots, violations and reports validate their shape")
    void splitBrainRecordsValidation() {
        assertThatThrownBy(() -> new NodeRoleSnapshot(1, true, PRIMARY, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NodeRoleSnapshot(1, true, null, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SplitBrainViolation(SplitBrainViolation.Kind.DUPLICATE_PRIMARY, List.of(1), 2, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SplitBrainViolation(SplitBrainViolation.Kind.STALE_PRIMARY, List.of(1), 2, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SplitBrainViolation(SplitBrainViolation.Kind.STALE_PRIMARY, List.of(1), 3, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SplitBrainReport(true, 2L, List.of(1),
                List.of(new SplitBrainViolation(SplitBrainViolation.Kind.STALE_PRIMARY, List.of(1), 1, 2))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SplitBrainReport(true, null, List.of(1), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
