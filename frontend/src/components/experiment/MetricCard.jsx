import { cn } from '@/lib/utils'
import { isAvailable } from './isAvailable'
import { SimulatedBadge } from './SimulatedBadge'

/**
 * One measurement: label, value with unit, and an optional hint. A missing value shows "—"
 * (read as "not available"), never 0, NaN or undefined: no figure is ever made up (R7).
 *
 * Lay several out in one divided strip rather than a grid of separate cards (R17).
 *
 * @param {object} props
 * @param {string} props.label
 * @param {number|string|null} [props.value]
 * @param {string} [props.unit]
 * @param {string} [props.hint]
 * @param {boolean|string} [props.simulated] true, or the reason, to add a SimulatedBadge
 * @param {string} [props.className]
 */
export function MetricCard({ label, value, unit, hint, simulated, className }) {
  const available = isAvailable(value)

  return (
    <div data-testid="metric-card" className={cn('min-w-0 space-y-1 px-4 py-3', className)}>
      <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <span>{label}</span>
        {simulated && <SimulatedBadge reason={typeof simulated === 'string' ? simulated : undefined} />}
      </div>
      <p className="text-xl font-semibold tabular-nums text-foreground">
        {available ? (
          <>
            {value}
            {unit && <span className="ml-1 text-sm font-normal text-muted-foreground">{unit}</span>}
          </>
        ) : (
          <span aria-label="Not available">—</span>
        )}
      </p>
      {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
    </div>
  )
}
