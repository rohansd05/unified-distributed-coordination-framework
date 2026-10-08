import { useEffect, useId, useRef, useState } from 'react'
import { ExperimentLayout } from '@/components/experiment/ExperimentLayout'
import { MetricCard } from '@/components/experiment/MetricCard'
import { Button } from '@/components/ui/button'
import { useStomp } from '@/hooks/useStomp'
import { useToast } from '@/hooks/use-toast'
import { cn } from '@/lib/utils'
import { BalancerFlow } from './BalancerFlow'
import { ComparisonTable } from './ComparisonTable'
import { flowModel, latestAction, num } from './flowModel'
import { capacityLabel, formatMillis, moduleStatusLabel, strategyLabel, strategyPhrase } from './labels'
import { loadBalancingApi } from './loadBalancingApi'
import { useLoadBalancing } from './useLoadBalancing'

const HOW_IT_WORKS = [
  'A load balancer receives every request and chooses which worker node serves it. Here it sends each request over TCP to a node\'s Experiment 2 executor, where it runs as real hashing work.',
  'The four strategies use different information: round robin uses none, weighted round robin uses a fixed weight set before the run, least connections counts the requests each worker has in progress, and least response time also uses how long each worker has recently taken to answer.',
  'The workers are unequal on purpose: a fast node has four threads and does the base amount of work per request, a slow node has one thread and four times the work. On identical workers every strategy would give the same result, and the comparison would prove nothing.',
  'If a worker cannot be reached, the balancer stops choosing it for the rest of the run (a circuit breaker) and sends the request to another worker, so a crash becomes a slower request instead of a lost one.',
  'Delivery is at least once: a request that timed out on one worker may still finish there after it has been sent to another.',
]

const WHAT_TO_NOTICE = [
  'Round robin gives every worker the same number of requests, so the slow node gets as many as a fast one. The comparison table says whether that made round robin finish last in your run.',
  'Least connections and least response time send fewer requests to the slow node without anyone configuring a weight, because they watch what is happening right now.',
  'Crash a worker during a run: its requests are rerouted and counted as reroutes, not failures, as long as another worker is up.',
]

const NOT_SIMULATED = 'Nothing on this page is simulated: every figure is measured from real requests.'

/** A number input with its label and the backend's message for that field, if any. */
function NumberField({ id, label, hint, value, onChange, error, min, max, disabled }) {
  const errorId = `${id}-error`
  return (
    <div className="space-y-1">
      <label htmlFor={id} className="text-sm font-medium">{label}</label>
      <input
        id={id}
        type="number"
        inputMode="numeric"
        min={min}
        max={max}
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
        aria-invalid={error ? 'true' : undefined}
        aria-describedby={error ? errorId : undefined}
        className={cn(
          'h-9 w-full rounded-md border bg-background px-3 text-sm tabular-nums focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50',
          error && 'border-destructive',
        )}
      />
      {error ? (
        <p id={errorId} className="text-xs text-destructive">{error}</p>
      ) : (
        hint && <p className="text-xs text-muted-foreground">{hint}</p>
      )}
    </div>
  )
}

const clamp = (value, low, high) => Math.min(high, Math.max(low, value))

/** The crash sentence for the latest run, or null when it had no crash plan or has not ended. */
function crashOutcome(run) {
  if (!run?.crash || run.state === 'RUNNING') {
    return null
  }
  const { nodeId, afterServed, crashed } = run.crash
  if (crashed) {
    return `Node ${nodeId} was crashed by this run after ${afterServed} requests were served. It stays down, for every experiment, until you recover it.`
  }
  return `Node ${nodeId} was not crashed by this run: it was already down, or the run ended before ${afterServed} requests were served.`
}

/** Which report the measurements show, in words. */
function measurementSource(model) {
  switch (model.mode) {
    case 'run':
      return `From the latest run, with ${strategyPhrase(model.strategy)}.`
    case 'phase':
      return `From the comparison's ${strategyPhrase(model.strategy)} phase.`
    case 'live':
      return 'A run is in progress; its measurements appear when it ends.'
    case 'failed':
      return 'The latest action failed, so it has no measurements.'
    default:
      return 'Nothing has run yet, so nothing is shown.'
  }
}

/**
 * Experiment 6, Load Balancing (docs/HANDOFF.md 7): run one strategy over the shared cluster,
 * compare all four on the same batch, and crash a worker mid-run to watch the reroute. Built on
 * the shared kit; live through /topic/modules/loadbalancing (see useLoadBalancing).
 *
 * Honest by construction: every number comes from the backend, an unavailable figure shows
 * "—" and never 0, and the finding sentences are gated on the measured comparison's flags.
 *
 * @param {object} props
 * @param {object} props.experiment the catalog entry (lab, id, title, concept)
 * @param {object} [props.api] see createLoadBalancingApi; for tests
 */
export function LoadBalancingPage({ experiment, api = loadBalancingApi }) {
  const { overview, loading, error, active, refresh } = useLoadBalancing({ api })
  const { status: connection } = useStomp()
  const { toast } = useToast()
  const [form, setForm] = useState(null)
  const [crash, setCrash] = useState({ enabled: false, nodeId: null, afterServed: null })
  const [phaseStrategy, setPhaseStrategy] = useState(null)
  const [fieldErrors, setFieldErrors] = useState({})
  const [actionError, setActionError] = useState(null)
  const [pending, setPending] = useState(null)
  const mountedRef = useRef(true)
  const formId = useId()

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  // The form starts from the backend's defaults, once they have arrived.
  const defaults = overview?.defaults
  useEffect(() => {
    if (!form && defaults) {
      setForm({
        strategy: 'ROUND_ROBIN',
        requestCount: String(defaults.requestCount),
        workUnits: String(defaults.workUnits),
        concurrency: String(defaults.concurrency),
      })
    }
  }, [form, defaults])

  // A new comparison starts the phase picker on its first phase again.
  const comparisonId = overview?.latestComparison?.comparisonId
  useEffect(() => {
    setPhaseStrategy(null)
  }, [comparisonId])

  const values = form ?? { strategy: 'ROUND_ROBIN', requestCount: '', workUnits: '', concurrency: '' }
  const limits = overview?.limits
  const workers = Array.isArray(overview?.workers) ? overview.workers : []
  const upNodes = workers.filter((worker) => worker.nodeStatus !== 'CRASHED')
  const crashedNodes = workers.filter((worker) => worker.nodeStatus === 'CRASHED')
  const strategies = Array.isArray(overview?.strategies) ? overview.strategies : []
  const model = flowModel(overview, phaseStrategy)
  const report = model.mode === 'run' || model.mode === 'phase' ? model.report : null
  const { run: latestRun } = latestAction(overview)

  const requestCount = Number(values.requestCount)
  const workUnits = Number(values.workUnits)
  const enoughRequests = Number.isInteger(requestCount) && requestCount >= 2
  const crashPossible = enoughRequests && upNodes.length > 0
  const crashBlockedReason = !enoughRequests
    ? 'A crash needs a run of at least 2 requests, so that some requests are served after it.'
    : upNodes.length === 0 ? 'No node is up to crash.' : null
  const defaultCrashNode = upNodes.find((worker) => worker.nodeId === 1)?.nodeId ?? upNodes[0]?.nodeId ?? null
  const crashNodeId = upNodes.some((worker) => worker.nodeId === crash.nodeId) ? crash.nodeId : defaultCrashNode
  const afterServed = crash.afterServed
    ?? (enoughRequests ? String(clamp(Math.floor(requestCount / 3), 1, requestCount - 1)) : '')
  const totalWork = Number.isFinite(requestCount * workUnits) && values.requestCount !== '' && values.workUnits !== ''
    ? requestCount * workUnits : null

  const busy = overview?.status === 'BUSY' || active
  const canAct = Boolean(overview) && Boolean(form) && !busy && pending === null
  const unreachable = connection === 'reconnecting' || connection === 'error' || Boolean(error)
  const hasData = Boolean(overview)

  const setField = (name) => (value) => {
    setForm((current) => ({ ...(current ?? values), [name]: value }))
    setFieldErrors((current) => ({ ...current, [name]: undefined, totalWork: undefined }))
  }

  async function act(kind, call, describe) {
    setPending(kind)
    setActionError(null)
    setFieldErrors({})
    try {
      const result = await call()
      if (!mountedRef.current) return
      toast({ title: describe(result) })
      refresh()
    } catch (err) {
      if (!mountedRef.current) return
      setFieldErrors(err?.errors ?? {})
      const message = err?.detail || err?.message || 'The request failed.'
      setActionError(message)
      toast({ variant: 'destructive', title: err?.title || 'Request failed', description: message })
    } finally {
      if (mountedRef.current) setPending(null)
    }
  }

  const counts = () => ({
    requestCount: Number(values.requestCount),
    workUnits: Number(values.workUnits),
    concurrency: Number(values.concurrency),
  })

  const startRun = (event) => {
    event.preventDefault()
    const body = { strategy: values.strategy, ...counts() }
    if (crash.enabled && crashPossible && crashNodeId !== null) {
      body.crash = { nodeId: crashNodeId, afterServed: Number(afterServed) }
    }
    act('run', () => api.startRun(body), (run) => `${strategyLabel(run?.strategy ?? body.strategy)} run started`)
  }

  const startComparison = () => act('compare', () => api.startComparison(counts()), () => 'Comparison of all four started')

  const recover = (nodeId) =>
    act(`recover-${nodeId}`, () => api.recoverNode(nodeId), () => `Node ${nodeId} recovered for every experiment`)

  const controls = (
    <div className="space-y-4">
      {unreachable && (
        <div role="alert" className="rounded-lg border border-warning/60 bg-warning/10 p-4 text-sm">
          <p className="font-semibold">The backend is not reachable.</p>
          <p className="mt-1 text-muted-foreground">
            Start it again with <code className="font-mono text-foreground">cd backend; .\mvnw.cmd spring-boot:run</code>.
            This page reconnects and refreshes by itself.
          </p>
          {error?.detail && <p className="mt-1 text-xs text-muted-foreground">Last error: {error.detail}</p>}
        </div>
      )}

      <form onSubmit={startRun} noValidate aria-label="Run the load balancer" className="grid gap-6 lg:grid-cols-2">
        <fieldset className="space-y-2" aria-describedby={fieldErrors.strategy ? `${formId}-strategy-error` : undefined}>
          <legend className="text-sm font-medium">Strategy</legend>
          {!hasData && <p className="text-sm text-muted-foreground">{loading ? 'Loading strategies…' : 'Strategies are not available.'}</p>}
          {strategies.map((entry) => (
            <label
              key={entry.strategy}
              className={cn(
                'flex cursor-pointer items-start gap-3 rounded-lg border bg-card p-3 text-sm focus-within:ring-2 focus-within:ring-ring',
                values.strategy === entry.strategy && 'border-primary bg-primary/10',
              )}
            >
              <input
                type="radio"
                name={`${formId}-strategy`}
                value={entry.strategy}
                checked={values.strategy === entry.strategy}
                onChange={() => setField('strategy')(entry.strategy)}
                className="mt-1 accent-primary"
              />
              <span className="space-y-0.5">
                <span className="block font-medium">{strategyLabel(entry.strategy)}</span>
                <span className="block text-xs text-muted-foreground">{entry.description}</span>
                <span className="block text-xs text-muted-foreground">What it knows: {entry.informationUsed}</span>
              </span>
            </label>
          ))}
          {fieldErrors.strategy && <p id={`${formId}-strategy-error`} className="text-xs text-destructive">{fieldErrors.strategy}</p>}
        </fieldset>

        <div className="space-y-4">
          <div className="grid gap-4 sm:grid-cols-3">
            <NumberField
              id={`${formId}-requests`}
              label="Requests"
              hint={limits ? `1 to ${limits.maxRequestCount}` : undefined}
              min={1}
              max={limits?.maxRequestCount}
              value={values.requestCount}
              onChange={setField('requestCount')}
              error={fieldErrors.requestCount}
            />
            <NumberField
              id={`${formId}-units`}
              label="Work units per request"
              hint={limits ? `1 to ${limits.maxWorkUnits}` : undefined}
              min={1}
              max={limits?.maxWorkUnits}
              value={values.workUnits}
              onChange={setField('workUnits')}
              error={fieldErrors.workUnits}
            />
            <NumberField
              id={`${formId}-clients`}
              label="Concurrent clients"
              hint={limits ? `1 to ${limits.maxConcurrency}` : undefined}
              min={1}
              max={limits?.maxConcurrency}
              value={values.concurrency}
              onChange={setField('concurrency')}
              error={fieldErrors.concurrency}
            />
          </div>
          {overview?.workUnitsNote && <p className="text-xs leading-relaxed text-muted-foreground">{overview.workUnitsNote}</p>}
          <p className="text-xs text-muted-foreground" data-testid="total-work">
            Total work for one run: {totalWork ?? '—'}
            {limits ? ` of ${limits.maxTotalWork} allowed` : ''}. A comparison uses five times this: the four strategies and a warm-up.
          </p>
          {fieldErrors.totalWork && <p role="alert" className="text-xs text-destructive">{fieldErrors.totalWork}</p>}

          <fieldset className="space-y-3 rounded-lg border bg-card p-3">
            <legend className="px-1 text-sm font-medium">Crash during the run</legend>
            <label className="flex items-start gap-3 text-sm">
              <input
                type="checkbox"
                checked={crash.enabled && crashPossible}
                disabled={!crashPossible}
                onChange={(event) => setCrash((current) => ({ ...current, enabled: event.target.checked }))}
                className="mt-1 accent-primary"
              />
              <span>Crash a worker during this run</span>
            </label>
            {crashBlockedReason && <p className="text-xs text-muted-foreground">{crashBlockedReason}</p>}
            {crash.enabled && crashPossible && (
              <div className="grid gap-4 sm:grid-cols-2">
                <div className="space-y-1">
                  <label htmlFor={`${formId}-crash-node`} className="text-sm font-medium">Node to crash</label>
                  <select
                    id={`${formId}-crash-node`}
                    value={crashNodeId ?? ''}
                    onChange={(event) => setCrash((current) => ({ ...current, nodeId: Number(event.target.value) }))}
                    className="h-9 w-full rounded-md border bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  >
                    {upNodes.map((worker) => (
                      <option key={worker.nodeId} value={worker.nodeId}>
                        Node {worker.nodeId} ({capacityLabel(worker.capacity)})
                      </option>
                    ))}
                  </select>
                </div>
                <NumberField
                  id={`${formId}-after`}
                  label="After this many requests are served"
                  hint={`1 to ${requestCount - 1}`}
                  min={1}
                  max={requestCount - 1}
                  value={afterServed}
                  onChange={(value) => {
                    setCrash((current) => ({ ...current, afterServed: value }))
                    setFieldErrors((current) => ({ ...current, 'crash.afterServed': undefined }))
                  }}
                  error={fieldErrors['crash.afterServed']}
                />
              </div>
            )}
            {overview?.crashNote && <p className="text-xs leading-relaxed text-muted-foreground">{overview.crashNote}</p>}
          </fieldset>

          <div className="flex flex-wrap items-center gap-3">
            <Button type="submit" disabled={!canAct}>{pending === 'run' ? 'Starting…' : 'Run'}</Button>
            <Button type="button" variant="outline" disabled={!canAct} onClick={startComparison}>
              {pending === 'compare' ? 'Starting…' : 'Compare all four'}
            </Button>
          </div>
          {overview?.warmUpNote && <p className="text-xs leading-relaxed text-muted-foreground">{overview.warmUpNote}</p>}

          <p className="text-sm" data-testid="module-status">
            Module status: <span className="font-medium">{overview ? moduleStatusLabel(overview.status) : '—'}</span>
            {overview?.actionInProgress && <span className="text-muted-foreground"> ({overview.actionInProgress})</span>}
          </p>
          {actionError && <p role="alert" className="text-sm text-destructive">{actionError}</p>}

          {crashedNodes.length > 0 && (
            <div className="space-y-2 rounded-lg border border-destructive/40 bg-destructive/10 p-3 text-sm">
              {crashedNodes.map((worker) => (
                <div key={worker.nodeId} className="flex flex-wrap items-center justify-between gap-2">
                  <span>Node {worker.nodeId} is down for every experiment.</span>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={pending !== null}
                    onClick={() => recover(worker.nodeId)}
                  >
                    {pending === `recover-${worker.nodeId}` ? 'Recovering…' : `Recover node ${worker.nodeId}`}
                  </Button>
                </div>
              ))}
              <p className="text-xs text-muted-foreground">Recovering a node brings it back for every experiment, not only this one.</p>
            </div>
          )}
        </div>
      </form>
    </div>
  )

  const visualisation = (
    <div className="space-y-4">
      {!hasData && loading && <div className="h-64 animate-pulse rounded-xl bg-card/60 motion-reduce:animate-none" aria-busy="true" />}
      {!hasData && !loading && error && (
        <div role="alert" className="rounded-lg border border-destructive/40 bg-card p-5 text-sm">
          <p className="font-semibold text-destructive">Could not load the load balancing data.</p>
          <p className="mt-1 text-muted-foreground">{error.detail || error.message}</p>
          <Button variant="outline" size="sm" className="mt-3" onClick={refresh}>Try again</Button>
        </div>
      )}
      {hasData && <BalancerFlow model={model} onPhaseChange={setPhaseStrategy} />}
    </div>
  )

  const outcome = model.kind === 'run' ? crashOutcome(latestRun) : null
  const measurements = (
    <div className="space-y-4">
      <p className="text-xs text-muted-foreground">{measurementSource(model)}</p>
      <div className="grid grid-cols-2 divide-x divide-y rounded-lg border bg-card sm:grid-cols-4 sm:divide-y-0 lg:grid-cols-7">
        <MetricCard label="Finish time" value={formatMillis(num(report?.makespanMillis))} unit="ms" hint="First request to last answer" />
        <MetricCard label="Average latency" value={formatMillis(num(report?.averageLatencyMillis))} unit="ms" />
        <MetricCard label="p95 latency" value={formatMillis(num(report?.p95LatencyMillis))} unit="ms" />
        <MetricCard label="Slowest request" value={formatMillis(num(report?.maxLatencyMillis))} unit="ms" />
        <MetricCard label="Reroutes" value={num(report?.reroutes)} hint="Requests sent to a second worker" />
        <MetricCard label="Failed requests" value={num(report?.failures)} hint="Served by no worker" />
        <MetricCard label="Request spread" value={num(report?.loadSpread)} hint="Busiest minus idlest worker" />
      </div>
      {outcome && <p className="text-sm" data-testid="crash-outcome">{outcome}</p>}

      <div className="space-y-2">
        <h3 className="text-sm font-semibold">Compare all four</h3>
        <ComparisonTable comparison={overview?.latestComparison ?? null} note={overview?.warmUpNote} />
      </div>

      <div className="space-y-1 text-xs leading-relaxed text-muted-foreground">
        {overview?.deliveryNote && <p>{overview.deliveryNote}</p>}
        {overview?.capacityNote && <p>{overview.capacityNote}</p>}
        <p>{NOT_SIMULATED}</p>
      </div>
    </div>
  )

  return (
    <ExperimentLayout
      experiment={experiment}
      howItWorks={HOW_IT_WORKS.map((sentence) => <p key={sentence}>{sentence}</p>)}
      controls={controls}
      visualisation={visualisation}
      measurements={measurements}
      whatToNotice={WHAT_TO_NOTICE}
    />
  )
}
