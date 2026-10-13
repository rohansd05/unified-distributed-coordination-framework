import { failureKindLabel, formatNanos, methodLabel } from './labels'

const WIDTH = 320
const HEIGHT = 110
const PAD = 8
const BASELINE = HEIGHT - 18
const MIN_BAR = 2   // a measured value is never drawn as nothing

/**
 * The last remote round trips as bars, oldest on the left. A completed call gets a bar of its
 * measured round trip (scaled to the largest shown); a failed call gets a cross with its kind,
 * never a zero-height bar. Hand drawn SVG; each mark has its own text in a title, and a list
 * below gives every value in words.
 *
 * @param {object} props
 * @param {{bars: object[], max: number|null}} props.model from chartModel
 */
export function RoundTripChart({ model }) {
  const { bars, max } = model
  if (bars.length === 0) {
    return (
      <div data-testid="round-trip-chart" className="rounded-lg border bg-card p-4 text-sm text-muted-foreground">
        Round trips appear here after the first remote call. Start with a ping.
      </div>
    )
  }
  const slot = (WIDTH - 2 * PAD) / bars.length
  const width = Math.max(4, Math.min(22, slot * 0.6))
  const describe = (bar) => (bar.ok
    ? `Call ${bar.id}, ${methodLabel(bar.method)} to node ${bar.targetNode}: ${formatNanos(bar.nanos)}`
    : `Call ${bar.id}, ${methodLabel(bar.method)} to node ${bar.targetNode}: failed, ${failureKindLabel(bar.failureKind).toLowerCase()}`)

  return (
    <div data-testid="round-trip-chart" className="rounded-lg border bg-card p-4">
      <div className="mb-2 flex flex-wrap items-baseline justify-between gap-2 text-xs text-muted-foreground">
        <span>Remote round trips, oldest first</span>
        {max !== null && <span className="tabular-nums">tallest bar {formatNanos(max)}</span>}
      </div>
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label={`Round trips of the last ${bars.length} remote calls`}
        className="h-28 w-full">
        <line x1={PAD} y1={BASELINE} x2={WIDTH - PAD} y2={BASELINE} stroke="hsl(var(--border))" strokeWidth="1" />
        {bars.map((bar, index) => {
          const x = PAD + index * slot + (slot - width) / 2
          const center = x + width / 2
          if (bar.ok) {
            const height = Math.max(MIN_BAR, (bar.nanos / max) * (BASELINE - PAD))
            return (
              <rect key={bar.key} data-testid="rt-bar" x={x} y={BASELINE - height} width={width} height={height}
                rx="2" fill="hsl(var(--primary))">
                <title>{describe(bar)}</title>
              </rect>
            )
          }
          return (
            <g key={bar.key} data-testid="rt-failure">
              <title>{describe(bar)}</title>
              <path d={`M${center - 5} ${BASELINE - 16} l10 10 M${center + 5} ${BASELINE - 16} l-10 10`}
                stroke="hsl(var(--destructive))" strokeWidth="2.5" fill="none" />
              <text x={center} y={BASELINE + 12} textAnchor="middle" className="fill-destructive text-[9px]">failed</text>
            </g>
          )
        })}
      </svg>
      <ul className="sr-only">
        {bars.map((bar) => <li key={bar.key}>{describe(bar)}</li>)}
      </ul>
      <p className="mt-1 text-[11px] text-muted-foreground">
        Bars are measured round trips; a cross marks a call that failed, which has no round trip.
      </p>
    </div>
  )
}
