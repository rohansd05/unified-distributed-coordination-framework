import { cn } from '@/lib/utils'
import { STEP_STATE_LABEL, factLabel, formatDuration, formatFact } from './scenarioModel'

/** One shape per state, so colour is never the only signal (the label beside it says it in words). */
export function StepMarker({ state, className }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" className={cn('size-6 shrink-0', className)} data-marker={state}>
      {state === 'PENDING' && <circle cx="12" cy="12" r="7" className="fill-background stroke-muted-foreground" strokeWidth="2" />}
      {state === 'RUNNING' && (
        <>
          <circle cx="12" cy="12" r="9" className="fill-none stroke-primary/40 motion-safe:animate-pulse" strokeWidth="3" />
          <circle cx="12" cy="12" r="5" className="fill-primary" />
        </>
      )}
      {state === 'PASSED' && (
        <>
          <circle cx="12" cy="12" r="9" className="fill-success" />
          <path d="M7.5 12.5l3 3 6-6.5" className="fill-none stroke-success-foreground" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
        </>
      )}
      {state === 'FAILED' && (
        <>
          <rect x="3.5" y="3.5" width="17" height="17" rx="4" className="fill-destructive" />
          <path d="M8.5 8.5l7 7M15.5 8.5l-7 7" className="stroke-destructive-foreground" strokeWidth="2.2" strokeLinecap="round" />
        </>
      )}
      {state === 'SKIPPED' && (
        <>
          <circle cx="12" cy="12" r="7" className="fill-background stroke-muted-foreground" strokeWidth="2" strokeDasharray="3 3" />
          <path d="M8.5 12h7" className="stroke-muted-foreground" strokeWidth="2" strokeLinecap="round" />
        </>
      )}
    </svg>
  )
}

function Facts({ facts }) {
  const entries = Object.entries(facts ?? {})
  if (!entries.length) return null
  return (
    <details className="group mt-2 text-xs">
      <summary className="cursor-pointer select-none text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded">
        Observed facts ({entries.length})
      </summary>
      <dl className="mt-2 grid grid-cols-[minmax(0,auto)_minmax(0,1fr)] gap-x-4 gap-y-1 rounded-md bg-background/60 p-3">
        {entries.map(([key, value]) => (
          <div key={key} className="contents">
            <dt className="text-muted-foreground">{factLabel(key)}</dt>
            <dd className="break-words tabular-nums">{formatFact(value)}</dd>
          </div>
        ))}
      </dl>
    </details>
  )
}

/**
 * The live step list: a vertical rail of step markers, each with its narration, state in words,
 * duration, the observed message and the facts behind it.
 *
 * @param {object} props
 * @param {Array<object>} props.steps the run's steps (StepDto shape)
 */
export function StepRail({ steps }) {
  return (
    <ol aria-label="Steps" className="space-y-0">
      {steps.map((step, i) => {
        const duration = formatDuration(step.durationMs)
        const last = i === steps.length - 1
        return (
          <li key={step.index} data-testid="scenario-step" data-state={step.state}
            aria-current={step.state === 'RUNNING' ? 'step' : undefined}
            className="relative grid grid-cols-[1.5rem_minmax(0,1fr)] gap-x-3 pb-5 last:pb-0">
            {!last && (
              <span aria-hidden="true"
                className={cn('absolute left-[11px] top-7 bottom-1 w-0.5 rounded-full motion-safe:transition-colors',
                  step.state === 'PASSED' ? 'bg-success/60' : 'bg-border')} />
            )}
            <StepMarker state={step.state} className="mt-0.5" />
            <div className={cn('min-w-0 rounded-lg px-3 py-2 motion-safe:transition-colors',
              step.state === 'RUNNING' && 'bg-primary/10 ring-1 ring-primary/30')}>
              <div className="flex flex-wrap items-baseline justify-between gap-x-3 text-xs">
                <span className="text-muted-foreground">Step {step.index} of {steps.length}</span>
                <span className="flex gap-2">
                  <span className={cn(step.state === 'FAILED' && 'text-destructive', step.state === 'PASSED' && 'text-success')}>
                    {STEP_STATE_LABEL[step.state] ?? step.state}
                  </span>
                  {duration && <span className="tabular-nums text-muted-foreground">{duration}</span>}
                </span>
              </div>
              <p className={cn('mt-1 text-sm', step.state === 'PENDING' || step.state === 'SKIPPED' ? 'text-muted-foreground' : 'font-medium')}>
                {step.narration}
              </p>
              {step.message && <p data-testid="step-message" className="mt-1 text-sm text-muted-foreground">{step.message}</p>}
              <Facts facts={step.facts} />
            </div>
          </li>
        )
      })}
    </ol>
  )
}
