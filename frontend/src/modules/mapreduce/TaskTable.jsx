import { CheckCircle2, XCircle } from 'lucide-react'
import { taskTypeLabel } from './labels'
import { dash, taskAttemptRows } from './runModel'

/**
 * Every attempt of every task of the run: task, type, attempt number, node, outcome (icon and
 * word, never colour alone) and, for a failed attempt, the backend's reason. A retried task has
 * one row per attempt, so the retry is visible as rows.
 *
 * @param {object} props
 * @param {object|null} props.run the shown RunDto
 */
export function TaskTable({ run }) {
  const rows = taskAttemptRows(run)
  if (rows.length === 0) {
    return <p className="text-sm text-muted-foreground">Tasks and their attempts appear here when a run ends.</p>
  }
  return (
    <div className="max-h-80 overflow-auto rounded-lg border">
      <table data-testid="task-table" className="w-full text-sm">
        <caption className="sr-only">Every task attempt of the run</caption>
        <thead className="sticky top-0 bg-navy/90 text-left text-xs text-muted-foreground">
          <tr>
            <th scope="col" className="px-3 py-2 font-medium">Task</th>
            <th scope="col" className="px-3 py-2 font-medium">Type</th>
            <th scope="col" className="px-3 py-2 font-medium">Attempt</th>
            <th scope="col" className="px-3 py-2 font-medium">Node</th>
            <th scope="col" className="px-3 py-2 font-medium">Outcome</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border/60">
          {rows.map((row) => (
            <tr key={row.key} data-testid="task-row" className={row.retried ? 'bg-warning/5' : undefined}>
              <td className="px-3 py-1.5">{row.task}{row.retried && <span className="ml-2 text-xs text-warning">retried</span>}</td>
              <td className="px-3 py-1.5">{taskTypeLabel(row.taskType)}</td>
              <td className="px-3 py-1.5 tabular-nums">{dash(row.attempt)}</td>
              <td className="px-3 py-1.5 font-mono text-xs">{row.nodeId === null ? '—' : `node ${row.nodeId}`}</td>
              <td className="px-3 py-1.5">
                {row.outcome === 'completed' ? (
                  <span className="inline-flex items-center gap-1 text-success">
                    <CheckCircle2 aria-hidden="true" className="size-3.5" />Completed
                  </span>
                ) : (
                  <span className="inline-flex flex-wrap items-center gap-1 text-destructive">
                    <XCircle aria-hidden="true" className="size-3.5" />Failed
                    {row.reason && <span className="text-xs text-muted-foreground">({row.reason})</span>}
                  </span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
