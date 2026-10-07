/** Requests per worker thread, most first; requests that never got a thread are left out. */
function tally(requests) {
  const counts = new Map()
  for (const request of requests ?? []) {
    if (request?.threadName) {
      counts.set(request.threadName, (counts.get(request.threadName) ?? 0) + 1)
    }
  }
  return [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
}

/**
 * Which worker thread ran how many of the node's recent requests: the evidence that one
 * batch is shared out between several threads. Counted from the request history the page
 * loaded, so the heading says how many requests it covers.
 *
 * @param {object} props
 * @param {Array<object>|null} props.requests null while loading
 */
export function ThreadTally({ requests }) {
  if (requests === null) {
    return <p className="text-sm text-muted-foreground">Loading this node&apos;s requests…</p>
  }
  const rows = tally(requests)
  if (rows.length === 0) {
    return <p className="text-sm text-muted-foreground">No request has run on a worker thread yet.</p>
  }
  const top = rows[0][1]

  return (
    <div data-testid="thread-tally" className="space-y-2">
      <p className="text-xs text-muted-foreground">Requests per worker thread, from the last {requests.length} requests</p>
      <ul className="space-y-1.5">
        {rows.map(([thread, count]) => (
          <li key={thread} className="grid grid-cols-[minmax(0,10rem)_1fr_auto] items-center gap-3 text-xs">
            <span className="truncate font-mono">{thread}</span>
            <span aria-hidden="true" className="h-2 rounded-full bg-muted">
              <span className="block h-2 rounded-full bg-primary" style={{ width: `${(count / top) * 100}%` }} />
            </span>
            <span className="tabular-nums">{count} {count === 1 ? 'request' : 'requests'}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}
