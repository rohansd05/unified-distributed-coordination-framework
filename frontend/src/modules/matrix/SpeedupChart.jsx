import { outcomeLabel } from '@/modules/mpi/labels'
import { formatSpeedup, seriesModel } from './matrixModel'

const WIDTH = 360
const HEIGHT = 210
const LEFT = 30
const RIGHT = 8
const TOP = 22
const BASELINE = HEIGHT - 34
export const MIN_BAR = 2   // a measured speedup is never drawn as nothing

function gapText(slot) {
  return slot.runId === null ? 'not run' : `run ${slot.runId}: ${outcomeLabel(slot.outcome).toLowerCase()}`
}

function describe(slot) {
  if (slot.kind === 'point') return `Size ${slot.size}: speedup ${slot.speedup} (run ${slot.runId}, ${slot.ranks} ranks)`
  return `Size ${slot.size}: no speedup, ${slot.runId === null ? 'not run yet' : `newest run ${slot.runId} was ${outcomeLabel(slot.outcome).toLowerCase()}`}`
}

/**
 * The measured speedup per configured series size, from GET /series: linear from 0 to the largest
 * of 1, the served ceiling and the largest point, so nothing is clipped. Every bar has its value
 * printed above it; a size with no usable run is a dashed outline saying why, never a 0. Dashed
 * lines mark 1.0 (as fast as the sequential product) and the served ceiling for the nodes live
 * now. Hand-drawn SVG; a list below gives every value in words.
 *
 * @param {object} props
 * @param {object|null} props.series the MatrixSeriesDto
 * @param {object|null} props.overview the MatrixOverviewDto (speedupCeiling)
 */
export function SpeedupChart({ series, overview }) {
  if (!series) return null
  const { slots, ceiling, top } = seriesModel(series, overview)
  const slotWidth = (WIDTH - LEFT - RIGHT) / Math.max(1, slots.length)
  const barWidth = Math.max(6, Math.min(34, slotWidth * 0.45))
  const y = (value) => BASELINE - (value / top) * (BASELINE - TOP)
  const line = (value, label, testId) => (
    <g data-testid={testId}>
      <line x1={LEFT} x2={WIDTH - RIGHT} y1={y(value)} y2={y(value)} stroke="hsl(var(--muted-foreground))" strokeDasharray="4 4" strokeWidth="1" />
      <text x={LEFT - 4} y={y(value) + 3} textAnchor="end" className="fill-muted-foreground text-[9px]">{value}</text>
      <text x={WIDTH - RIGHT} y={y(value) - 3} textAnchor="end" className="fill-muted-foreground text-[9px]">{label}</text>
    </g>
  )
  return (
    <div data-testid="speedup-chart" className="space-y-2 rounded-lg border bg-card p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="text-sm font-semibold">Speedup by size</h3>
        <span data-testid="series-progress" className="text-xs text-muted-foreground">
          {series.sizesWithSpeedup} of {series.sizesRequired} sizes needed have a speedup{series.done ? ': enough for the experiment' : ''}
        </span>
      </div>
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label={`Measured speedup for ${slots.length} sizes`} className="h-auto w-full max-w-xl">
        <line x1={LEFT} x2={WIDTH - RIGHT} y1={BASELINE} y2={BASELINE} stroke="hsl(var(--border))" />
        <text x={LEFT - 4} y={BASELINE + 3} textAnchor="end" className="fill-muted-foreground text-[9px]">0</text>
        {line(1, '1.0: as fast as sequential', 'line-one')}
        {ceiling !== null && ceiling !== 1 && line(ceiling, 'ceiling with the nodes live now', 'line-ceiling')}
        {slots.map((slot, index) => {
          const center = LEFT + index * slotWidth + slotWidth / 2
          let mark
          if (slot.kind === 'point') {
            const height = Math.max(MIN_BAR, BASELINE - y(slot.speedup))
            mark = (
              <g data-testid="speedup-bar">
                <rect x={center - barWidth / 2} y={BASELINE - height} width={barWidth} height={height} rx="1.5" fill="hsl(var(--primary))" />
                <text data-testid="speedup-value" x={center} y={BASELINE - height - 4} textAnchor="middle"
                  className="fill-foreground text-[10px] font-semibold">{formatSpeedup(slot.speedup)}</text>
              </g>
            )
          } else {
            mark = (
              <g data-testid="speedup-gap">
                <rect x={center - barWidth / 2} y={BASELINE - 26} width={barWidth} height={26} fill="none"
                  stroke="hsl(var(--muted-foreground))" strokeDasharray="3 3" />
                <text x={center} y={BASELINE - 30} textAnchor="middle" className="fill-muted-foreground text-[9px]">{gapText(slot)}</text>
              </g>
            )
          }
          return (
            <g key={slot.key} data-testid="speedup-slot" data-kind={slot.kind}>
              <title>{describe(slot)}</title>
              {mark}
              <text x={center} y={BASELINE + 13} textAnchor="middle" className="fill-foreground text-[10px]">n = {slot.size}</text>
              {slot.kind === 'point' && (
                <text x={center} y={BASELINE + 25} textAnchor="middle" className="fill-muted-foreground text-[9px]">{slot.ranks} ranks</text>
              )}
            </g>
          )
        })}
      </svg>
      <ul className="sr-only" data-testid="speedup-list">
        {slots.map((slot) => <li key={slot.key}>{describe(slot)}</li>)}
      </ul>
      <p className="text-[11px] text-muted-foreground">
        Bars are measured speedups (sequential time divided by parallel time), on a linear scale. A bar shorter than {MIN_BAR} px
        is drawn at {MIN_BAR} px so it stays visible; the number above it is the measured value (three significant digits, the
        full value in its tooltip), never read off the bar. A dashed outline is a size with no usable run.
        {ceiling === null ? ' No ceiling is drawn: no node is live now.' : ''}
      </p>
      <p className="text-xs text-muted-foreground"><span className="font-medium text-foreground">Which runs.</span> {series.rule}</p>
      {series.ranksDiffer && series.ranksNote && (
        <p data-testid="ranks-note" className="rounded-md border border-warning/60 bg-warning/10 p-2 text-xs">{series.ranksNote}</p>
      )}
      {series.basis && <p className="text-xs text-muted-foreground"><span className="font-medium text-foreground">Speedup basis.</span> {series.basis}</p>}
    </div>
  )
}
