import { OutcomeBadge } from '@/modules/mpi/CollectiveResult'
import { clockTime, exactNanos, formatNanos } from '@/modules/rmi/labels'
import { Missing } from './RunResult'

function passedText(passed) {
  if (passed === true) return 'Passed'
  if (passed === false) return 'Failed'
  return <Missing text="Not available" reason="Unknown, or nothing was assembled" />
}

/**
 * The kept runs, newest first as the backend sent them, each row keyed by run id plus start time
 * (ids restart after a cluster reset). Selecting a row shows that run in full below; no request
 * is made, the list entry is the whole run.
 *
 * @param {object} props
 * @param {object[]} props.rows from historyRows
 * @param {number|null} props.historySize limits.historySize
 * @param {string|null} props.selectedKey
 * @param {(row: object) => void} props.onSelect
 */
export function HistoryTable({ rows, historySize, selectedKey, onSelect }) {
  if (rows.length === 0) {
    return <p data-testid="history-empty" className="rounded-lg border bg-card p-4 text-sm text-muted-foreground">No runs yet.</p>
  }
  return (
    <div className="space-y-1">
      <div className="overflow-x-auto rounded-md border">
        <table data-testid="history-table" className="w-full min-w-[36rem] text-left text-xs">
          <thead className="bg-muted/30 text-muted-foreground">
            <tr>
              <th scope="col" className="px-2 py-1.5 font-medium">Run</th>
              <th scope="col" className="px-2 py-1.5 font-medium">Started</th>
              <th scope="col" className="px-2 py-1.5 font-medium">Size</th>
              <th scope="col" className="px-2 py-1.5 font-medium">Root</th>
              <th scope="col" className="px-2 py-1.5 font-medium">Outcome</th>
              <th scope="col" className="px-2 py-1.5 font-medium">Parallel time</th>
              <th scope="col" className="px-2 py-1.5 font-medium">Verification</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.key} data-testid="history-row" data-selected={row.key === selectedKey ? 'true' : 'false'}
                className="border-t data-[selected=true]:bg-primary/10">
                <td className="px-2 py-1.5">
                  <button type="button" onClick={() => onSelect(row)}
                    className="rounded font-mono text-primary underline underline-offset-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                    {row.runId}<span className="sr-only">, show run {row.runId}</span>
                  </button>
                </td>
                <td className="px-2 py-1.5 font-mono">{row.startedAt ? clockTime(row.startedAt) : ''}</td>
                <td className="px-2 py-1.5 tabular-nums">{row.size}</td>
                <td className="px-2 py-1.5 font-mono">{row.rootNodeId}</td>
                <td className="px-2 py-1.5"><OutcomeBadge outcome={row.outcome} /></td>
                <td className="px-2 py-1.5 tabular-nums">
                  {formatNanos(row.parallelTotalNanos)
                    ? <span title={exactNanos(row.parallelTotalNanos)}>{formatNanos(row.parallelTotalNanos)}</span>
                    : <Missing reason={row.outcome === 'REFUSED' ? 'Refused: nothing ran' : 'Not measured'} />}
                </td>
                <td className="px-2 py-1.5">{passedText(row.passed)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {typeof historySize === 'number' && (
        <p className="text-xs text-muted-foreground">The backend keeps the latest {historySize} runs; ids restart at 1 after a cluster reset.</p>
      )}
    </div>
  )
}
