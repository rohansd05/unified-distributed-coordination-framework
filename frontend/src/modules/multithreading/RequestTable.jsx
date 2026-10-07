import { cn } from '@/lib/utils'
import { formatMillis, requestStatusLabel, workloadLabel } from './labels'

export const TABLE_ROWS = 20

const STATUS_TONE = {
  COMPLETED: 'text-success',
  FAILED: 'text-destructive',
  REJECTED: 'text-warning',
}

/** A time cell: "—" when the request never reached that stage, never a made-up 0. */
function Millis({ value, show }) {
  const text = show ? formatMillis(value) : null
  return <td className="px-3 py-2 text-right tabular-nums">{text === null ? '—' : `${text} ms`}</td>
}

/**
 * The node's most recent requests, newest first, with the worker thread that ran each (in
 * monospace with the request id). Status is a word, coloured as a second signal. Times a
 * request never reached show "—": a rejected request has no wait or processing time, and an
 * unfinished one has no total yet. The table scrolls inside its own box on narrow screens.
 *
 * @param {object} props
 * @param {Array<object>|null} props.requests newest first; null while loading
 */
export function RequestTable({ requests }) {
  if (requests === null) {
    return <p className="text-sm text-muted-foreground">Loading this node&apos;s requests…</p>
  }
  if (requests.length === 0) {
    return <p className="text-sm text-muted-foreground">No requests on this node yet. Send a batch to see them here.</p>
  }

  return (
    <div data-testid="request-table" className="overflow-x-auto rounded-lg border">
      <table className="w-full min-w-[44rem] text-left text-xs">
        <caption className="sr-only">The latest {Math.min(TABLE_ROWS, requests.length)} requests on this node, newest first</caption>
        <thead className="bg-navy/30 text-muted-foreground">
          <tr>
            <th scope="col" className="px-3 py-2 font-medium">Request</th>
            <th scope="col" className="px-3 py-2 font-medium">Thread</th>
            <th scope="col" className="px-3 py-2 font-medium">Workload</th>
            <th scope="col" className="px-3 py-2 font-medium">Status</th>
            <th scope="col" className="px-3 py-2 text-right font-medium">Queue wait</th>
            <th scope="col" className="px-3 py-2 text-right font-medium">Processing</th>
            <th scope="col" className="px-3 py-2 text-right font-medium">Total</th>
            <th scope="col" className="px-3 py-2 font-medium">Result</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border/60">
          {requests.slice(0, TABLE_ROWS).map((request) => {
            const started = Boolean(request.threadName)
            const ended = ['COMPLETED', 'FAILED', 'REJECTED'].includes(request.status)
            return (
              <tr key={request.id}>
                <td className="px-3 py-2 font-mono">{request.id}</td>
                <td className="px-3 py-2 font-mono">{request.threadName ?? '—'}</td>
                <td className="px-3 py-2">{workloadLabel(request.type)}</td>
                <td className={cn('px-3 py-2 font-medium', STATUS_TONE[request.status])}>{requestStatusLabel(request.status)}</td>
                <Millis value={request.queueWaitMillis} show={started} />
                <Millis value={request.processingMillis} show={started && ended} />
                <Millis value={request.totalMillis} show={started && ended} />
                <td className="max-w-[16rem] truncate px-3 py-2" title={request.resultSummary ?? request.errorMessage ?? ''}>
                  {request.resultSummary ?? request.errorMessage ?? '—'}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
