import { useMemo } from 'react'
import { useModuleEvents } from '@/hooks/useModuleEvents'
import { formatEventType, formatRelativeTime } from '@/components/events/eventFormatters'

/**
 * One module's event log, newest first, live (docs/HANDOFF.md 8.4, section 6). Lamport values
 * are in monospace; the list clears on a cluster reset. Generic: knows only the module id.
 *
 * @param {object} props
 * @param {string} props.moduleId
 * @param {object} [props.api] for tests; defaults to the shared API client
 * @param {number} [props.limit=50]
 */
export function ModuleEventLog({ moduleId, api, limit = 50 }) {
  const { events, loading, error } = useModuleEvents({ moduleId, limit, ...(api ? { api } : {}) })
  const newestFirst = useMemo(() => [...events].reverse(), [events])

  return (
    <div data-testid="module-event-log" className="overflow-hidden rounded-lg border bg-card">
      <div className="flex items-center justify-between border-b bg-navy/20 px-4 py-2.5">
        <p className="text-xs text-muted-foreground">Newest first, in causal (Lamport) order</p>
        {!loading && !error && (
          <span className="text-xs text-muted-foreground">
            {events.length} {events.length === 1 ? 'event' : 'events'}
          </span>
        )}
      </div>

      {loading && (
        <div className="space-y-2 p-4" aria-busy="true">
          <div className="h-4 w-3/4 animate-pulse rounded bg-muted/40 motion-reduce:animate-none" />
          <div className="h-4 w-2/3 animate-pulse rounded bg-muted/40 motion-reduce:animate-none" />
          <p className="text-xs text-muted-foreground">Loading events…</p>
        </div>
      )}

      {!loading && error && (
        <p role="alert" className="p-4 text-sm text-destructive">
          Could not load this module&apos;s events: {error.detail || error.message || 'the backend did not answer'}.
        </p>
      )}

      {!loading && !error && newestFirst.length === 0 && (
        <p className="p-4 text-sm text-muted-foreground">
          No events for this module yet. They appear here as soon as you use the controls above.
        </p>
      )}

      {!loading && !error && newestFirst.length > 0 && (
        <ol className="max-h-80 divide-y divide-border/60 overflow-y-auto">
          {newestFirst.map((event) => (
            <li key={event.sequence} className="flex flex-wrap items-baseline gap-x-3 gap-y-1 px-4 py-2 text-xs">
              <span
                className="shrink-0 rounded border border-border/70 bg-navy/50 px-1.5 py-0.5 font-mono text-[11px] font-semibold text-primary"
                title={`Lamport time ${event.lamportTime}`}
              >
                L:{event.lamportTime}
              </span>
              <span className="w-14 shrink-0 font-medium">{event.nodeId === 0 ? 'Cluster' : `Node ${event.nodeId}`}</span>
              <span className="shrink-0 text-muted-foreground">{formatEventType(event.type)}</span>
              <span className="min-w-0 flex-1 text-foreground/90">{event.message}</span>
              <time dateTime={event.wallTime} className="shrink-0 text-[11px] text-muted-foreground">
                {formatRelativeTime(event.wallTime)}
              </time>
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}
