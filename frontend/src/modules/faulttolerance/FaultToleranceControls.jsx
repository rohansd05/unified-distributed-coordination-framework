import { useId, useState } from 'react'
import { Link } from 'react-router-dom'
import { AlertTriangle } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { SimulatedBadge } from '@/components/experiment/SimulatedBadge'
import { cn } from '@/lib/utils'
import { FIELD_LABELS } from './faultToleranceApi'
import { words } from './faultToleranceModel'

const MODES = ['SYNCHRONOUS', 'ASYNCHRONOUS']
const STOP_TEXT = { REQUESTED: 'stopped on request', LIMIT_REACHED: 'the session budget is used', ERROR: 'stopped by an error' }

function ResetLink() {
  return (
    <Link to="/cluster" className="font-medium text-primary underline underline-offset-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
      Reset the cluster on the Cluster page
    </Link>
  )
}

/**
 * Experiment 8's problems as the page tells them (E8d Q4: module-specific; Experiments 1 and 9
 * keep their own private copies). Busy, a known conflict and a timeout are calm (warning
 * style); anything else is an error. Field errors are listed by field name.
 */
function ProblemAlert({ problem, alertRef }) {
  if (!problem) return null
  const calm = problem.kind === 'busy' || problem.kind === 'conflict' || problem.kind === 'timeout'
  return (
    <div ref={alertRef} tabIndex={-1} role="alert" data-testid="action-error" data-kind={problem.kind}
      data-reason={problem.reason ?? undefined}
      className={cn('rounded-md border p-3 text-sm focus:outline-none',
        calm ? 'border-warning/60 bg-warning/10' : 'border-destructive/40 bg-destructive/10')}>
      <p className={cn('font-medium', !calm && 'text-destructive')}>{problem.message}</p>
      {problem.kind === 'busy' && problem.actionInProgress && (
        <p className="mt-1 text-muted-foreground">Running now: {problem.actionInProgress}</p>
      )}
      {problem.fieldErrors && (
        <ul className="mt-1 list-disc pl-5 text-muted-foreground">
          {Object.entries(problem.fieldErrors).map(([field, text]) => (
            <li key={field}>{FIELD_LABELS[field] ?? field}: {text}</li>
          ))}
        </ul>
      )}
      {problem.resetLink && <p className="mt-1"><ResetLink /></p>}
    </div>
  )
}

/** A button with its "why not" hint, linked by aria-describedby. */
function Action({ control, onClick, children, variant = 'outline', testId }) {
  const hintId = useId()
  return (
    <div className="space-y-1">
      <Button type="button" variant={variant} size="sm" disabled={!control.enabled} onClick={onClick} data-testid={testId}
        aria-describedby={control.hint ? hintId : undefined}>
        {children}
      </Button>
      {control.hint && <p id={hintId} className="max-w-[18rem] text-xs text-muted-foreground">{control.hint}</p>}
    </div>
  )
}

function Group({ title, children, className }) {
  return (
    <div className={cn('space-y-3 rounded-lg border bg-card p-4', className)}>
      <h3 className="text-sm font-semibold">{title}</h3>
      {children}
    </div>
  )
}

/**
 * Every action of Experiment 8, each enabled as controlsModel says (E8c's conflict matrix and the
 * E8d rulings): start, mode, rate, start and stop the update stream, crash the primary, recover
 * the old primary. Shows the stream counts and the session budget.
 *
 * @param {object} props
 * @param {object} props.status
 * @param {object} props.settings
 * @param {ReturnType<import('./faultToleranceModel').controlsModel>} props.controls
 * @param {string} props.state from uiState
 * @param {string} props.situation from situationText
 * @param {string|null} props.pending text of the request in flight
 * @param {boolean} props.awaitingIdle after a timeout, until the backend is no longer busy (C2)
 * @param {object|null} props.problem from describeFaultProblem
 * @param {object} [props.alertRef]
 */
export function FaultToleranceControls({ status, settings, controls, state, situation, pending, awaitingIdle, problem, alertRef,
  onStart, onStartStream, onStopStream, onMode, onCrash, onRecover }) {
  const stream = status.stream ?? {}
  const running = stream.state === 'RUNNING'
  const [rate, setRate] = useState(String(settings?.defaultRatePerSecond ?? ''))
  const rateId = useId()
  const rateHintId = useId()
  const budgetUsed = stream.remainingUpdates === 0
  const left = typeof stream.maxUpdates === 'number' && stream.maxUpdates > 0 ? stream.remainingUpdates / stream.maxUpdates : null

  return (
    <div data-testid="ft-controls" className="space-y-4">
      <div data-testid="situation" data-state={state}
        className={cn('rounded-md border p-3 text-sm',
          state === 'failover-waiting' ? 'border-warning/60 bg-warning/10' : 'border-border bg-background/60')}>
        {state === 'failover-waiting' && <AlertTriangle aria-hidden="true" className="mr-2 inline size-4 text-warning" />}
        {situation}
        {state === 'failover-waiting' && budgetUsed && <> <ResetLink /></>}
      </div>
      {pending && <p role="status" className="text-sm text-muted-foreground">{pending}</p>}
      {awaitingIdle && (
        <p role="status" data-testid="awaiting-idle" className="text-sm text-muted-foreground">
          Buttons stay disabled until the backend has finished the request.
        </p>
      )}
      <ProblemAlert problem={problem} alertRef={alertRef} />

      <div className="grid gap-4 lg:grid-cols-[minmax(0,14rem)_minmax(0,1fr)_minmax(0,16rem)]">
        <Group title="Experiment">
          <Action control={controls.start} onClick={onStart} testId="start">Start experiment 8</Action>
          <p className="text-xs text-muted-foreground">Starting the update stream also starts it.</p>
        </Group>

        <Group title="Update stream">
          <fieldset disabled={!controls.mode.enabled} className="space-y-1">
            <legend className="text-xs font-medium text-muted-foreground">Consistency mode</legend>
            <div className="inline-flex rounded-md border p-0.5">
              {MODES.map((mode) => (
                <label key={mode} className={cn('flex cursor-pointer items-center rounded px-3 py-1.5 text-sm has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ring',
                  stream.mode === mode ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground')}>
                  <input type="radio" name="ft-mode" value={mode} className="sr-only" checked={stream.mode === mode}
                    onChange={() => onMode(mode)} />
                  {words(mode)}
                </label>
              ))}
            </div>
            <p className="text-xs text-muted-foreground">A change applies from the next update, running or not.</p>
            {settings?.asyncDelaySimulated && (
              <p data-testid="async-delay" className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                Asynchronous updates wait {settings.asyncDelayMillis} ms before the push to the backups
                <SimulatedBadge reason={settings.asyncDelayReason ?? undefined} />
              </p>
            )}
          </fieldset>

          <div className="flex flex-wrap items-end gap-3">
            <div className="space-y-1">
              <label htmlFor={rateId} className="block text-xs font-medium text-muted-foreground">Rate (updates per second)</label>
              <input id={rateId} type="number" min={1} max={settings?.maxRatePerSecond ?? undefined} step={1}
                value={running ? (stream.ratePerSecond ?? '') : rate} disabled={!controls.rate.enabled}
                onChange={(event) => setRate(event.target.value)} aria-describedby={rateHintId}
                className="h-8 w-24 rounded-md border bg-background px-2 text-sm tabular-nums focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50" />
            </div>
            <Action control={controls.startStream} variant="default" testId="stream-start"
              onClick={() => onStartStream(rate === '' ? null : Number(rate))}>Start stream</Action>
            <Action control={controls.stopStream} testId="stream-stop" onClick={onStopStream}>Stop stream</Action>
          </div>
          <p id={rateHintId} className="text-xs text-muted-foreground">
            From 1 to {settings?.maxRatePerSecond ?? 'the configured maximum'} per second.{controls.rate.hint ? ` ${controls.rate.hint}` : ''}
          </p>
          {budgetUsed && <p className="text-xs"><ResetLink /></p>}

          <dl data-testid="stream-summary" className="grid grid-cols-2 gap-x-4 gap-y-1 border-t pt-3 text-xs sm:grid-cols-4">
            <div className="col-span-2 sm:col-span-4">
              <dt className="sr-only">State</dt>
              <dd>
                {running
                  ? `Running: ${words(stream.mode)?.toLowerCase()} updates, ${stream.ratePerSecond} per second.`
                  : `Stopped${stream.stopReason ? `: ${STOP_TEXT[stream.stopReason] ?? words(stream.stopReason)?.toLowerCase()}` : ''}.`}
              </dd>
            </div>
            <div><dt className="text-muted-foreground">Sent</dt><dd className="tabular-nums">{stream.sent}</dd></div>
            <div><dt className="text-muted-foreground">Acknowledged</dt><dd className="tabular-nums">{stream.acknowledged}</dd></div>
            <div>
              <dt className="text-muted-foreground">Gave up</dt>
              <dd className={cn('tabular-nums', stream.gaveUp > 0 && 'font-medium text-warning')}>
                {stream.gaveUp}{stream.gaveUp > 0 && ' (outlasted the retry window)'}
              </dd>
            </div>
            <div>
              <dt className="text-muted-foreground">In flight</dt>
              <dd>{stream.inFlightSequence ? <span className="font-mono">update {stream.inFlightSequence}</span> : 'None'}</dd>
            </div>
            <div className="col-span-2 space-y-1 sm:col-span-4">
              <dt className="sr-only">Session budget</dt>
              <dd data-testid="budget">{stream.remainingUpdates} of {stream.maxUpdates} updates left this session</dd>
              {left !== null && (
                <div aria-hidden="true" className="h-1.5 overflow-hidden rounded bg-muted/40">
                  <div className={cn('h-full', budgetUsed ? 'bg-warning' : 'bg-primary')} style={{ width: `${Math.round(left * 100)}%` }} />
                </div>
              )}
            </div>
          </dl>
        </Group>

        <Group title="Failure">
          <Action control={controls.crash} variant="destructive" testId="crash" onClick={onCrash}>Crash primary</Action>
          <Action control={controls.recover} testId="recover" onClick={onRecover}>Recover old primary</Action>
        </Group>
      </div>
    </div>
  )
}
