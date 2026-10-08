import { useId } from 'react'
import { ShieldOff, CircleX } from 'lucide-react'
import { cn } from '@/lib/utils'
import { usePoliteAnnouncement } from '@/modules/multithreading/usePoliteAnnouncement'
import { flowSummary } from './flowModel'
import { capacityLabel, formatMillis, strategyLabel, threadsText } from './labels'

function percent(share) {
  return share === null ? null : Math.round(share * 100)
}

function capacityText(lane) {
  const parts = [capacityLabel(lane.capacity)]
  if (lane.threads !== null) parts.push(threadsText(lane.threads))
  if (lane.workMultiplier !== null) parts.push(`${lane.workMultiplier}× work`)
  return parts.join(', ')
}

function statusText(lane) {
  if (lane.crashed) return 'Crashed'
  if (!lane.healthy) return 'Taken out by the breaker'
  return 'Up'
}

function requestsText(lane) {
  if (lane.requests === null) return 'No requests yet'
  const noun = lane.requests === 1 ? 'request' : 'requests'
  const share = percent(lane.share)
  return share === null ? `${lane.requests} ${noun}` : `${lane.requests} ${noun}, ${share}%`
}

/** The lane's full state in one sentence: its accessible name. */
function laneLabel(lane) {
  const parts = [`Node ${lane.nodeId}, ${capacityText(lane)}`, statusText(lane), requestsText(lane)]
  if (lane.inFlight !== null) parts.push(`${lane.inFlight} in flight`)
  if (lane.failedAttempts > 0) parts.push(`${lane.failedAttempts} attempts refused, rerouted`)
  if (lane.declinedAttempts > 0) parts.push(`${lane.declinedAttempts} attempts declined, rerouted`)
  return parts.join('; ')
}

function Lane({ lane }) {
  const share = percent(lane.share)
  const average = formatMillis(lane.averageLatencyMillis)
  // The connector from the balancer gets thicker with the lane's share (1 to 7 px).
  const connector = lane.share === null ? 1 : 1 + Math.round(lane.share * 6)

  return (
    <li aria-label={laneLabel(lane)} data-testid={`lane-${lane.nodeId}`} className="grid grid-cols-[minmax(0,1fr)] items-center md:grid-cols-[1.5rem_minmax(0,1fr)]">
      <span aria-hidden="true" className="hidden bg-primary/60 md:block" style={{ height: `${connector}px` }} />
      <div
        className={cn(
          'space-y-2 rounded-lg border bg-card/80 p-3 text-sm',
          lane.crashed && 'border-dashed border-destructive/70',
          !lane.crashed && !lane.healthy && 'border-dashed border-warning/70',
        )}
      >
        <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
          <span className="font-medium">
            Node {lane.nodeId}
            <span className="ml-2 text-xs font-normal text-muted-foreground">{capacityText(lane)}</span>
          </span>
          <span
            className={cn(
              'inline-flex items-center gap-1 text-xs font-medium',
              lane.crashed ? 'text-destructive' : lane.healthy ? 'text-muted-foreground' : 'text-warning',
            )}
          >
            {lane.crashed && <CircleX aria-hidden="true" className="size-3.5" />}
            {!lane.crashed && !lane.healthy && <ShieldOff aria-hidden="true" className="size-3.5" />}
            {statusText(lane)}
          </span>
        </div>

        <div className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-3">
          <div aria-hidden="true" className="h-2.5 overflow-hidden rounded-full bg-muted">
            <div
              className="h-full rounded-full bg-primary transition-[width] duration-500 motion-reduce:transition-none"
              style={{ width: `${share ?? 0}%` }}
            />
          </div>
          <span className="text-xs tabular-nums">{requestsText(lane)}</span>
        </div>

        <p className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
          <span>Average {average === null ? '—' : `${average} ms`}</span>
          {lane.inFlight !== null && <span className="tabular-nums">{lane.inFlight} in flight</span>}
          {lane.failedAttempts > 0 && (
            <span className="text-destructive">{lane.failedAttempts} attempts refused, rerouted</span>
          )}
          {lane.declinedAttempts > 0 && (
            <span className="text-warning">{lane.declinedAttempts} attempts declined, rerouted</span>
          )}
        </p>
      </div>
    </li>
  )
}

/**
 * The page's bold element (R17): the balancer on the left and one lane per worker, each
 * showing how many of the requests it served (a bar with its number and share as text), its
 * average latency, live requests in flight, and attempts it refused (crashed) or declined.
 * After a comparison a radio group picks which strategy's split to draw.
 *
 * Everything comes from the model (flowModel): nothing is drawn as a number that was not
 * measured. State is text as well as colour (Crashed, Taken out by the breaker), the summary
 * is announced politely at most every two seconds from inside this positioned figure, and
 * widths animate only when the data changes, never under prefers-reduced-motion.
 *
 * @param {object} props
 * @param {object} props.model from flowModel(overview, phaseStrategy)
 * @param {(strategy: string) => void} [props.onPhaseChange]
 */
export function BalancerFlow({ model, onPhaseChange }) {
  const summary = flowSummary(model)
  const announcement = usePoliteAnnouncement(summary)
  const pickerName = useId()
  const live = model.mode === 'live'

  return (
    <figure data-testid="balancer-flow" className="relative rounded-xl border bg-gradient-to-br from-navy/60 to-card p-5 shadow-inner">
      <figcaption className="mb-4 space-y-1">
        <span className="block text-base font-semibold">Where the requests went</span>
        <span className="block text-sm text-muted-foreground">{summary}</span>
      </figcaption>
      <p aria-live="polite" className="sr-only">{announcement}</p>

      {model.mode === 'phase' && (
        <fieldset className="mb-4">
          <legend className="mb-2 text-xs font-medium text-muted-foreground">Show the split for</legend>
          <div className="flex flex-wrap gap-2">
            {model.phases.map((phase) => (
              <label
                key={phase.strategy}
                className={cn(
                  'cursor-pointer rounded-md border px-3 py-1.5 text-xs focus-within:ring-2 focus-within:ring-ring',
                  phase.strategy === model.strategy && 'border-primary bg-primary/15 font-medium',
                )}
              >
                <input
                  type="radio"
                  name={pickerName}
                  value={phase.strategy}
                  checked={phase.strategy === model.strategy}
                  onChange={() => onPhaseChange?.(phase.strategy)}
                  className="sr-only"
                />
                {strategyLabel(phase.strategy)}
              </label>
            ))}
          </div>
        </fieldset>
      )}

      <div className="grid gap-4 md:grid-cols-[11rem_minmax(0,1fr)] md:items-center">
        <div className="rounded-lg border border-primary/60 bg-primary/10 p-3 text-sm">
          <p className="font-semibold">Balancer</p>
          <p className="text-xs text-muted-foreground">
            {model.strategy ? strategyLabel(model.strategy) : model.kind === 'comparison' ? 'Comparison' : 'No strategy yet'}
          </p>
          {live && (
            <p className="mt-1 text-xs tabular-nums">
              {model.answered ?? 0} answered, {model.inFlightTotal ?? 0} in flight
            </p>
          )}
        </div>
        {model.lanes.length > 0 ? (
          <ol aria-label="Workers" className="space-y-2">
            {model.lanes.map((lane) => <Lane key={lane.nodeId} lane={lane} />)}
          </ol>
        ) : (
          <p className="text-sm text-muted-foreground">No workers are listed yet.</p>
        )}
      </div>

      {model.mode === 'failed' && (
        <p role="alert" className="mt-4 rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm">
          <span className="font-semibold text-destructive">Failed.</span> {model.error}
        </p>
      )}
    </figure>
  )
}
