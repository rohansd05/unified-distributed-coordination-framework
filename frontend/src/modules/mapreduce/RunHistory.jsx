import { cn } from '@/lib/utils'
import { inputTypeLabel, jobFallbackLabel, runStateLabel } from './labels'
import { dash, historyRows, ms } from './runModel'

/**
 * The backend's run history (GET /runs, newest first). Picking a row shows that run; "Latest"
 * goes back to following the newest run. Every missing value is "—".
 *
 * @param {object} props
 * @param {Array} props.runs RunSummaryDto[]
 * @param {Array} [props.jobs] overview jobs, for titles
 * @param {string|null} props.selectedRunId the shown run's id
 * @param {string|null} props.pinnedRunId the picked history entry, or null when following the latest
 * @param {(runId: string|null) => void} props.onSelect
 */
export function RunHistory({ runs, jobs = [], selectedRunId, pinnedRunId, onSelect }) {
  const rows = historyRows(runs)
  const title = (jobId) => jobs.find((job) => job.id === jobId)?.title ?? jobFallbackLabel(jobId)
  if (rows.length === 0) {
    return <p className="text-sm text-muted-foreground">No runs yet. Each run you start is listed here.</p>
  }
  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold">Recent runs</h3>
        <button
          type="button"
          onClick={() => onSelect(null)}
          aria-pressed={pinnedRunId === null}
          className="rounded-md px-2 py-1 text-xs text-primary underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          Follow the latest run
        </button>
      </div>
      <ol data-testid="run-history" className="max-h-64 divide-y divide-border/60 overflow-auto rounded-lg border">
        {rows.map((row) => (
          <li key={row.runId}>
            <button
              type="button"
              onClick={() => onSelect(row.runId)}
              aria-current={row.runId === selectedRunId ? 'true' : undefined}
              className={cn(
                'flex w-full flex-wrap items-baseline gap-x-3 gap-y-1 px-3 py-2 text-left text-xs hover:bg-navy/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring',
                row.runId === selectedRunId && 'bg-primary/10',
              )}
            >
              <span className="font-mono text-muted-foreground">{row.runId}</span>
              <span className="font-medium">{title(row.jobId)}</span>
              <span>{dash(row.inputName ?? inputTypeLabel(row.inputType))}</span>
              <span>{runStateLabel(row.state)}</span>
              <span className="tabular-nums">{ms(row.totalMillis)}</span>
              <span className="tabular-nums">{dash(row.resultKeys)} keys</span>
              <span className="tabular-nums">{dash(row.retriedTasks)} retried</span>
              {row.crashWorkerId !== null && <span className="text-destructive">crash on node {row.crashWorkerId}</span>}
            </button>
          </li>
        ))}
      </ol>
    </div>
  )
}
