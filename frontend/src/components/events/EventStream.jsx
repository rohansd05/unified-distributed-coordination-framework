import { useMemo } from 'react'
import { formatEventType, formatRelativeTime } from './eventFormatters'

/**
 * Live Event Stream Component
 *
 * Displays cluster events with newest first so actions are immediately visible.
 * Monospace is strictly reserved for Lamport timestamps (L:12).
 *
 * @param {object} props
 * @param {Array} [props.events] List of event DTOs
 * @param {boolean} [props.loading] Loading state
 * @param {Error|null} [props.error] Error state
 * @param {string} [props.className] Additional CSS class
 */
export function EventStream({
  events = [],
  loading = false,
  error = null,
  className = '',
}) {
  // Newest first: reverse causal array for display
  const displayedEvents = useMemo(() => {
    return [...events].reverse()
  }, [events])

  return (
    <div
      data-testid="event-stream"
      className={`rounded-lg border border-border bg-card overflow-hidden ${className}`}
    >
      <div className="flex items-center justify-between border-b border-border bg-navy/20 px-4 py-3">
        <div>
          <h2 className="text-sm font-medium text-foreground">Live event stream</h2>
          <p className="text-xs text-muted-foreground">
            Causally ordered by Lamport logical time, node id, and sequence
          </p>
        </div>
        {!loading && !error && (
          <span className="text-xs text-muted-foreground">
            {displayedEvents.length} {displayedEvents.length === 1 ? 'event' : 'events'}
          </span>
        )}
      </div>

      {loading && (
        <div className="space-y-3 p-4">
          <div className="h-5 w-3/4 animate-pulse rounded bg-muted/40" />
          <div className="h-5 w-full animate-pulse rounded bg-muted/40" />
          <div className="h-5 w-2/3 animate-pulse rounded bg-muted/40" />
          <p className="text-center text-xs text-muted-foreground">Loading event history…</p>
        </div>
      )}

      {!loading && error && (
        <div className="p-4 text-center">
          <p className="text-sm font-medium text-destructive">Unable to load event stream</p>
          <p className="mt-1 text-xs text-muted-foreground">
            {error.detail || error.message || 'Check backend connection'}
          </p>
        </div>
      )}

      {!loading && !error && displayedEvents.length === 0 && (
        <div className="p-6 text-center text-sm text-muted-foreground">
          No events recorded yet. Actions like crashing a node will appear here.
        </div>
      )}

      {!loading && !error && displayedEvents.length > 0 && (
        <div className="divide-y divide-border/60 max-h-[420px] overflow-y-auto">
          {displayedEvents.map((event) => {
            const nodeLabel = event.nodeId === 0 ? 'Cluster' : `Node ${event.nodeId}`
            const readableType = formatEventType(event.type)
            const relTime = formatRelativeTime(event.wallTime)

            return (
              <div
                key={`event-${event.sequence}`}
                className="flex flex-col gap-1 px-4 py-2.5 hover:bg-accent/40 transition-colors sm:flex-row sm:items-center sm:justify-between text-xs"
              >
                <div className="flex items-center gap-2.5 min-w-0 flex-1">
                  <span
                    className="font-mono text-[11px] font-semibold text-primary px-1.5 py-0.5 rounded bg-navy/50 border border-border/70 shrink-0"
                    title={`Lamport time: ${event.lamportTime}`}
                  >
                    L:{event.lamportTime}
                  </span>

                  <span className="font-medium text-foreground shrink-0 w-16">
                    {nodeLabel}
                  </span>

                  <span className="font-medium text-muted-foreground shrink-0 hidden md:inline-block w-28 truncate">
                    {readableType}
                  </span>

                  <span
                    className="text-foreground/90 truncate flex-1"
                    title={event.message}
                  >
                    {event.message}
                  </span>
                </div>

                <div className="flex items-center justify-between sm:justify-end gap-2 text-muted-foreground shrink-0 pl-7 sm:pl-0">
                  <span className="md:hidden text-[11px] font-medium text-muted-foreground">
                    {readableType}
                  </span>
                  <time dateTime={event.wallTime} className="text-[11px]">
                    {relTime}
                  </time>
                </div>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
