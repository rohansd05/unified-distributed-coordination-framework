import { Button } from '@/components/ui/button'
import { violationTypeLabel } from './labels'

/**
 * Causal Invariant Verification Panel (Experiment 3).
 *
 * Rules:
 * - States "0 causal violations" only when verified and reported by backend (passed === true and violationsCount === 0).
 * - Displays violations list with shape and text when violations exist.
 * - Displays retainedWindowNote regarding capacity.
 * - Allows manual onDemand verification trigger.
 */
export function VerificationPanel({
  verification,
  onVerify,
  loading = false,
  error = null,
}) {
  const hasResult = verification != null
  const passed = hasResult && verification.passed && verification.violationsCount === 0

  return (
    <div data-testid="verification-panel" className="relative space-y-4 rounded-xl border bg-card p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h3 className="font-semibold text-foreground">Causal invariant verification</h3>
          <p className="text-xs text-muted-foreground">
            Lamport logical clock rule: for any message a → b, Lamport time of receive must strictly exceed send time.
          </p>
        </div>

        <Button
          type="button"
          size="sm"
          variant={passed ? 'outline' : 'default'}
          disabled={loading}
          onClick={onVerify}
          className="text-xs"
        >
          {loading ? 'Verifying…' : hasResult ? 'Re-verify invariants' : 'Verify causal invariants'}
        </Button>
      </div>

      {error && (
        <p role="alert" className="text-xs text-destructive">
          {error.detail || error.message || 'Verification request failed.'}
        </p>
      )}

      {!hasResult ? (
        <div className="rounded-lg border border-border/60 bg-background/50 p-4 text-xs text-muted-foreground">
          Click &quot;Verify causal invariants&quot; to check happens-before invariants and monotonicity across all retained events.
        </div>
      ) : passed ? (
        <div className="space-y-3 rounded-lg border border-emerald-500/30 bg-emerald-500/10 p-4 text-xs text-foreground">
          <div className="flex items-center gap-2 font-semibold text-emerald-400">
            <span className="flex size-4 items-center justify-center rounded-full bg-emerald-500/20 text-[10px] text-emerald-400">
              ✓
            </span>
            <span>0 causal violations</span>
          </div>
          <p className="leading-relaxed text-muted-foreground">
            {verification.summary || 'All recorded events satisfy Lamport causal happens-before relations.'}
          </p>
          <div className="flex flex-wrap gap-4 font-mono text-[11px] text-muted-foreground">
            <span>Total events checked: <strong className="text-foreground">{verification.totalEventsChecked}</strong></span>
            <span>Receive events checked: <strong className="text-foreground">{verification.receiveEventsChecked}</strong></span>
          </div>
          {verification.retainedWindowNote && (
            <p className="text-[11px] text-muted-foreground/80">{verification.retainedWindowNote}</p>
          )}
        </div>
      ) : (
        <div className="space-y-3 rounded-lg border border-destructive/40 bg-destructive/10 p-4 text-xs text-foreground">
          <div className="flex items-center gap-2 font-semibold text-destructive">
            <span className="inline-block size-0 border-x-4 border-b-[8px] border-x-transparent border-b-destructive" />
            <span>{verification.violationsCount} causal violations detected</span>
          </div>
          <p className="text-muted-foreground">{verification.summary}</p>

          {/* Violations List */}
          <div className="max-h-48 overflow-y-auto space-y-1.5 pt-1">
            {verification.violations?.map((v, i) => (
              <div
                key={i}
                data-testid="violation-item"
                className="rounded border border-destructive/30 bg-background/80 p-2 text-[11px]"
              >
                <div className="flex flex-wrap items-center justify-between gap-2 font-medium text-destructive">
                  <span>{violationTypeLabel(v.type)} (Node {v.nodeId})</span>
                  <span className="font-mono text-muted-foreground">
                    actual L={v.actualLamportTime} vs expected &gt; {v.expectedRelationTime}
                  </span>
                </div>
                <p className="mt-0.5 text-muted-foreground">{v.message}</p>
              </div>
            ))}
          </div>

          {verification.retainedWindowNote && (
            <p className="text-[11px] text-muted-foreground/80">{verification.retainedWindowNote}</p>
          )}
        </div>
      )}
    </div>
  )
}
