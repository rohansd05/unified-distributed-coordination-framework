import { strategyLabel } from './labels'

/** A finite number, or null: unavailable data is never turned into 0 or NaN. */
export function num(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function startedAt(action) {
  const time = Date.parse(action?.startedAt ?? '')
  return Number.isFinite(time) ? time : -Infinity
}

/**
 * The most recent action: the latest run or the latest comparison, whichever started last.
 *
 * @returns {{ kind: 'run'|'comparison'|null, run: object|null, comparison: object|null }}
 */
export function latestAction(overview) {
  const run = overview?.latestRun ?? null
  const comparison = overview?.latestComparison ?? null
  if (!run && !comparison) {
    return { kind: null, run, comparison }
  }
  if (run && (!comparison || startedAt(run) >= startedAt(comparison))) {
    return { kind: 'run', run, comparison }
  }
  return { kind: 'comparison', run, comparison }
}

function phasesOf(comparison) {
  return Array.isArray(comparison?.phases) ? comparison.phases : []
}

/** The finished comparison phase to show: the chosen strategy, or the first phase. */
export function selectedPhase(comparison, phaseStrategy) {
  const phases = phasesOf(comparison)
  return phases.find((phase) => phase.strategy === phaseStrategy) ?? phases[0] ?? null
}

function workerBase(worker) {
  const crashed = worker.nodeStatus === 'CRASHED'
  return {
    nodeId: worker.nodeId,
    capacity: worker.capacity,
    threads: num(worker.threads),
    workMultiplier: num(worker.workMultiplier),
    crashed,
    healthy: !crashed && worker.healthy !== false,
  }
}

function lanesFromWorkers(workers, withCounts) {
  return workers.map((worker) => ({
    ...workerBase(worker),
    requests: withCounts ? num(worker.completed) : null,
    inFlight: withCounts ? num(worker.inFlight) : null,
    failedAttempts: withCounts ? num(worker.failed) ?? 0 : 0,
    declinedAttempts: withCounts ? num(worker.declined) ?? 0 : 0,
    averageLatencyMillis: withCounts ? num(worker.averageLatencyMillis) : null,
  }))
}

function lanesFromReport(report, workers) {
  const byId = new Map(workers.map((worker) => [worker.nodeId, worker]))
  const nodes = Array.isArray(report?.nodes) ? report.nodes : []
  return nodes.map((node) => {
    const worker = byId.get(node.nodeId)
    const base = worker ? workerBase(worker) : { nodeId: node.nodeId, capacity: node.capacity, threads: null,
      workMultiplier: null, crashed: false, healthy: true }
    return {
      ...base,
      capacity: node.capacity ?? base.capacity,
      healthy: base.healthy && node.healthy !== false,
      requests: num(node.requests),
      inFlight: null,
      failedAttempts: num(node.failedAttempts) ?? 0,
      declinedAttempts: num(node.declinedAttempts) ?? 0,
      averageLatencyMillis: num(node.averageLatencyMillis),
    }
  })
}

/**
 * What the balancer flow shows, from the overview alone:
 * - mode 'empty': nothing has run; lanes without counts.
 * - mode 'live': a run or comparison is RUNNING; the workers' live counters.
 * - mode 'run': the latest run FINISHED; its report.
 * - mode 'phase': a comparison with finished phases; the chosen phase's report.
 * - mode 'failed': the latest action FAILED with nothing to report; the counters it reached.
 * Each lane's share is its requests over the lanes' total; null when nothing was counted.
 */
export function flowModel(overview, phaseStrategy) {
  const workers = Array.isArray(overview?.workers) ? overview.workers : []
  const { kind, run, comparison } = latestAction(overview)
  const action = kind === 'run' ? run : kind === 'comparison' ? comparison : null
  const phases = kind === 'comparison' ? phasesOf(comparison) : []

  let mode = 'empty'
  let lanes = lanesFromWorkers(workers, false)
  let report = null
  let strategy = null
  if (action?.state === 'RUNNING') {
    mode = 'live'
    lanes = lanesFromWorkers(workers, true)
    strategy = kind === 'run' ? run.strategy : null
  } else if (kind === 'run' && run.report) {
    mode = 'run'
    report = run.report
    strategy = run.strategy
    lanes = lanesFromReport(report, workers)
  } else if (kind === 'comparison' && phases.length > 0) {
    mode = 'phase'
    report = selectedPhase(comparison, phaseStrategy)
    strategy = report.strategy
    lanes = lanesFromReport(report, workers)
  } else if (action?.state === 'FAILED') {
    mode = 'failed'
    lanes = lanesFromWorkers(workers, true)
    strategy = kind === 'run' ? run.strategy : null
  }

  const counted = lanes.map((lane) => lane.requests).filter((value) => value !== null)
  const total = counted.length > 0 ? counted.reduce((sum, value) => sum + value, 0) : null
  const withShares = lanes.map((lane) => ({
    ...lane,
    share: total && lane.requests !== null ? lane.requests / total : null,
  }))
  const inFlight = lanes.map((lane) => lane.inFlight).filter((value) => value !== null)

  return {
    mode,
    kind,
    action,
    strategy,
    report,
    phases,
    lanes: withShares,
    answered: total,
    inFlightTotal: inFlight.length > 0 ? inFlight.reduce((sum, value) => sum + value, 0) : null,
    requestCount: num(action?.requestCount),
    error: action?.state === 'FAILED' ? action.error ?? 'The action failed.' : null,
  }
}

/** The one-sentence state of the flow, for the polite live region and the caption. */
export function flowSummary(model) {
  const what = model.kind === 'comparison' ? 'Comparison' : 'Run'
  switch (model.mode) {
    case 'live': {
      const of = model.requestCount !== null ? ` of ${model.requestCount}` : ''
      const name = model.strategy ? `${strategyLabel(model.strategy)} run` : 'Comparison'
      return `${name} in progress: ${model.answered ?? 0}${of} answered so far, ${model.inFlightTotal ?? 0} in flight.`
    }
    case 'run':
    case 'phase': {
      const failures = num(model.report?.failures)
      const failed = failures === null ? '' : `, ${failures} failed`
      return `${strategyLabel(model.strategy)}: ${model.answered ?? 0} requests served${failed}.`
    }
    case 'failed':
      return `${what} failed: ${model.error}`
    default:
      return 'Nothing has run yet. Run a strategy to see where the requests go.'
  }
}
