import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { CheckCircle2, Info, ListOrdered, Presentation, WifiOff, XCircle } from 'lucide-react'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { EndStateView } from '@/components/scenarios/EndStateView'
import { PresenterView } from '@/components/scenarios/PresenterView'
import { ScenarioList } from '@/components/scenarios/ScenarioList'
import { StepRail } from '@/components/scenarios/StepRail'
import { scenariosApi } from '@/components/scenarios/scenariosApi'
import { useScenarios } from '@/components/scenarios/useScenarios'
import { RUN_STATE_LABEL, formatDuration, isActive, runDuration, timelineHref } from '@/components/scenarios/scenarioModel'

function Verdict({ run }) {
  const passed = run.state === 'PASSED'
  const Icon = passed ? CheckCircle2 : XCircle
  return (
    <div role="status" data-testid="verdict"
      className={cn('flex items-start gap-3 rounded-lg border px-4 py-3 text-sm',
        passed ? 'border-success/40 bg-success/10' : 'border-warning/40 bg-warning/10')}>
      <Icon className={cn('mt-0.5 size-5 shrink-0', passed ? 'text-success' : 'text-warning')} aria-hidden="true" />
      <div className="space-y-0.5">
        <p className="font-medium">{RUN_STATE_LABEL[run.state] ?? run.state}</p>
        <p>{run.verdict}</p>
      </div>
    </div>
  )
}

function RunPanel({ run, scenario }) {
  const total = formatDuration(runDuration(run))
  return (
    <section aria-labelledby="run-heading" className="space-y-4 rounded-lg border bg-card p-4 sm:p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <h2 id="run-heading" className="text-lg font-semibold">{scenario?.title ?? run.scenarioId}</h2>
          <p className="flex flex-wrap gap-x-3 text-xs text-muted-foreground">
            <span>Run <span className="font-mono">{run.runId}</span></span>
            <span>{RUN_STATE_LABEL[run.state] ?? run.state}</span>
            {total && <span className="tabular-nums">took {total}</span>}
          </p>
        </div>
        {scenario && (
          <Button asChild variant="outline" size="sm">
            <Link to={timelineHref(scenario)}><ListOrdered aria-hidden="true" /> See in Timeline</Link>
          </Button>
        )}
      </div>
      {!isActive(run) && run.verdict && <Verdict run={run} />}
      {run.steps.length > 0
        ? <StepRail steps={run.steps} />
        : <p className="text-sm text-muted-foreground">This run has no steps to show.</p>}
      {run.endState && <EndStateView endState={run.endState} />}
    </section>
  )
}

/**
 * /scenarios: guided demonstrations across modules (9A.6 engine, 9A.7 page). The scenarios on a
 * rail with Run buttons; the latest run live, step by step, from /topic/events with a 1-second
 * poll as fallback; its verdict and the cluster it leaves; a deep link into the Timeline; and a
 * presenter view for the projector.
 */
export function ScenariosPage({ api = scenariosApi }) {
  const { scenarios, loadError, run, starting, startProblem, start, status } = useScenarios({ api })
  const [presenter, setPresenter] = useState(false)
  const closePresenter = useCallback(() => setPresenter(false), [])
  const runScenario = scenarios?.find((s) => s.id === run?.scenarioId) ?? null

  return (
    <div className="space-y-6">
      <PageHeader title="Scenarios"
        description="Guided demonstrations on the real cluster: each one acts across several experiments, narrates every step and reports what it observed." />

      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="flex items-start gap-2 text-sm text-muted-foreground">
          <Info className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          Each run starts with a clean cluster reset and leaves the cluster as it ends, so you can inspect it afterwards.
        </p>
        <Button variant="outline" size="sm" aria-pressed={presenter} onClick={() => setPresenter((p) => !p)}>
          <Presentation aria-hidden="true" /> Presenter view
        </Button>
      </div>

      {(status === 'reconnecting' || status === 'error') && (
        <div role="status" data-testid="disconnected-banner"
          className="flex items-start gap-3 rounded-lg border border-warning/40 bg-warning/10 px-4 py-3 text-sm">
          <WifiOff className="mt-0.5 size-4 shrink-0 text-warning" aria-hidden="true" />
          <p>Live updates disconnected, retrying. While a scenario runs, the page checks it every second instead.</p>
        </div>
      )}

      {startProblem && (
        <div role="status" data-testid="start-problem"
          className="rounded-lg border border-warning/40 bg-warning/10 px-4 py-3 text-sm">
          {startProblem.message}
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-[minmax(0,24rem)_minmax(0,1fr)]">
        <div>
          {loadError && (
            <p role="status" className="rounded-lg border border-warning/40 bg-warning/10 px-4 py-3 text-sm">
              The scenarios could not be loaded: {loadError.detail || loadError.message}
            </p>
          )}
          {!loadError && scenarios == null && <p className="text-sm text-muted-foreground">Loading scenarios…</p>}
          {scenarios?.length === 0 && <p className="text-sm text-muted-foreground">The backend offers no scenarios.</p>}
          {scenarios?.length > 0 && <ScenarioList scenarios={scenarios} run={run} starting={starting} onRun={start} />}
        </div>
        <div>
          {run
            ? <RunPanel run={run} scenario={runScenario} />
            : (
              <section aria-label="No run yet" className="rounded-lg border border-dashed p-6 text-sm text-muted-foreground">
                No scenario has run since the backend started. Run one and its steps appear here as they happen.
              </section>
            )}
        </div>
      </div>

      {presenter && <PresenterView run={run} scenario={runScenario} onClose={closePresenter} />}
    </div>
  )
}
