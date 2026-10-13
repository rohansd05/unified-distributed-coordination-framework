import { CheckCircle2, XCircle } from 'lucide-react'
import { NotAvailable } from './CallResult'
import { clockTime, exactNanos, failureKindLabel, formatNanos, methodLabel } from './labels'

/**
 * The recent calls and comparisons (GET /calls, newest first, replaced wholesale on every read).
 * A row's button loads that entry (GET /calls/{id}) into the detail panel the page renders.
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
        No calls yet: start with a ping to any node.
      </p>
    )
  }
  return (
    <div className="overflow-x-auto rounded-lg border bg-card">
      <table data-testid="history-table" className="w-full min-w-[34rem] text-left text-sm">
        <caption className="px-4 py-2 text-left text-xs text-muted-foreground">
          Newest first{typeof historySize === 'number' ? `; the backend keeps the latest ${historySize}` : ''}. Times are UTC.
        </caption>
        <thead className="border-b text-xs text-muted-foreground">
          <tr>
            <th scope="col" className="px-4 py-2 font-medium">Call</th>
            <th scope="col" className="px-2 py-2 font-medium">Time</th>
            <th scope="col" className="px-2 py-2 font-medium">Method</th>
            <th scope="col" className="px-2 py-2 font-medium">From, to</th>
            <th scope="col" className="px-2 py-2 font-medium">Outcome</th>
            <th scope="col" className="px-4 py-2 font-medium">Round trip</th>
          </tr>
        </thead>
        <tbody className="divide-y">
          {rows.map((row) => (
            <tr key={row.key} data-testid="history-row"
              className={row.key === selectedKey ? 'bg-primary/10' : undefined}>
              <td className="px-4 py-1.5">
                <button type="button" onClick={() => onSelect(row)} aria-current={row.key === selectedKey ? 'true' : undefined}
                  className="rounded font-mono text-primary underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  aria-label={`Show ${row.kind === 'COMPARISON' ? 'comparison' : 'call'} ${row.id}`}>
                  {row.id}
                </button>
                {row.kind === 'COMPARISON' && <span className="ml-1 text-xs text-muted-foreground">comparison</span>}
              </td>
              <td className="px-2 py-1.5 font-mono text-xs">{clockTime(row.calledAt) ?? ''}</td>
              <td className="px-2 py-1.5">{methodLabel(row.method)}</td>
              <td className="px-2 py-1.5">{row.fromNode}, {row.targetNode}</td>
              <td className="px-2 py-1.5">
                <span className="inline-flex items-center gap-1">
                  {row.ok
                    ? <CheckCircle2 aria-hidden="true" className="size-3.5 text-success" />
                    : <XCircle aria-hidden="true" className="size-3.5 text-destructive" />}
                  {row.ok ? 'Succeeded' : `Failed: ${failureKindLabel(row.failureKind).toLowerCase()}`}
                </span>
              </td>
              <td className="px-4 py-1.5 tabular-nums">
                {row.nanos === null || row.nanos === undefined
                  ? <NotAvailable reason="No round trip: the call did not complete" />
                  : <span title={exactNanos(row.nanos)}>{formatNanos(row.nanos)}</span>}
                {row.localNanos !== null && row.localNanos !== undefined && (
                  <span className="block text-xs text-muted-foreground">direct {formatNanos(row.localNanos)}</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
