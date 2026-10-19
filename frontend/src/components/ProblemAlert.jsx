import { AlertTriangle, Info } from 'lucide-react'
import { cn } from '@/lib/utils'

/** 409s and timeouts are situations, not mistakes: they are shown calmly (warning style). */
const CALM_KINDS = new Set(['busy', 'no-leader', 'node-down', 'state-conflict', 'conflict', 'timeout'])

/**
 * A module action's problem, as the page's describeProblem() tells it (14.1: one shared copy for
 * Experiments 1, 8, 9 and 10). "No leader" is a status, not an alert. Busy names the action in
 * progress; field errors are listed by `fieldLabels[field]` or the field name; `children` adds
 * module-specific lines. An icon and the wording, not only colour, tell calm from error.
 *
 * @param {object} props
 * @param {object|null} props.problem { kind, message, actionInProgress?, fieldErrors?, reason? }
 * @param {object} [props.alertRef] receives the element, so the page can move focus to it
 * @param {Record<string, string>} [props.fieldLabels]
 */
export function ProblemAlert({ problem, alertRef, fieldLabels = {}, children }) {
  if (!problem) return null
  const noLeader = problem.kind === 'no-leader'
  const calm = CALM_KINDS.has(problem.kind)
  const Icon = calm ? Info : AlertTriangle
  return (
    <div ref={alertRef} tabIndex={-1} role={noLeader ? 'status' : 'alert'} data-testid="action-error"
      data-kind={problem.kind} data-reason={problem.reason ?? undefined}
      className={cn('flex gap-2 rounded-md border p-3 text-sm focus:outline-none',
        calm ? 'border-warning/60 bg-warning/10' : 'border-destructive/40 bg-destructive/10')}>
      <Icon className={cn('mt-0.5 size-4 shrink-0', calm ? 'text-warning' : 'text-destructive')} aria-hidden="true" />
      <div className="min-w-0">
        {noLeader && <p className="font-medium text-warning">No leader</p>}
        <p className={cn('font-medium', !calm && 'text-destructive')}>{problem.message}</p>
        {problem.kind === 'busy' && problem.actionInProgress && (
          <p className="mt-1 text-muted-foreground">Running now: {problem.actionInProgress}</p>
        )}
        {problem.fieldErrors && (
          <ul className="mt-1 list-disc pl-5 text-muted-foreground">
            {Object.entries(problem.fieldErrors).map(([field, text]) => (
              <li key={field}>{fieldLabels[field] ?? field}: {text}</li>
            ))}
          </ul>
        )}
        {children}
      </div>
    </div>
  )
}
