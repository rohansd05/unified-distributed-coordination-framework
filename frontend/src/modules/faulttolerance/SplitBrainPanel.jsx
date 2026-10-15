import { ShieldAlert, ShieldCheck } from 'lucide-react'
import { cn } from '@/lib/utils'
import { words } from './faultToleranceModel'

const nodesText = (ids) => (ids.length === 1 ? `node ${ids[0]}` : `nodes ${ids.join(', ')}`)

/**
 * The no-two-primaries check from status.splitBrain, run by the backend on every status read over
 * each node's replication service. Passed and failed differ by text and icon, not only colour.
 *
 * @param {object} props
 * @param {object|null} props.splitBrain
 */
export function SplitBrainPanel({ splitBrain }) {
  if (!splitBrain) return null
  const { passed, livePrimaries = [], highestEpoch, violations = [], violationsSeen } = splitBrain
  const Icon = passed ? ShieldCheck : ShieldAlert

  return (
    <section data-testid="split-brain" data-passed={String(passed)} aria-label="No two primaries check"
      className={cn('rounded-lg border p-4 text-sm', passed ? 'border-success/50 bg-success/5' : 'border-destructive/60 bg-destructive/10')}>
      <p className="flex items-center gap-2 font-semibold">
        <Icon aria-hidden="true" className={cn('size-5', passed ? 'text-success' : 'text-destructive')} />
        {passed ? 'No two primaries: check passed' : 'Two primaries seen: check failed'}
      </p>
      <dl className="mt-2 grid gap-x-6 gap-y-1 sm:grid-cols-3">
        <div>
          <dt className="text-xs text-muted-foreground">Live primaries now</dt>
          <dd>{livePrimaries.length ? words(nodesText(livePrimaries)) : 'None right now'}</dd>
        </div>
        <div>
          <dt className="text-xs text-muted-foreground">Highest epoch</dt>
          <dd className="tabular-nums">{highestEpoch ?? 'Unknown'}</dd>
        </div>
        <div>
          <dt className="text-xs text-muted-foreground">Violations seen since the reset</dt>
          <dd className="tabular-nums">{violationsSeen}</dd>
        </div>
      </dl>
      {violations.length > 0 && (
        <ul data-testid="violations" className="mt-3 space-y-1 border-t border-destructive/40 pt-2">
          {violations.map((v) => (
            <li key={`${v.kind}-${v.nodeIds.join('-')}-${v.epoch}`}>
              <span className="font-medium">{words(v.kind)}</span>: {nodesText(v.nodeIds)} at epoch {v.epoch} (highest {v.highestEpoch}). {v.message}.
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
