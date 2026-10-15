import { formatEventType } from '@/components/events/eventFormatters'
import { cn } from '@/lib/utils'
import { OUTCOME_LABELS, formatMillis, tickLabel, timelineModel, words } from './faultToleranceModel'

// SVG geometry (viewBox units). The figure scales to its box; below about 36rem it scrolls inside its own box.
const VIEW_W = 720
const LABEL_X = 4
const AXIS_X0 = 216
const AXIS_X1 = 632
const GUTTER_X = 684
const TOP = 40
const ROW_H = 30


function headline(run) {
  const to = run.newPrimaryId ? `node ${run.newPrimaryId} at epoch ${run.newEpoch}` : 'no new primary yet'
  return `Node ${run.oldPrimaryId} at epoch ${run.oldEpoch} to ${to}. ${OUTCOME_LABELS[run.outcome] ?? words(run.outcome)}.`
}

/**
 * The latest run as a timeline on a real time axis: one row per milestone kind, recorded ones at
 * their offset (ms from the crash on the backend's monotonic clock, never aligned with wall
 * times), the others as hollow markers in the "Not yet" column on the right, never at 0. Bars
 * show the measured intervals between recorded milestones. The derived expected detection window
 * is a dashed band, labelled Derived, separate from the measured detection marker.
 *
 * Live: every refresh of the run (events, and polling while the run is open) can fill a pending
 * row; if the axis grows, markers glide to their new place, except under prefers-reduced-motion.
 * The list under the figure says the same in text, names each milestone's event in the log
 * (linked by kind, not by time) and is what a screen reader reads.
 *
 * @param {object} props
 * @param {object|null} props.run
 * @param {object} [props.settings]
 * @param {Array} [props.glossary]
 */
export function FailoverTimeline({ run, settings, glossary }) {
  const model = timelineModel(run, settings)
  if (!model) {
    return (
      <div data-testid="failover-timeline" className="rounded-lg border border-dashed p-5 text-sm text-muted-foreground">
        No failover yet. Start the update stream, then crash the primary: the crash, the detection, the promotion and the
        restored service appear here as they happen.
      </div>
    )
  }

  const { rows, intervals, band, axis, crashObserved } = model
  const x = (ms) => AXIS_X0 + (ms / axis.end) * (AXIS_X1 - AXIS_X0)
  const y = (index) => TOP + index * ROW_H + ROW_H / 2
  const rowIndex = Object.fromEntries(rows.map((row, index) => [row.kind, index]))
  const bottom = TOP + rows.length * ROW_H
  const height = bottom + 28
  const showBand = band && band.from < axis.end
  const term = (id) => glossary?.find((g) => g.id === id)?.label ?? id

  return (
    <figure data-testid="failover-timeline" className="space-y-3 rounded-lg border bg-card p-4">
      <figcaption className="space-y-0.5">
        <p className="text-sm font-semibold">Run {run.runId}: {headline(run)}</p>
        <p className="text-xs text-muted-foreground">
          Time since the crash, on the backend&apos;s monotonic clock (not wall time).
          {run.outcome === 'IN_PROGRESS' && ' The run is still open: rows fill in as the backend records them.'}
        </p>
      </figcaption>

      <div className="overflow-x-auto">
        <svg viewBox={`0 0 ${VIEW_W} ${height}`} className="h-auto w-full min-w-[36rem]" role="img"
          aria-label={`Timeline of run ${run.runId}. The list below gives every milestone in text.`}>
          {showBand && (
            <g data-testid="detection-band">
              <rect x={x(band.from)} y={TOP - 4} width={x(Math.min(band.to, axis.end)) - x(band.from)} height={bottom - TOP + 4}
                className="fill-primary/10 stroke-primary/50" strokeDasharray="4 3" />
              <text x={x(band.from)} y={TOP - 10} className="fill-muted-foreground text-[11px]">
                {term('expectedDetection')} (derived)
              </text>
            </g>
          )}
          {axis.ticks.map((tick) => (
            <g key={tick}>
              <line x1={x(tick)} x2={x(tick)} y1={TOP - 4} y2={bottom} className="stroke-border/70" strokeWidth={1} />
              <text x={x(tick)} y={bottom + 16} textAnchor="middle" className="fill-muted-foreground text-[11px]">
                {tickLabel(tick, axis.step)}
              </text>
            </g>
          ))}
          <text x={GUTTER_X} y={TOP - 10} textAnchor="middle" className="fill-muted-foreground text-[11px]">Not yet</text>

          {intervals.map((interval) => (
            <line key={interval.id} data-testid={`interval-${interval.id}`} x1={x(interval.from)} x2={x(interval.to)}
              y1={y(rowIndex[interval.endKind])} y2={y(rowIndex[interval.endKind])} strokeWidth={5} strokeLinecap="round"
              className="stroke-primary/50 transition-all duration-300 motion-reduce:transition-none">
              <title>{term(interval.id)} (measured)</title>
            </line>
          ))}

          {rows.map((row, index) => {
            const cy = y(index)
            const recorded = row.state === 'recorded'
            const cx = recorded ? x(row.offset) : GUTTER_X
            const valueRight = cx < AXIS_X1 - 90
            return (
              <g key={row.kind} data-testid={`milestone-${row.kind}`} data-state={row.state}>
                <text x={LABEL_X} y={cy + 4} className={cn('text-[12px]', recorded ? 'fill-foreground' : 'fill-muted-foreground')}>
                  {row.label}{row.nodeId ? `, node ${row.nodeId}` : ''}
                </text>
                <line x1={AXIS_X0} x2={AXIS_X1} y1={cy} y2={cy} className="stroke-border/40" strokeWidth={1} />
                {recorded ? (
                  <>
                    <circle data-testid="marker" cx={cx} cy={cy} r={6}
                      className="fill-primary stroke-background transition-all duration-300 motion-reduce:transition-none" strokeWidth={2}>
                      <title>{`${row.label}: ${formatMillis(row.offset)} after the crash`}</title>
                    </circle>
                    <text x={valueRight ? cx + 11 : cx - 11} y={cy - 7} textAnchor={valueRight ? 'start' : 'end'}
                      className="fill-foreground font-mono text-[11px]">
                      {formatMillis(row.offset)}
                    </text>
                  </>
                ) : (
                  <circle data-testid="marker" cx={cx} cy={cy} r={6} className="fill-none stroke-muted-foreground" strokeWidth={1.5}
                    strokeDasharray={row.state === 'not-reached' ? '2 2' : undefined}>
                    <title>{row.note}</title>
                  </circle>
                )}
              </g>
            )
          })}
        </svg>
      </div>

      <ol aria-label="Milestones" className="grid gap-x-6 gap-y-1 text-xs sm:grid-cols-2">
        {rows.map((row) => (
          <li key={row.kind} data-testid="milestone-item" data-state={row.state}>
            <span className="font-medium">{row.label}</span>
            {row.nodeId ? ` (node ${row.nodeId})` : ''}
            {': '}
            {row.state === 'recorded'
              ? <span className="font-mono">{formatMillis(row.offset)}</span>
              : <span className="text-muted-foreground">{row.note}</span>}
            {row.state === 'recorded' && ' after the crash'}
            {row.kind === 'CRASHED' && crashObserved && (
              <span className="text-warning"> Seen as the node went down (slightly late): the crash was started outside this page.</span>
            )}
            <span className="block text-muted-foreground">In the event log: {formatEventType(row.eventType)}</span>
          </li>
        ))}
      </ol>
    </figure>
  )
}
