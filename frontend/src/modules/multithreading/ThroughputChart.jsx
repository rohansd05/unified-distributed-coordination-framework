const WIDTH = 300
const HEIGHT = 90
const PAD = 6

/**
 * A small hand-drawn SVG line of the selected node's throughput (requests completed per
 * second, as the executor reports it over its sliding window), one point per refresh. No chart
 * library. The chart is one image with a text summary; fewer than two samples shows guidance.
 *
 * @param {object} props
 * @param {Array<{at: number, value: number}>} props.samples oldest first
 */
export function ThroughputChart({ samples = [] }) {
  const points = samples.filter((sample) => Number.isFinite(sample?.value))

  if (points.length < 2) {
    return (
      <div data-testid="throughput-chart" className="rounded-lg border bg-card p-4 text-sm text-muted-foreground">
        Throughput is drawn here while a batch runs.
      </div>
    )
  }

  const max = Math.max(1, ...points.map((point) => point.value))
  const latest = points[points.length - 1].value
  const step = (WIDTH - 2 * PAD) / (points.length - 1)
  const coordinates = points.map((point, index) => {
    const x = PAD + index * step
    const y = HEIGHT - PAD - (point.value / max) * (HEIGHT - 2 * PAD)
    return `${x.toFixed(1)},${y.toFixed(1)}`
  })
  const area = `${PAD},${HEIGHT - PAD} ${coordinates.join(' ')} ${(PAD + (points.length - 1) * step).toFixed(1)},${HEIGHT - PAD}`
  const label = `Throughput over the last ${points.length} samples; latest ${latest} requests per second, highest ${max}.`

  return (
    <div data-testid="throughput-chart" className="rounded-lg border bg-card p-4">
      <div className="mb-2 flex items-baseline justify-between text-xs text-muted-foreground">
        <span>Throughput (requests per second)</span>
        <span className="tabular-nums">latest {latest}</span>
      </div>
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label={label} className="h-24 w-full" preserveAspectRatio="none">
        <line x1={PAD} y1={HEIGHT - PAD} x2={WIDTH - PAD} y2={HEIGHT - PAD} stroke="hsl(var(--border))" strokeWidth="1" />
        <polygon points={area} fill="hsl(var(--primary) / 0.18)" />
        <polyline points={coordinates.join(' ')} fill="none" stroke="hsl(var(--primary))" strokeWidth="2" vectorEffect="non-scaling-stroke" />
      </svg>
      <p className="mt-1 text-[11px] text-muted-foreground">Scale 0 to {max}; one point per refresh.</p>
    </div>
  )
}
