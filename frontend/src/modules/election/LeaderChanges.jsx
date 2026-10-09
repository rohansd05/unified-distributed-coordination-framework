import { leaderChangeText } from './electionModel'

/**
 * "Leader changes (cluster log)": the LEADER_CHANGED events of the cluster's own log, newest
 * first, with their real Lamport values and node ids. Read from the cluster log directly (not
 * through the module event log) so the event exists only once.
 *
 * @param {object} props
 * @param {Array<object>} props.changes causal order, see useLeaderChanges
 * @param {boolean} props.loading
 * @param {Error|null} props.error
 */
export function LeaderChanges({ changes, loading, error }) {
  const newestFirst = [...changes].reverse()
  return (
    <section data-testid="leader-changes" aria-labelledby="leader-changes-heading" className="space-y-2">
      <h3 id="leader-changes-heading" className="text-sm font-semibold">Leader changes (cluster log)</h3>
      {loading && <p className="text-xs text-muted-foreground">Loading the cluster log…</p>}
      {!loading && error && (
        <p role="alert" className="text-sm text-destructive">
          Could not load the cluster log: {error.detail || error.message || 'the backend did not answer'}.
        </p>
      )}
      {!loading && !error && newestFirst.length === 0 && (
        <p className="text-sm text-muted-foreground">No leader change yet. The first election sets one.</p>
      )}
      {!loading && !error && newestFirst.length > 0 && (
        <ol className="divide-y divide-border/60 rounded-lg border bg-card">
          {newestFirst.map((event) => (
            <li key={event.sequence} className="flex flex-wrap items-baseline gap-x-3 gap-y-1 px-3 py-2 text-xs">
              <span className="font-mono text-primary" title={`Lamport time ${event.lamportTime}`}>L:{event.lamportTime}</span>
              <span className="font-medium">{event.nodeId === 0 ? 'Cluster' : `Node ${event.nodeId}`}</span>
              <span className="min-w-0 flex-1">{leaderChangeText(event)}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
