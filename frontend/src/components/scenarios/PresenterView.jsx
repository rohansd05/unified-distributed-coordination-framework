import { useEffect, useRef } from 'react'
import { X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { STEP_STATE_LABEL, currentStep, isActive } from './scenarioModel'
import { StepMarker } from './StepRail'

/**
 * Full-screen narration for the projector: the current step's narration in large type, what was
 * observed beneath it, a row of step markers, and the verdict once the run ends. Escape or the
 * close button leaves it.
 *
 * @param {object} props
 * @param {object|null} props.run
 * @param {object|null} props.scenario the run's scenario (title, summary)
 * @param {() => void} props.onClose
 */
export function PresenterView({ run, scenario, onClose }) {
  const closeRef = useRef(null)
  useEffect(() => {
    closeRef.current?.focus()
    const onKey = (e) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const step = currentStep(run)
  const finished = run && !isActive(run)
  const headline = !run ? 'Choose a scenario to run.' : finished ? run.verdict : step?.narration ?? scenario?.summary
  const detail = finished ? run.endState?.description : step?.message

  return (
    <div role="dialog" aria-modal="true" aria-label="Presenter view" data-testid="presenter-view"
      className="fixed inset-0 z-50 flex flex-col bg-background px-6 py-6 sm:px-12 sm:py-10">
      <div className="flex items-start justify-between gap-4">
        <div className="space-y-1">
          <p className="text-sm text-muted-foreground">{scenario?.title ?? 'Scenarios'}</p>
          {run && step && !finished && (
            <p className="text-base">
              Step {step.index} of {run.steps.length}
              <span className="ml-3 text-muted-foreground">{STEP_STATE_LABEL[step.state] ?? step.state}</span>
            </p>
          )}
        </div>
        <Button ref={closeRef} variant="outline" size="sm" onClick={onClose}>
          <X aria-hidden="true" /> Exit presenter view
        </Button>
      </div>

      <div className="flex flex-1 flex-col justify-center gap-6">
        <p aria-live="polite" data-testid="presenter-narration"
          className={cn('max-w-5xl text-balance text-3xl font-semibold leading-tight sm:text-5xl',
            finished && run.state === 'PASSED' && 'text-success', finished && run.state === 'FAILED' && 'text-warning')}>
          {headline}
        </p>
        {detail && <p className="max-w-4xl text-lg text-muted-foreground sm:text-2xl">{detail}</p>}
      </div>

      {run?.steps?.length > 0 && (
        <ol aria-label="Progress" className="flex flex-wrap items-center gap-2">
          {run.steps.map((s) => (
            <li key={s.index} className="flex items-center gap-2">
              <StepMarker state={s.state} className="size-7" />
              <span className="sr-only">Step {s.index}: {STEP_STATE_LABEL[s.state] ?? s.state}</span>
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}
