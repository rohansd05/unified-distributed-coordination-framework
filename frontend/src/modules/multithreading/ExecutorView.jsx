import { cn } from '@/lib/utils'
import { capacityLabel } from './labels'
import { executorSummary } from './executorSummary'
import { usePoliteAnnouncement } from './usePoliteAnnouncement'

function Outcome({ label, value, tone }) {
  return (
    <div className="flex items-baseline justify-between gap-3 rounded-md bg-background/50 px-3 py-2">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className={cn('text-lg font-semibold tabular-nums', tone)}>{value}</dd>
    </div>
  )
}

/**
 * The page's bold element (R17): one node's executor as a pipeline. Requests wait in the
 * bounded queue tank, run on the worker-thread lanes and end completed, failed or rejected.
 *
 * Every number comes from the executor snapshot (stats): the lit lanes are its active-thread
 * count; the executor does not say which threads are busy, so lanes are numbered rather than
 * named. Without stats (never started, or crashed) nothing is drawn as a number. State is
 * text as well as colour (busy lanes are striped and say "busy"), the summary is announced
 * politely at most every two seconds, and transitions are off under prefers-reduced-motion.
 *
 * @param {object} props
 * @param {object} [props.node] a node from the overview (nodeId, nodeStatus, capacity, threads, workMultiplier, stats)
 */
export function ExecutorView({ node }) {
  const summary = executorSummary(node)
  const announcement = usePoliteAnnouncement(summary)

  if (!node) {
    return null
  }

  const crashed = node.nodeStatus === 'CRASHED'
  const stats = crashed ? null : node.stats
  const laneCount = stats?.maxPoolSize ?? node.threads
  const busy = stats?.activeThreads ?? 0
  const queued = stats?.queuedRequests ?? 0
  const capacity = stats?.queueCapacity ?? 0
  const fill = stats && capacity > 0 ? Math.min(100, (queued / capacity) * 100) : 0

  return (
    <figure
      data-testid="executor-view"
      className={cn(
        'relative rounded-xl border bg-gradient-to-br from-navy/60 to-card p-5 shadow-inner',
        crashed && 'border-destructive/60 border-dashed',
      )}
    >
      <figcaption className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
        <span className="text-base font-semibold">
          Node {node.nodeId} executor
          <span className="ml-2 text-sm font-normal text-muted-foreground">
            {capacityLabel(node.capacity)}, {node.threads} {node.threads === 1 ? 'thread' : 'threads'}, {node.workMultiplier}× work per request
          </span>
        </span>
        <span className="text-sm text-muted-foreground">{summary}</span>
      </figcaption>
      <p aria-live="polite" className="sr-only">{announcement}</p>

      {crashed && (
        <p className="rounded-md border border-destructive/40 bg-destructive/10 p-4 text-sm">
          <span className="font-semibold text-destructive">Crashed.</span> This node&apos;s executor is shut down,
          so it accepts no work. Recover the node on the Cluster page to start a fresh executor.
        </p>
      )}

      {!crashed && (
        <div className="grid items-stretch gap-4 md:grid-cols-[minmax(0,1fr)_minmax(0,2fr)_minmax(0,1fr)]">
          <div className="space-y-2">
            <p className="text-xs font-medium text-muted-foreground">Bounded queue</p>
            <div
              role="meter"
              aria-label="Queue"
              aria-valuemin={0}
              aria-valuemax={stats ? capacity : undefined}
              aria-valuenow={stats ? queued : undefined}
              aria-valuetext={stats ? `${queued} of ${capacity} queued` : 'Not started yet'}
              className="relative h-28 overflow-hidden rounded-lg border bg-background/60"
            >
              <div
                className="absolute inset-x-0 bottom-0 bg-primary/70 transition-[height] duration-500 motion-reduce:transition-none"
                style={{ height: `${fill}%` }}
              />
            </div>
            <p className="text-sm tabular-nums">{stats ? `${queued} of ${capacity} waiting` : 'Not started yet'}</p>
          </div>

          <div className="space-y-2">
            <p className="text-xs font-medium text-muted-foreground">
              Worker threads {stats ? `(${busy} of ${laneCount} busy)` : ''}
            </p>
            <ol aria-label="Worker threads" className="space-y-2">
              {Array.from({ length: laneCount }, (_, index) => {
                const isBusy = Boolean(stats) && index < busy
                return (
                  <li
                    key={index}
                    aria-label={`Thread lane ${index + 1} of ${laneCount}: ${stats ? (isBusy ? 'busy' : 'idle') : 'not started'}`}
                    className={cn(
                      'flex h-9 items-center justify-between rounded-md border px-3 text-xs transition-colors duration-300 motion-reduce:transition-none',
                      isBusy
                        ? 'border-success/60 bg-success/20 bg-[repeating-linear-gradient(135deg,transparent_0,transparent_6px,hsl(var(--success)/0.25)_6px,hsl(var(--success)/0.25)_12px)]'
                        : 'border-border bg-background/40 text-muted-foreground',
                    )}
                  >
                    <span>Lane {index + 1}</span>
                    <span className={cn('font-medium', isBusy && 'text-success')}>
                      {stats ? (isBusy ? 'busy' : 'idle') : 'not started'}
                    </span>
                  </li>
                )
              })}
            </ol>
          </div>

          <div className="space-y-2">
            <p className="text-xs font-medium text-muted-foreground">Outcomes in history</p>
            {stats ? (
              <dl className="space-y-2">
                <Outcome label="Completed" value={stats.statusCounts?.COMPLETED ?? '—'} tone="text-success" />
                <Outcome label="Failed" value={stats.statusCounts?.FAILED ?? '—'} tone="text-destructive" />
                <Outcome label="Rejected" value={stats.statusCounts?.REJECTED ?? '—'} tone="text-warning" />
              </dl>
            ) : (
              <p className="rounded-md bg-background/50 p-3 text-sm text-muted-foreground">
                Not started yet. This node&apos;s executor starts with its first batch.
              </p>
            )}
          </div>
        </div>
      )}
    </figure>
  )
}
