import { Loader2, Play } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { RUN_STATE_LABEL, isActive, moduleLabel } from './scenarioModel'

/**
 * The scenarios as stops on one rail, in the engine's order: title, summary, the modules involved
 * and a Run button. Every button is disabled while any run is active (the engine runs one at a time).
 *
 * @param {object} props
 * @param {Array<object>} props.scenarios
 * @param {object|null} props.run the latest run
 * @param {string|null} props.starting the id being started
 * @param {(id: string) => void} props.onRun
 */
export function ScenarioList({ scenarios, run, starting, onRun }) {
  const busy = isActive(run) || starting != null
  return (
    <ol aria-label="Scenarios" className="relative space-y-3 before:absolute before:bottom-6 before:left-[11px] before:top-6 before:w-0.5 before:rounded-full before:bg-border">
      {scenarios.map((scenario) => {
        const current = run?.scenarioId === scenario.id
        const running = current && isActive(run)
        return (
          <li key={scenario.id} data-testid="scenario-card" className="relative grid grid-cols-[1.5rem_minmax(0,1fr)] gap-x-3">
            <span aria-hidden="true" className={cn('relative z-10 mt-5 size-6 rounded-full border-2 bg-background',
              running ? 'border-primary motion-safe:animate-pulse' : current ? 'border-primary/60' : 'border-muted-foreground/50')} />
            <article aria-labelledby={`scenario-${scenario.id}`}
              className={cn('space-y-3 rounded-lg border bg-card p-4', running && 'border-primary/50')}>
              <div className="space-y-1">
                <h2 id={`scenario-${scenario.id}`} className="text-base font-medium">{scenario.title}</h2>
                <p className="text-sm text-muted-foreground">{scenario.summary}</p>
              </div>
              <ul aria-label="Modules involved" className="flex flex-wrap gap-1.5">
                {scenario.modules.map((id) => (
                  <li key={id} className="rounded-full border px-2 py-0.5 text-xs text-muted-foreground">{moduleLabel(id)}</li>
                ))}
              </ul>
              <div className="flex flex-wrap items-center gap-3">
                <Button size="sm" onClick={() => onRun(scenario.id)} disabled={busy}
                  aria-label={`Run "${scenario.title}"`}>
                  {starting === scenario.id
                    ? <Loader2 className="motion-safe:animate-spin" aria-hidden="true" />
                    : <Play aria-hidden="true" />}
                  Run
                </Button>
                {current && (
                  <span className="text-xs text-muted-foreground">
                    {running ? 'Running now' : `Last run: ${RUN_STATE_LABEL[run.state] ?? run.state}`}
                  </span>
                )}
              </div>
            </article>
          </li>
        )
      })}
    </ol>
  )
}
