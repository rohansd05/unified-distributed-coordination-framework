package com.udcf.modules.faulttolerance;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The failover state machine of Experiment 8: {@link FailoverPhase#STEADY} to
 * {@link FailoverPhase#SUSPECTED} to {@link FailoverPhase#PROMOTING} to
 * {@link FailoverPhase#RESTORED}, recording every instant it passes through in a
 * {@link FailoverRun}.
 *
 * <p>Pure: it never reads a clock. Every event carries the instant it was observed, stamped by
 * the caller from the module's <b>one shared</b> monotonic nano clock (one {@code LongSupplier}
 * instance for every stamper), so instants from different threads are comparable.</p>
 *
 * <h2>Events and the runs they open or advance</h2>
 * <ul>
 *   <li>{@link #onPrimaryCrashed}: opens a run for the current primary. The phase stays STEADY:
 *       the cluster has not noticed yet.</li>
 *   <li>{@link #onSuspected}: the first suspicion of the current primary (later ones are
 *       ignored) records the detection and moves to SUSPECTED; without a recorded crash it opens
 *       a run with no crash instant, so the detection interval stays null.</li>
 *   <li>{@link #onPrimaryAlive} or {@link #onOldPrimaryRecovered} before anyone was chosen: the
 *       run ends {@link FailoverRun.Outcome#PRIMARY_RETURNED} and the phase goes back to STEADY.</li>
 *   <li>{@link #onPromotionChosen}: inside a run, moves to PROMOTING; outside one it is a planned
 *       handover (or the first appointment) and opens no run.</li>
 *   <li>{@link #onPromotionFailed}: the chosen node could not become primary; back to SUSPECTED
 *       (or STEADY if nothing was detected yet).</li>
 *   <li>{@link #onPromoted}: the chosen node acts as primary; it becomes the current primary.</li>
 *   <li>{@link #onWriteAccepted}: the new primary's first accepted write at its new epoch or
 *       higher, after {@code onPromoted}, restores the service (RESTORED). A write accepted before
 *       {@code onPromoted} was recorded does not count.</li>
 *   <li>{@link #onOldPrimaryRecovered}, {@link #onDemoted}, {@link #onResynchronised}: the old
 *       primary's recovery, recorded on the latest run.</li>
 *   <li>A failure of the current primary before the latest run restored the service ends that
 *       run {@link FailoverRun.Outcome#INTERRUPTED} and opens a new one.</li>
 * </ul>
 *
 * <p>Every event returns an {@link EventResult}. An event that does not apply changes nothing
 * ({@link EventResult#IGNORED}). An event whose instant is earlier than the instant it must
 * follow (for example a detection before the crash) is {@link EventResult#REJECTED_OUT_OF_ORDER}:
 * nothing is recorded, so no interval is ever negative, and it is counted in
 * {@link #rejectedEventCount()} so the caller can log it; it is never silently lost.</p>
 *
 * <p>Thread safety: every method is synchronized; none waits or calls out while holding the lock.</p>
 */
public class FailoverStateMachine {

    /** What an event did. */
    public enum EventResult {

        /** The event was recorded. */
        APPLIED,

        /** The event does not apply in the current state; nothing changed. */
        IGNORED,

        /** The event's instant is earlier than the one it must follow; nothing changed, and it was counted. */
        REJECTED_OUT_OF_ORDER
    }

    private final int historyLimit;
    private final ArrayDeque<Draft> runs = new ArrayDeque<>();

    private FailoverPhase phase = FailoverPhase.STEADY;
    private Integer primaryId;
    private long primaryEpoch;
    private Promotion pendingHandover;
    private long nextRunId = 1;
    private long rejectedEvents;

    /** @param historyLimit how many runs to keep, oldest dropped first; at least 1 */
    public FailoverStateMachine(int historyLimit) {
        if (historyLimit < 1) {
            throw new IllegalArgumentException("historyLimit must be >= 1, was " + historyLimit);
        }
        this.historyLimit = historyLimit;
    }

    // ------------------------------------------------------------------ failure

    /** The current primary crashed; {@code source} says how {@code atNanos} was stamped. */
    public synchronized EventResult onPrimaryCrashed(int nodeId, InstantSource source, long atNanos) {
        Objects.requireNonNull(source, "source must not be null");
        if (!isPrimary(nodeId)) {
            return EventResult.IGNORED;
        }
        Draft run = openRunFor(nodeId);
        if (run != null) {
            if (run.crashAtNanos != null) {
                return EventResult.IGNORED;
            }
            if (run.detectedAtNanos != null && atNanos > run.detectedAtNanos) {
                return reject();
            }
            run.crashAtNanos = atNanos;
            run.crashSource = source;
            return EventResult.APPLIED;
        }
        Draft opened = open(nodeId);
        opened.crashAtNanos = atNanos;
        opened.crashSource = source;
        phase = FailoverPhase.STEADY;
        return EventResult.APPLIED;
    }

    /** {@code observerId}'s failure detector suspects {@code suspectedId}. Only the first suspicion of the primary counts. */
    public synchronized EventResult onSuspected(int observerId, int suspectedId, long atNanos) {
        EpochRules.requireNodeId("observerId", observerId);
        if (observerId == suspectedId) {
            throw new IllegalArgumentException("node " + observerId + " cannot suspect itself");
        }
        if (!isPrimary(suspectedId)) {
            return EventResult.IGNORED;
        }
        Draft run = openRunFor(suspectedId);
        if (run == null) {
            run = open(suspectedId);
        } else if (run.detectedAtNanos != null) {
            return EventResult.IGNORED;
        } else if (run.crashAtNanos != null && atNanos < run.crashAtNanos) {
            return reject();
        }
        run.detectedAtNanos = atNanos;
        run.detectedByNodeId = observerId;
        if (run.newPrimaryId == null) {
            phase = FailoverPhase.SUSPECTED;
        }
        return EventResult.APPLIED;
    }

    /** The suspected primary answers heartbeats again. Ends the run only if nobody was chosen yet. */
    public synchronized EventResult onPrimaryAlive(int nodeId, long atNanos) {
        Draft run = openRunFor(nodeId);
        if (run == null || run.newPrimaryId != null || run.detectedAtNanos == null) {
            return EventResult.IGNORED;
        }
        if (atNanos < run.detectedAtNanos) {
            return reject();
        }
        run.finish(FailoverRun.Outcome.PRIMARY_RETURNED);
        phase = FailoverPhase.STEADY;
        return EventResult.APPLIED;
    }

    // ------------------------------------------------------------------ promotion

    /**
     * A new primary was chosen ({@link EpochAuthority#onLeaderElected}). Inside a run whose
     * primary is down it moves to PROMOTING; with no run in progress it is a planned handover
     * (or the first appointment), which opens no run.
     */
    public synchronized EventResult onPromotionChosen(Promotion promotion) {
        Objects.requireNonNull(promotion, "promotion must not be null");
        Draft run = latestInProgress();
        if (run != null) {
            if (run.newPrimaryId != null || promotion.nodeId() == run.oldPrimaryId) {
                return EventResult.IGNORED;
            }
            Long follows = run.detectedAtNanos != null ? run.detectedAtNanos : run.crashAtNanos;
            if (follows != null && promotion.observedAtNanos() < follows) {
                return reject();
            }
            run.electedAtNanos = promotion.observedAtNanos();
            run.newPrimaryId = promotion.nodeId();
            run.newEpoch = promotion.epoch();
            phase = FailoverPhase.PROMOTING;
            return EventResult.APPLIED;
        }
        if (isPrimary(promotion.nodeId())) {
            return EventResult.IGNORED;
        }
        pendingHandover = promotion;
        return EventResult.APPLIED;
    }

    /** The chosen node could not become primary (for example it went down). */
    public synchronized EventResult onPromotionFailed(int nodeId, long atNanos) {
        Draft run = latestInProgress();
        if (run != null && phase == FailoverPhase.PROMOTING && Objects.equals(run.newPrimaryId, nodeId)
                && run.promotedAtNanos == null) {
            if (atNanos < run.electedAtNanos) {
                return reject();
            }
            run.electedAtNanos = null;
            run.newPrimaryId = null;
            run.newEpoch = null;
            phase = run.detectedAtNanos != null ? FailoverPhase.SUSPECTED : FailoverPhase.STEADY;
            return EventResult.APPLIED;
        }
        if (pendingHandover != null && pendingHandover.nodeId() == nodeId) {
            if (atNanos < pendingHandover.observedAtNanos()) {
                return reject();
            }
            pendingHandover = null;
            return EventResult.APPLIED;
        }
        return EventResult.IGNORED;
    }

    /** The chosen node now acts as primary at {@code epoch} (its replication service accepted the role). */
    public synchronized EventResult onPromoted(int nodeId, long epoch, long atNanos) {
        Draft run = latestInProgress();
        if (run != null && phase == FailoverPhase.PROMOTING && Objects.equals(run.newPrimaryId, nodeId)
                && run.newEpoch == epoch && run.promotedAtNanos == null) {
            if (atNanos < run.electedAtNanos) {
                return reject();
            }
            run.promotedAtNanos = atNanos;
            primaryId = nodeId;
            primaryEpoch = epoch;
            return EventResult.APPLIED;
        }
        if (pendingHandover != null && pendingHandover.nodeId() == nodeId && pendingHandover.epoch() == epoch) {
            if (atNanos < pendingHandover.observedAtNanos()) {
                return reject();
            }
            pendingHandover = null;
            primaryId = nodeId;
            primaryEpoch = epoch;
            return EventResult.APPLIED;
        }
        return EventResult.IGNORED;
    }

    /** {@code nodeId} accepted a client write at {@code epoch}. Only the new primary's first one, after promotion, counts. */
    public synchronized EventResult onWriteAccepted(int nodeId, long epoch, long atNanos) {
        Draft run = latestInProgress();
        if (run == null || phase != FailoverPhase.PROMOTING || run.promotedAtNanos == null
                || !Objects.equals(run.newPrimaryId, nodeId) || epoch < run.newEpoch) {
            return EventResult.IGNORED;
        }
        if (atNanos < run.promotedAtNanos) {
            return reject();
        }
        run.restoredAtNanos = atNanos;
        run.finish(FailoverRun.Outcome.RESTORED);
        phase = FailoverPhase.RESTORED;
        return EventResult.APPLIED;
    }

    // ------------------------------------------------------------------ recovery of the old primary

    /**
     * The latest run's old primary came back. Before anyone was chosen, it simply resumes as
     * primary and the run ends {@link FailoverRun.Outcome#PRIMARY_RETURNED}; afterwards this
     * starts the recovery interval.
     */
    public synchronized EventResult onOldPrimaryRecovered(int nodeId, long atNanos) {
        Draft run = runs.peekLast();
        if (run == null || run.oldPrimaryId != nodeId || run.oldPrimaryRecoveredAtNanos != null
                || run.outcome == FailoverRun.Outcome.PRIMARY_RETURNED) {
            return EventResult.IGNORED;
        }
        Long follows = run.crashAtNanos != null ? run.crashAtNanos : run.detectedAtNanos;
        if (follows != null && atNanos < follows) {
            return reject();
        }
        run.oldPrimaryRecoveredAtNanos = atNanos;
        if (run.inProgress && run.newPrimaryId == null) {
            run.finish(FailoverRun.Outcome.PRIMARY_RETURNED);
            phase = FailoverPhase.STEADY;
        }
        return EventResult.APPLIED;
    }

    /** The recovered old primary demoted itself on seeing {@code epoch}, which must be above its old one. */
    public synchronized EventResult onDemoted(int nodeId, long epoch, long atNanos) {
        Draft run = recoveringRun(nodeId);
        if (run == null || run.demotedAtNanos != null || epoch <= run.oldEpoch) {
            return EventResult.IGNORED;
        }
        if (atNanos < run.oldPrimaryRecoveredAtNanos) {
            return reject();
        }
        run.demotedAtNanos = atNanos;
        return EventResult.APPLIED;
    }

    /** The recovered old primary finished copying the current primary's state. */
    public synchronized EventResult onResynchronised(int nodeId, long atNanos) {
        Draft run = recoveringRun(nodeId);
        if (run == null || run.resyncedAtNanos != null) {
            return EventResult.IGNORED;
        }
        if (atNanos < run.oldPrimaryRecoveredAtNanos) {
            return reject();
        }
        run.resyncedAtNanos = atNanos;
        return EventResult.APPLIED;
    }

    // ------------------------------------------------------------------ queries

    public synchronized FailoverPhase phase() {
        return phase;
    }

    /** The current primary as this machine knows it, or empty before the first appointment. */
    public synchronized Optional<Integer> primaryId() {
        return Optional.ofNullable(primaryId);
    }

    /** The current primary's epoch, or empty before the first appointment. */
    public synchronized OptionalLong primaryEpoch() {
        return primaryId == null ? OptionalLong.empty() : OptionalLong.of(primaryEpoch);
    }

    public synchronized Optional<FailoverRun> latestRun() {
        Draft run = runs.peekLast();
        return run == null ? Optional.empty() : Optional.of(run.snapshot());
    }

    /** The kept runs, oldest first. */
    public synchronized List<FailoverRun> runs() {
        return runs.stream().map(Draft::snapshot).toList();
    }

    /** Events rejected as out of order since the last reset. */
    public synchronized long rejectedEventCount() {
        return rejectedEvents;
    }

    /** Back to a fresh machine: no primary, no runs, counter 0. */
    public synchronized void reset() {
        runs.clear();
        phase = FailoverPhase.STEADY;
        primaryId = null;
        primaryEpoch = 0;
        pendingHandover = null;
        nextRunId = 1;
        rejectedEvents = 0;
    }

    // ------------------------------------------------------------------ internals

    private boolean isPrimary(int nodeId) {
        return primaryId != null && primaryId == nodeId;
    }

    private Draft latestInProgress() {
        Draft run = runs.peekLast();
        return run != null && run.inProgress ? run : null;
    }

    /** The run in progress for this old primary, or null. */
    private Draft openRunFor(int oldPrimaryId) {
        Draft run = latestInProgress();
        return run != null && run.oldPrimaryId == oldPrimaryId ? run : null;
    }

    private Draft recoveringRun(int nodeId) {
        Draft run = runs.peekLast();
        return run != null && run.oldPrimaryId == nodeId && run.oldPrimaryRecoveredAtNanos != null
                && run.newPrimaryId != null ? run : null;
    }

    /** Opens a run for the current primary, interrupting an unfinished one. */
    private Draft open(int oldPrimaryId) {
        Draft unfinished = latestInProgress();
        if (unfinished != null) {
            unfinished.finish(FailoverRun.Outcome.INTERRUPTED);
        }
        pendingHandover = null;
        Draft run = new Draft(nextRunId++, oldPrimaryId, primaryEpoch);
        runs.addLast(run);
        while (runs.size() > historyLimit) {
            runs.removeFirst();
        }
        return run;
    }

    private EventResult reject() {
        rejectedEvents++;
        return EventResult.REJECTED_OUT_OF_ORDER;
    }

    /** A run under construction; only ever touched under the machine's lock. */
    private static final class Draft {
        private final long runId;
        private final int oldPrimaryId;
        private final long oldEpoch;
        private Long crashAtNanos;
        private InstantSource crashSource;
        private Long detectedAtNanos;
        private Integer detectedByNodeId;
        private Long electedAtNanos;
        private Integer newPrimaryId;
        private Long newEpoch;
        private Long promotedAtNanos;
        private Long restoredAtNanos;
        private Long oldPrimaryRecoveredAtNanos;
        private Long demotedAtNanos;
        private Long resyncedAtNanos;
        private FailoverRun.Outcome outcome = FailoverRun.Outcome.IN_PROGRESS;
        private boolean inProgress = true;

        private Draft(long runId, int oldPrimaryId, long oldEpoch) {
            this.runId = runId;
            this.oldPrimaryId = oldPrimaryId;
            this.oldEpoch = oldEpoch;
        }

        private void finish(FailoverRun.Outcome finalOutcome) {
            outcome = finalOutcome;
            inProgress = false;
        }

        private FailoverRun snapshot() {
            return new FailoverRun(runId, oldPrimaryId, oldEpoch, crashAtNanos, crashSource, detectedAtNanos,
                    detectedByNodeId, electedAtNanos, newPrimaryId, newEpoch, promotedAtNanos, restoredAtNanos,
                    oldPrimaryRecoveredAtNanos, demotedAtNanos, resyncedAtNanos, outcome);
        }
    }
}
