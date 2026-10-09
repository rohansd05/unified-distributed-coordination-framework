package com.udcf.modules.faulttolerance;

import com.udcf.modules.replication.ConsistencyModel;
import com.udcf.modules.replication.DataItem;
import com.udcf.modules.replication.DataStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.udcf.modules.faulttolerance.AcknowledgedLedgerTest.write;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Data loss against the acknowledged keys, checked on a real shared replication store. */
class DataLossTest {

    private final AcknowledgedLedger ledger = new AcknowledgedLedger();
    private final DataStore newPrimary = new DataStore();

    @Test
    @DisplayName("synchronous mode: every confirmed update is on the new primary, so nothing is lost")
    void synchronousLosesNothing() {
        for (int seq = 1; seq <= 5; seq++) {
            var result = write(SystemUpdate.numbered(seq).key(), seq, 2, ConsistencyModel.SYNCHRONOUS, 0);
            ledger.record(seq, result, seq);
            newPrimary.apply(result.item());   // a synchronous write reached the backup before the confirmation
        }

        DataLossReport report = DataLoss.measure(ledger.snapshot(), newPrimary.snapshot());
        assertThat(report.assessed()).isTrue();
        assertThat(report.acknowledged()).isEqualTo(5);
        assertThat(report.lost()).isZero();
        assertThat(report.lostKeys()).isEmpty();
        assertThat(report.lostSynchronous()).isZero();
        assertThat(report.lostAsynchronous()).isZero();
        assertThat(report.simulated()).isFalse();
        assertThat(report.simulatedDelayMillis()).isZero();
    }

    @Test
    @DisplayName("asynchronous mode: updates still in flight at the crash are lost, and the loss is marked simulated")
    void asynchronousLosesInFlight() {
        for (int seq = 1; seq <= 5; seq++) {
            var result = write(SystemUpdate.numbered(seq).key(), seq, 2, ConsistencyModel.ASYNCHRONOUS, 450);
            ledger.record(seq, result, seq);
            if (seq <= 3) {
                newPrimary.apply(result.item());   // 4 and 5 were still waiting for their delayed push
            }
        }

        DataLossReport report = DataLoss.measure(ledger.snapshot(), newPrimary.snapshot());
        assertThat(report.lost()).isEqualTo(2);
        assertThat(report.lostKeys()).containsExactly("setting-0004", "setting-0005");
        assertThat(report.lostAsynchronous()).isEqualTo(2);
        assertThat(report.lostSynchronous()).isZero();
        assertThat(report.simulated()).isTrue();
        assertThat(report.simulatedDelayMillis()).isEqualTo(450);
    }

    @Test
    @DisplayName("an older version on the new primary is a loss; a newer one is not")
    void versionsAreCompared() {
        ledger.record(1, write("older", 10, 2, ConsistencyModel.SYNCHRONOUS, 0), 1L);
        ledger.record(2, write("newer", 10, 2, ConsistencyModel.SYNCHRONOUS, 0), 2L);
        newPrimary.apply(new DataItem("older", "v", 5, 4, 2));
        newPrimary.apply(new DataItem("newer", "w", 3, 3, 3), 3);

        DataLossReport report = DataLoss.measure(ledger.snapshot(), newPrimary.snapshot());
        assertThat(report.lostKeys()).containsExactly("older");
    }

    @Test
    @DisplayName("mixed modes are broken down per mode, and the largest reported delay is carried")
    void mixedModes() {
        ledger.record(1, write("s1", 1, 2, ConsistencyModel.SYNCHRONOUS, 0), 1L);
        ledger.record(2, write("a1", 2, 2, ConsistencyModel.ASYNCHRONOUS, 220), 2L);
        ledger.record(3, write("a2", 3, 2, ConsistencyModel.ASYNCHRONOUS, 450), 3L);

        DataLossReport report = DataLoss.measure(ledger.snapshot(), newPrimary.snapshot());
        assertThat(report.lost()).isEqualTo(3);
        assertThat(report.lostSynchronous()).isEqualTo(1);
        assertThat(report.lostAsynchronous()).isEqualTo(2);
        assertThat(report.simulatedDelayMillis()).isEqualTo(450);
    }

    @Test
    @DisplayName("nothing acknowledged: the loss is null with a reason, never 0")
    void nothingAcknowledged() {
        DataLossReport report = DataLoss.measure(List.of(), newPrimary.snapshot());

        assertThat(report.assessed()).isFalse();
        assertThat(report.notAssessed()).isEqualTo(DataLossReport.NotAssessed.NOTHING_ACKNOWLEDGED);
        assertThat(report.lost()).isNull();
        assertThat(report.lostSynchronous()).isNull();
        assertThat(report.lostAsynchronous()).isNull();
        assertThat(report.simulated()).isFalse();
    }

    @Test
    @DisplayName("no primary store: the loss is null with a reason; the simulated marker still follows the acknowledged writes")
    void noPrimaryStore() {
        ledger.record(1, write("a", 1, 2, ConsistencyModel.ASYNCHRONOUS, 450), 1L);

        DataLossReport report = DataLoss.measure(ledger.snapshot(), null);
        assertThat(report.notAssessed()).isEqualTo(DataLossReport.NotAssessed.NO_PRIMARY_STORE);
        assertThat(report.lost()).isNull();
        assertThat(report.acknowledged()).isEqualTo(1);
        assertThat(report.simulated()).isTrue();
    }

    @Test
    @DisplayName("a key acknowledged twice in the input is rejected")
    void duplicateKeysRejected() {
        AcknowledgedUpdate a = AcknowledgedUpdate.from(1, write("k", 1, 2, ConsistencyModel.SYNCHRONOUS, 0), 1L);
        AcknowledgedUpdate b = AcknowledgedUpdate.from(2, write("k", 2, 2, ConsistencyModel.SYNCHRONOUS, 0), 2L);
        assertThatThrownBy(() -> DataLoss.measure(List.of(a, b), newPrimary.snapshot()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
