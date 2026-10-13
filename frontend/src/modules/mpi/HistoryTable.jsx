import { NotAvailable } from '@/modules/rmi/CallResult'
import { OutcomeBadge } from './CollectiveResult'
import { clockTime, exactNanos, formatNanos, operationLabel } from './labels'

/**
 * The recent collectives (GET /collectives, newest first, replaced wholesale on every read). Each
 * entry is a whole CollectiveDto, so selecting a row shows it at once, with no further request.
 *
 * @param {object} props
 * @param {object[]} props.rows from historyRows
 * @param {number|null} [props.historySize] how many the backend keeps (overview limits)
 * @param {string|null} [props.selectedKey]
 * @param {(row: object) => void} props.onSelect
 */
export function HistoryTable({ rows, historySize, selectedKey, onSelect }) {
  if (rows.length === 0) {
    return (
      <p data-testid="history-empty" className="rounded-lg border bg-card p-4 text-sm text-muted-foreground">
        No collectives yet: start with a broadcast.
      </p>
    )
  }
  return (
    <div className="overflow-x-auto rounded-lg border bg-card">
      <table data-testid="history-table" className="w-full min-w-[36rem] text-left text-sm">
        <caption className="px-4 py-2 text-left text-xs text-muted-foreground">
          Newest first{typeof historySize === 'number' ? `; the backend keeps the latest ${historySize}` : ''}. Times are UTC.
          Ids restart at 1 after a cluster reset.
        </caption>
        <thead className="border-b text-xs text-muted-foreground">
          <tr>
            <th scope="col" className="px-4 py-2 font-medium">Collective</th>
            <th scope="col" className="px-2 py-2 font-medium">Time</th>
            <th scope="col" className="px-2 py-2 font-medium">Operation</th>
            <th scope="col" className="px-2 py-2 font-medium">Root</th>
            <th scope="col" className="px-2 py-2 font-medium">Outcome</th>
            <th scope="col" className="px-2 py-2 font-medium">Delivered</th>
            <th scope="col" className="px-4 py-2 font-medium">Total time</th>
          </tr>
        </thead>
        <tbody className="divide-y">
          {rows.map((row) => (
            <tr key={row.key} data-testid="history-row" className={row.key === selectedKey ? 'bg-primary/10' : undefined}>
              <td className="px-4 py-1.5">
                <button type="button" onClick={() => onSelect(row)} aria-current={row.key === selectedKey ? 'true' : undefined}
                  className="rounded font-mono text-primary underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  aria-label={`Show collective ${row.id}`}>
                  {row.id}
                </button>
              </td>
              <td className="px-2 py-1.5 font-mono text-xs">{clockTime(row.startedAt) ?? ''}</td>
              <td className="px-2 py-1.5">{operationLabel(row.operation)} of {row.count}</td>
              <td className="px-2 py-1.5">Node {row.rootNodeId}</td>
              <td className="px-2 py-1.5"><OutcomeBadge outcome={row.outcome} /></td>
              <td className="px-2 py-1.5 tabular-nums">{row.counts.delivered} of {row.counts.remote}</td>
              <td className="px-4 py-1.5 tabular-nums">
                {row.totalNanos === null
                  ? <NotAvailable reason={row.outcome === 'REFUSED' ? 'Refused: nothing was sent' : 'No remote transfer was attempted'} />
                  : <span title={exactNanos(row.totalNanos)}>{formatNanos(row.totalNanos)}</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
