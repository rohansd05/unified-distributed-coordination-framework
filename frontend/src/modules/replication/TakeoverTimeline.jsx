import { cn } from '@/lib/utils'
import { itemsText, nodesText } from './labels'

function entryTitle(entry) {
  return entry.previousNodeId === null
    ? `Node ${entry.nodeId} selected as primary`
    : `Node ${entry.nodeId} took over from node ${entry.previousNodeId}`
}

function entryDetail(entry) {
  if (entry.previousNodeId === null) {
    return 'First selection: the lowest live node. Nothing to catch up.'
  }
  const sources = entry.catchUpSources.length > 0
    ? `Caught up ${itemsText(entry.appliedFromCatchUp ?? 0)} from ${nodesText(entry.catchUpSources)}`
    : 'No live peer to catch up from'
  const pushed = entry.pushedTo.length > 0 ? `, then pushed its store to ${nodesText(entry.pushedTo)}.` : '.'
  return sources + pushed
}

function catchUpLine(catchUp) {
  if (!catchUp.completed) {
    return `From node ${catchUp.sourceNodeId}: could not read it (${catchUp.failure})`
  }
  return `From node ${catchUp.sourceNodeId}: ${catchUp.applied} of ${catchUp.pulled} applied`
}

/**
 * Who has been the replication primary, as a small timeline: each selection (from the module's
 * PRIMARY_SELECTED events, in causal order, with its Lamport time), the per-source catch-up
 * counts of the latest takeover, and a dashed marker when the selector already picks another
 * node, which takes over at the next write, anti-entropy, stale update or recover. Horizontal
 * from the small breakpoint up, stacked on a phone.
 *
 * @param {object} props
 * @param {ReturnType<import('./replicaModel').timelineModel>} props.model
 */
export function TakeoverTimeline({ model }) {
  const { entries, pending } = model
  if (entries.length === 0 && !pending) {
    return (
      <p data-testid="takeover-timeline" className="rounded-lg border border-dashed p-4 text-sm text-muted-foreground">
        No primary selected yet. The first write selects the lowest live node.
      </p>
    )
  }

  return (
    <ol data-testid="takeover-timeline" aria-label="Primary history" className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-stretch">
      {entries.map((entry, index) => (
        <li
          key={entry.id}
          data-testid="timeline-entry"
          className={cn(
            'relative min-w-0 flex-1 rounded-lg border bg-card px-3 py-2.5 text-sm sm:max-w-[18rem]',
            index === entries.length - 1 && !pending && 'border-primary/60',
          )}
        >
          <p className="flex flex-wrap items-baseline justify-between gap-2">
            <span className="font-medium">{entryTitle(entry)}</span>
            <span className="font-mono text-[11px] text-muted-foreground">L:{entry.lamportTime}</span>
          </p>
          <p className="mt-1 text-xs text-muted-foreground">{entryDetail(entry)}</p>
          {entry.catchUps.length > 0 && (
            <ul className="mt-1.5 space-y-0.5 text-xs" aria-label="Catch-up from each peer">
              {entry.catchUps.map((catchUp) => (
                <li key={catchUp.sourceNodeId}>{catchUpLine(catchUp)}</li>
              ))}
            </ul>
          )}
        </li>
      ))}
      {pending && (
        <li
          data-testid="timeline-pending"
          className="min-w-0 flex-1 rounded-lg border border-dashed border-warning/70 px-3 py-2.5 text-sm sm:max-w-[18rem]"
        >
          <p className="font-medium">Pending: node {pending.nodeId} takes over next</p>
          <p className="mt-1 text-xs text-muted-foreground">
            Node {pending.fromNodeId} was the primary. Node {pending.nodeId} is now the lowest live node, so the next
            write, anti-entropy, stale update or recover runs the takeover: it catches up first, then pushes. Reading
            this page changes nothing.
          </p>
        </li>
      )}
    </ol>
  )
}
