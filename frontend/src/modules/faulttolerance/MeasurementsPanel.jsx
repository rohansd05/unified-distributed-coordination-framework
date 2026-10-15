import { useId } from 'react'
import { Info } from 'lucide-react'
import { MetricCard } from '@/components/experiment/MetricCard'
import { cn } from '@/lib/utils'
import { OUTCOME_LABELS, expectedDetectionWindow, measurement, words } from './faultToleranceModel'

/** A value the backend sent as null: the words "Not measured" (or `label`), the reason on hover and for screen readers. Never 0. */
export function NotMeasured({ reason, label = 'Not measured' }) {
  return (
    <span data-testid="not-measured" title={reason ?? undefined} className="text-base font-normal text-muted-foreground">
      {label}
      {reason && <span className="sr-only"> ({reason})</span>}
    </span>
  )
}

/**
 * A glossary term as the backend serves it: its label, its kind (Measured or Derived) as a word,
 * and its definition in a tooltip that opens on hover and on keyboard focus of the info button.
 */
export function GlossaryTerm({ entry }) {
  const tooltipId = useId()
  const derived = entry.kind === 'derived'
  return (
    <span className="group relative inline-flex flex-wrap items-center gap-1.5">
      <span>{entry.label}</span>
      <span className={cn('rounded border px-1 py-0.5 text-[10px] leading-none',
        derived ? 'border-dashed border-muted-foreground/70 text-muted-foreground' : 'border-primary/60 text-primary')}>
        {words(entry.kind)}
      </span>
      {entry.definition && (
        <>
          <button type="button" aria-label={`What "${entry.label}" means`} aria-describedby={tooltipId}
            className="rounded-full text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
            <Info aria-hidden="true" className="size-3.5" />
          </button>
          <span role="tooltip" id={tooltipId}
            className="pointer-events-none absolute left-0 top-full z-30 mt-1.5 hidden w-max max-w-[min(18rem,75vw)] rounded-md border bg-popover p-2.5 text-xs font-normal leading-snug text-popover-foreground shadow-lg group-hover:block group-focus-within:block">
            {entry.definition}
          </span>
        </>
      )}
    </span>
  )
}

function Figure({ m }) {
  return m.text ? <span title={m.exact ?? undefined}>{m.text}</span> : <NotMeasured reason={m.reason} />
}

const FOUR = ['detection', 'failover', 'recovery', 'dataLoss']
const BESIDE = ['outage', 'serviceRestored']

/**
 * The latest run's measurements: the four of HANDOFF Section 7 (detection, failover, recovery,
 * data loss), then outage and time to first update beside them, each with its served glossary
 * label, kind and definition. The two derived figures (expected detection window, worst-case
 * failover) sit apart, labelled Derived, for comparison with the measured detection; they are
 * never merged into it. Recovery time is read from the run, never from an event.
 *
 * @param {object} props
 * @param {object|null} props.run the latest run, or null
 * @param {Array} [props.glossary]
 * @param {object} [props.settings]
 */
export function MeasurementsPanel({ run, glossary, settings }) {
  const loss = run?.dataLoss
  const band = expectedDetectionWindow(settings)
  const detection = run?.measurements?.detectionMillis
  const term = (id) => glossary?.find((g) => g.id === id) ?? { id, label: id, kind: 'derived', definition: '' }
  const worst = settings?.worstCaseFailoverMillis
  const card = (id) => {
    const m = measurement(id, run, glossary)
    return (
      <MetricCard key={id} label={<GlossaryTerm entry={m} />} value={<Figure m={m} />}
        hint={id === 'dataLoss' && m.text ? 'lost of acknowledged' : undefined}
        simulated={id === 'dataLoss' && loss?.simulated ? (loss.simulatedReason ?? true) : undefined} />
    )
  }

  return (
    <div data-testid="measurements-panel" className="space-y-4">
      <p className="text-sm text-muted-foreground">
        {run
          ? `From run ${run.runId}: ${OUTCOME_LABELS[run.outcome] ?? words(run.outcome)}.`
          : 'No failover since the reset. Crash the primary while the update stream runs to measure one.'}
      </p>
      <div data-testid="four-measurements" className="grid grid-cols-1 divide-y rounded-lg border bg-card sm:grid-cols-4 sm:divide-x sm:divide-y-0">
        {FOUR.map(card)}
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <div data-testid="beside-measurements" className="grid grid-cols-1 divide-y rounded-lg border bg-card sm:grid-cols-2 sm:divide-x sm:divide-y-0">
          {BESIDE.map(card)}
        </div>
        <div data-testid="derived-figures" className="rounded-lg border border-dashed p-3 text-sm">
          <p className="text-xs text-muted-foreground">From the settings, for comparison. Not measurements.</p>
          <dl className="mt-2 space-y-2">
            <div>
              <dt className="text-xs text-muted-foreground"><GlossaryTerm entry={term('expectedDetection')} /></dt>
              <dd className="tabular-nums">
                {band ? `${band.from} to ${band.to} ms` : <NotMeasured label="Not available" reason="The detector settings were not served" />}
              </dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground"><GlossaryTerm entry={term('worstCaseFailover')} /></dt>
              <dd className="tabular-nums">
                {typeof worst === 'number' ? `${worst} ms` : <NotMeasured label="Not available" reason="Not served" />}
              </dd>
            </div>
          </dl>
          {band && typeof detection === 'number' && (
            <p data-testid="detection-comparison" className="mt-2 text-xs text-muted-foreground">
              The measured detection is {detection >= band.from && detection <= band.to ? 'inside' : 'outside'} the expected window.
            </p>
          )}
        </div>
      </div>
    </div>
  )
}
