import { Button } from '@/components/ui/button'
import { usePoliteAnnouncement } from '@/modules/multithreading/usePoliteAnnouncement'
import { cn } from '@/lib/utils'
import { CELL_STATES, cellStateLabel, cellStateMeaning, nodeStatusText, valueText } from './labels'
import { gridSummary } from './replicaModel'

/*
 * Each state has its own shape as well as its own word, so colour is never the only signal:
 * current a filled disc with a tick, stale a half-filled disc, missing a dashed empty ring,
 * ahead an upward triangle, conflict a split diamond, unreachable a crossed square, not held a
 * short bar, not compared a hollow ring.
 */
const GLYPH_TONE = {
  CURRENT: 'text-success',
  STALE: 'text-warning',
  MISSING: 'text-warning',
  AHEAD: 'text-primary',
  CONFLICT: 'text-destructive',
  UNREACHABLE: 'text-destructive',
  ABSENT: 'text-muted-foreground',
}

/** The state's shape, drawn in currentColor; decoration only (the word beside it carries the meaning). */
export function StateGlyph({ state, className }) {
  const common = { width: 14, height: 14, viewBox: '0 0 14 14', 'aria-hidden': true, focusable: 'false' }
  const tone = GLYPH_TONE[state] ?? 'text-muted-foreground'
  const svgClass = cn('shrink-0', tone, className)
  switch (state) {
    case 'CURRENT':
      return (
        <svg {...common} className={svgClass} data-glyph="current">
          <circle cx="7" cy="7" r="6" fill="currentColor" />
          <path d="M4 7.2 6.2 9.4 10 5" fill="none" stroke="hsl(var(--card))" strokeWidth="1.6" strokeLinecap="round" />
        </svg>
      )
    case 'STALE':
      return (
        <svg {...common} className={svgClass} data-glyph="stale">
          <circle cx="7" cy="7" r="5.5" fill="none" stroke="currentColor" strokeWidth="1.4" />
          <path d="M7 1.5a5.5 5.5 0 0 0 0 11z" fill="currentColor" />
        </svg>
      )
    case 'MISSING':
      return (
        <svg {...common} className={svgClass} data-glyph="missing">
          <circle cx="7" cy="7" r="5.5" fill="none" stroke="currentColor" strokeWidth="1.4" strokeDasharray="2.2 1.8" />
        </svg>
      )
    case 'AHEAD':
      return (
        <svg {...common} className={svgClass} data-glyph="ahead">
          <path d="M7 1.5 12.5 12h-11z" fill="currentColor" />
        </svg>
      )
    case 'CONFLICT':
      return (
        <svg {...common} className={svgClass} data-glyph="conflict">
          <path d="M7 1 13 7 7 13 1 7z" fill="none" stroke="currentColor" strokeWidth="1.4" />
          <path d="M7 1v12" stroke="currentColor" strokeWidth="1.4" />
          <path d="M7 1 1 7l6 6z" fill="currentColor" />
        </svg>
      )
    case 'UNREACHABLE':
      return (
        <svg {...common} className={svgClass} data-glyph="unreachable">
          <rect x="1.5" y="1.5" width="11" height="11" rx="1.5" fill="none" stroke="currentColor" strokeWidth="1.4" />
          <path d="M4 4l6 6M10 4l-6 6" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
        </svg>
      )
    case 'ABSENT':
      return (
        <svg {...common} className={svgClass} data-glyph="absent">
          <path d="M3.5 7h7" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
        </svg>
      )
    default:
      return (
        <svg {...common} className={svgClass} data-glyph="not-compared">
          <circle cx="7" cy="7" r="5.5" fill="none" stroke="currentColor" strokeWidth="1.4" />
        </svg>
      )
  }
}

const CELL_FRAME = {
  CURRENT: 'border-success/50',
  STALE: 'border-warning/70 bg-warning/10',
  MISSING: 'border-dashed border-warning/70',
  AHEAD: 'border-primary/70 bg-primary/10',
  CONFLICT: 'border-destructive/70 bg-destructive/10',
  UNREACHABLE: 'border-destructive/50 bg-[repeating-linear-gradient(135deg,transparent_0_6px,hsl(var(--destructive)/0.12)_6px_8px)]',
  ABSENT: 'border-border/60',
}

function Cell({ cell }) {
  const { state, item } = cell
  return (
    <li
      data-testid="replica-cell"
      data-node={cell.nodeId}
      data-state={state ?? 'none'}
      aria-label={cell.description}
      className={cn(
        'flex min-w-[7.5rem] flex-1 basis-[7.5rem] flex-col gap-1 rounded-lg border bg-card px-3 py-2',
        CELL_FRAME[state] ?? 'border-border/60',
        cell.isReference && 'ring-1 ring-primary/40',
      )}
    >
      <span className="flex items-center justify-between gap-2 text-[11px] text-muted-foreground" aria-hidden="true">
        <span>Node {cell.nodeId}{cell.isReference ? ', primary' : ''}</span>
      </span>
      <span className="truncate text-sm font-semibold" aria-hidden="true">
        {item ? valueText(item.value) : state === 'UNREACHABLE' ? 'No answer' : 'Nothing held'}
      </span>
      <span className="flex items-center gap-1.5 text-xs" aria-hidden="true">
        <StateGlyph state={state} />
        <span>{cellStateLabel(state)}</span>
      </span>
      {item && (
        <span className="font-mono text-[11px] text-muted-foreground" aria-hidden="true">
          L:{item.lamportTime} from node {item.originNode}
        </span>
      )}
    </li>
  )
}

/**
 * The page's one bold element (R17): every replica's version of every key, side by side, with
 * the primary first. Read over TCP by the backend (GET /replicas), so a crashed replica is
 * unreachable rather than a cached value. Each cell shows the value, the state as a word and a
 * shape, and the version's Lamport time and origin; its accessible name says all of it.
 *
 * @param {object} props
 * @param {ReturnType<import('./replicaModel').gridModel>} props.model
 * @param {boolean} props.loading
 * @param {Error|null} props.error the replicas read failed
 * @param {() => void} props.onRetry
 */
export function ReplicaGrid({ model, loading, error, onRetry }) {
  const summary = gridSummary(model)
  const announcement = usePoliteAnnouncement(model.loaded ? summary : '')

  return (
    <figure data-testid="replica-grid" className="relative space-y-4 rounded-xl border bg-gradient-to-br from-navy/50 to-card p-4 sm:p-5">
      <figcaption className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="text-sm font-semibold">Every replica, compared with the primary</span>
        {model.loaded && (
          <span data-testid="grid-summary" className="text-sm text-muted-foreground">{summary}</span>
        )}
      </figcaption>
      <p aria-live="polite" className="sr-only">{announcement}</p>

      {!model.loaded && loading && (
        <div className="space-y-2" aria-busy="true">
          <div className="h-16 animate-pulse rounded-lg bg-card/60 motion-reduce:animate-none" />
          <p className="text-xs text-muted-foreground">Reading every replica…</p>
        </div>
      )}

      {error && (
        <div role="alert" className="rounded-lg border border-destructive/40 bg-card p-4 text-sm">
          <p className="font-semibold text-destructive">Could not read the replicas.</p>
          <p className="mt-1 text-muted-foreground">{error.detail || error.message}</p>
          <Button variant="outline" size="sm" className="mt-3" onClick={onRetry}>Try again</Button>
        </div>
      )}

      {model.loaded && (
        <>
          <ul className="flex flex-wrap gap-2" aria-label="Replicas">
            {model.columns.map((column) => (
              <li
                key={column.nodeId}
                data-testid="replica-column"
                className={cn(
                  'rounded-md border bg-card/70 px-2.5 py-1.5 text-xs',
                  column.isReference && 'border-primary/60',
                  !column.reachable && 'border-destructive/50',
                )}
              >
                <span className="font-medium">Node {column.nodeId}</span>
                {column.isReference && <span className="text-primary">, primary</span>}
                <span className="text-muted-foreground">
                  {', '}
                  {column.reachable
                    ? `${column.itemCount} ${column.itemCount === 1 ? 'key' : 'keys'}, epoch ${column.epoch}`
                    : `${nodeStatusText(column.nodeStatus).toLowerCase()}, not readable`}
                </span>
              </li>
            ))}
          </ul>

          {model.rows.length === 0 ? (
            <p className="rounded-lg border border-dashed p-4 text-sm text-muted-foreground">
              No keys yet. Write one with the form above and it appears here on every replica.
            </p>
          ) : (
            <ul className="space-y-3" aria-label="Keys">
              {model.rows.map((row) => (
                <li key={row.key} data-testid="replica-row" className="space-y-1.5">
                  <p className="break-all font-mono text-sm">{row.key}</p>
                  <ul className="flex flex-wrap gap-2" aria-label={`Replicas of ${row.key}`}>
                    {row.cells.map((cell) => <Cell key={cell.nodeId} cell={cell} />)}
                  </ul>
                </li>
              ))}
            </ul>
          )}

          <ul data-testid="state-legend" aria-label="What the states mean" className="grid gap-x-4 gap-y-1 text-xs text-muted-foreground sm:grid-cols-2">
            {CELL_STATES.map((state) => (
              <li key={state} className="flex items-start gap-1.5">
                <StateGlyph state={state} className="mt-0.5" />
                <span><span className="font-medium text-foreground">{cellStateLabel(state)}</span>: {cellStateMeaning(state)}.</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </figure>
  )
}
