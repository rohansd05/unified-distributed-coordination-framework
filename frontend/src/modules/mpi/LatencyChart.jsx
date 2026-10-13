import { failureKindLabel, formatNanos } from './labels'

const WIDTH = 320
const HEIGHT = 130
const PAD = 8
const BASELINE = HEIGHT - 30
const MIN_BAR = 2   // a measured value is never drawn as nothing

/** The words for one slot, used in its title and the screen-reader list. */
function describe(slot) {
  const where = `Rank ${slot.rank}, node ${slot.nodeId}`
  if (slot.status === 'DELIVERED') {
    return typeof slot.nanos === 'number' ? `${where}: latency ${formatNanos(slot.nanos)}` : `${where}: delivered, latency not measured`
  }
  if (slot.status === 'FAILED') {
    const time = formatNanos(slot.timeToFailureNanos)
    return `${where}: failed, ${failureKindLabel(slot.failureKind).toLowerCase()}${time ? `, after ${time} (time to failure, not a latency)` : ''}`
  }
  if (slot.status === 'NOT_ATTEMPTED') return `${where}: not attempted, no latency`
  return `${where}: the root's own part, no transfer`
}

/**
 * The latency of each rank's transfer in the shown collective, in rank order, scaled to the
 * slowest delivered one. Only a delivered transfer has a bar. A failed one is a cross labelled
 * "failed", a not-attempted one an empty dashed outline, the root's own part a dot: none of them
 * is a zero bar, and a time to failure is never drawn as a latency. Hand drawn SVG; a list below
 * gives every value in words.
 *
 * @param {object} props
 * @param {{slots: object[], max: number|null}} props.model from chartModel
 * @param {string|null} [props.latencyBasis] the collective's latencyBasis
 */
export function LatencyChart({ model, latencyBasis }) {
  const { slots, max } = model
  if (slots.length === 0) {
    return (
      <div data-testid="latency-chart" className="rounded-lg border bg-card p-4 text-sm text-muted-foreground">
        Transfer latencies appear here after a collective that sends something. Start with a broadcast.
      </div>
    )
  }
  const slot = (WIDTH - 2 * PAD) / slots.length
  const width = Math.max(4, Math.min(26, slot * 0.6))
  return (
    <div data-testid="latency-chart" className="rounded-lg border bg-card p-4">
      <div className="mb-2 flex flex-wrap items-baseline justify-between gap-2 text-xs text-muted-foreground">
        <span>Latency per rank, in rank order</span>
        {max !== null && <span className="tabular-nums">tallest bar {formatNanos(max)}</span>}
      </div>
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label={`Transfer latency of ${slots.length} ranks`} className="h-32 w-full">
        <line x1={PAD} y1={BASELINE} x2={WIDTH - PAD} y2={BASELINE} stroke="hsl(var(--border))" strokeWidth="1" />
        {slots.map((item, index) => {
          const x = PAD + index * slot + (slot - width) / 2
          const center = x + width / 2
          let mark
          if (item.status === 'DELIVERED' && typeof item.nanos === 'number') {
            const height = Math.max(MIN_BAR, (item.nanos / max) * (BASELINE - PAD))
            mark = <rect data-testid="latency-bar" x={x} y={BASELINE - height} width={width} height={height} rx="2" fill="hsl(var(--primary))" />
          } else if (item.status === 'FAILED') {
            mark = (
              <g data-testid="latency-failed">
                <path d={`M${center - 5} ${BASELINE - 16} l10 10 M${center + 5} ${BASELINE - 16} l-10 10`}
                  stroke="hsl(var(--destructive))" strokeWidth="2.5" fill="none" />
                <text x={center} y={BASELINE - 20} textAnchor="middle" className="fill-destructive text-[9px]">failed</text>
              </g>
            )
          } else if (item.status === 'NOT_ATTEMPTED') {
            mark = (
              <g data-testid="latency-not-attempted">
                <rect x={x} y={BASELINE - 24} width={width} height={24} fill="none" stroke="hsl(var(--muted-foreground))"
                  strokeDasharray="3 3" />
                <text x={center} y={BASELINE - 28} textAnchor="middle" className="fill-muted-foreground text-[9px]">not attempted</text>
              </g>
            )
          } else {
            mark = (
              <g data-testid="latency-local">
                <circle cx={center} cy={BASELINE - 5} r="4" fill="hsl(var(--muted-foreground))" />
                <text x={center} y={BASELINE - 13} textAnchor="middle" className="fill-muted-foreground text-[9px]">root</text>
              </g>
            )
          }
          return (
            <g key={item.key} data-testid="latency-slot" data-status={item.status}>
              <title>{describe(item)}</title>
              {mark}
              <text x={center} y={BASELINE + 12} textAnchor="middle" className="fill-muted-foreground text-[9px]">r{item.rank}</text>
              <text x={center} y={BASELINE + 23} textAnchor="middle" className="fill-muted-foreground text-[9px]">n{item.nodeId}</text>
            </g>
          )
        })}
      </svg>
      <ul className="sr-only" data-testid="latency-list">
        {slots.map((item) => <li key={item.key}>{describe(item)}</li>)}
      </ul>
      <p className="mt-1 text-[11px] text-muted-foreground">
        Below each mark: r is the rank, n the node. Bars are measured latencies; a cross is a failed transfer, which has no
        latency; a dashed outline was not attempted; a dot is the root&apos;s own part, which never crosses the network.
      </p>
      {latencyBasis && <p className="mt-1 text-[11px] text-muted-foreground"><span className="font-medium text-foreground">Latency.</span> {latencyBasis}</p>}
    </div>
  )
}
