package com.udcf.modules.replication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validation of the E5b records: replies, push outcomes, write results and anti-entropy reports. */
class ReplicationRecordsTest {

    private static final DataItem ITEM = new DataItem("k", "v", 1, 1, 1);

    @Test
    @DisplayName("reply records reject node 0, a negative Lamport time and epoch 0")
    void replyHeadersValidated() {
        assertThatThrownBy(() -> new AckReply(0, 1, 1, ApplyResult.APPLIED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AckReply(1, -1, 1, ApplyResult.APPLIED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SyncReply(1, 1, 0, new AntiEntropyResult(0, 0, 0, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReadReply(1, 1, 1, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReplicaDump(0, 1, new TreeMap<>())).isInstanceOf(IllegalArgumentException.class);
        assertThat(new AckReply(1, 0, 1, ApplyResult.DUPLICATE).result()).isEqualTo(ApplyResult.DUPLICATE);
    }

    @Test
    @DisplayName("a dump page holds keys in strictly ascending order; a replica dump is keyed by each item's own key and unmodifiable")
    void pagesAndDumpsValidated() {
        DataItem a = new DataItem("a", "", 1, 1, 1);
        DataItem b = new DataItem("b", "", 1, 1, 1);
        assertThatThrownBy(() -> new DumpPage(1, 1, 1, List.of(b, a), false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DumpPage(1, 1, 1, List.of(a, a), false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new DumpPage(1, 1, 1, List.of(a, b), true).items()).containsExactly(a, b);

        TreeMap<String, DataItem> wrong = new TreeMap<>();
        wrong.put("x", a);
        assertThatThrownBy(() -> new ReplicaDump(1, 1, wrong)).isInstanceOf(IllegalArgumentException.class);
        TreeMap<String, DataItem> right = new TreeMap<>();
        right.put("a", a);
        ReplicaDump dump = new ReplicaDump(1, 1, right);
        right.put("b", b);
        assertThat(dump.items()).containsOnlyKeys("a");
        assertThatThrownBy(() -> dump.items().put("b", b)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("an ACKED push has a result, epoch and latency and no detail; any other push only a detail")
    void pushOutcomeShapes() {
        PushOutcome acked = PushOutcome.acked(2, ApplyResult.APPLIED, 1, 0.4);
        PushOutcome failed = PushOutcome.withoutReply(3, PushStatus.FAILED, "ConnectException: refused");

        assertThat(acked.acknowledged()).isTrue();
        assertThat(acked.latencyMillis()).hasValue(0.4);
        assertThat(failed.acknowledged()).isFalse();
        assertThat(failed.latencyMillis()).isEmpty();
        assertThat(failed.result()).isEmpty();

        assertThatThrownBy(() -> PushOutcome.withoutReply(2, PushStatus.ACKED, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PushOutcome(2, PushStatus.FAILED, Optional.of(ApplyResult.APPLIED), OptionalLong.empty(),
                OptionalDouble.empty(), Optional.of("x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushOutcome.acked(2, ApplyResult.APPLIED, 1, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushOutcome.acked(2, ApplyResult.APPLIED, 1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushOutcome.acked(0, ApplyResult.APPLIED, 1, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a write result rejects a simulated delay on a synchronous write and invalid timings; simulated() follows the delay")
    void writeResultValidated() {
        CompletableFuture<List<PushOutcome>> done = CompletableFuture.completedFuture(List.of());

        assertThatThrownBy(() -> new WriteResult(ITEM, ConsistencyModel.SYNCHRONOUS, ApplyResult.APPLIED, 1, 450, List.of(), done))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WriteResult(ITEM, ConsistencyModel.ASYNCHRONOUS, ApplyResult.APPLIED, Double.NaN, 450, List.of(), done))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WriteResult(ITEM, ConsistencyModel.ASYNCHRONOUS, ApplyResult.APPLIED, 1, -1, List.of(), done))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new WriteResult(ITEM, ConsistencyModel.ASYNCHRONOUS, ApplyResult.APPLIED, 1, 450, List.of(2), done).simulated()).isTrue();
        assertThat(new WriteResult(ITEM, ConsistencyModel.SYNCHRONOUS, ApplyResult.APPLIED, 1, 0, List.of(2), done).simulated()).isFalse();
    }

    @Test
    @DisplayName("an anti-entropy report has a failure exactly when a chunk was not acknowledged")
    void antiEntropyReportValidated() {
        AntiEntropyResult none = new AntiEntropyResult(0, 0, 0, 0, 0);

        assertThat(new AntiEntropyReport(3, none, 1, 1, 0.5, Optional.empty()).completed()).isTrue();
        assertThat(new AntiEntropyReport(3, none, 2, 1, 0.5, Optional.of("refused")).completed()).isFalse();
        assertThatThrownBy(() -> new AntiEntropyReport(3, none, 2, 1, 0.5, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AntiEntropyReport(3, none, 1, 1, 0.5, Optional.of("x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AntiEntropyReport(3, none, 0, 0, 0.5, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AntiEntropyReport(3, none, 1, 2, 0.5, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AntiEntropyReport(0, none, 1, 1, 0.5, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a catch-up report has a merge result and source epoch, or a failure, never both")
    void catchUpReportValidated() {
        AntiEntropyResult merged = new AntiEntropyResult(2, 1, 1, 0, 0);

        assertThat(CatchUpReport.merged(2, merged, 3, 0.7).completed()).isTrue();
        assertThat(CatchUpReport.failed(2, 0.7, "refused").completed()).isFalse();
        assertThatThrownBy(() -> new CatchUpReport(2, Optional.of(merged), OptionalLong.of(1), 0.7, Optional.of("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatchUpReport(2, Optional.of(merged), OptionalLong.empty(), 0.7, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CatchUpReport.failed(0, 0.7, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CatchUpReport.failed(2, Double.NaN, "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a takeover report copies its lists and sums what the catch-ups applied (failed pulls count 0)")
    void takeoverReportSumsCatchUps() {
        List<CatchUpReport> catchUps = new java.util.ArrayList<>(List.of(
                CatchUpReport.merged(2, new AntiEntropyResult(3, 2, 1, 0, 0), 1, 0.5),
                CatchUpReport.failed(3, 0.5, "refused"),
                CatchUpReport.merged(4, new AntiEntropyResult(1, 1, 0, 0, 0), 1, 0.5)));

        TakeoverReport report = new TakeoverReport(Optional.of(2), 1, catchUps, List.of(), 9);
        catchUps.clear();

        assertThat(report.appliedFromCatchUp()).isEqualTo(3);
        assertThat(report.catchUps()).hasSize(3);
        assertThatThrownBy(() -> new TakeoverReport(Optional.empty(), 0, List.of(), List.of(), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ReplicationProperties rejects a negative delay, a timeout of 0 and a batch size outside 1 to 1000")
    void propertiesConstructorValidated() {
        assertThatThrownBy(() -> new ReplicationProperties(-1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReplicationProperties(0, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReplicationProperties(0, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReplicationProperties(0, 1, 1001)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ReplicationProperties(0, 1, 1000).batchSize()).isEqualTo(1000);
    }
}
