import { EXPERIMENTS } from '@/lib/experiments'

/**
 * Pure helpers for the Scenarios page: folding the engine's live events (module "scenario",
 * docs/tracks/track-b-swanand.md "9A.6 APIs") into a run, choosing between a polled run and
 * the one built from events, and the texts and links the page shows.
 */

export const SCENARIO_MODULE = 'scenario'
export const RUNNING = 'RUNNING'

const STEP_RANK = { PENDING: 0, RUNNING: 1, PASSED: 2, FAILED: 2, SKIPPED: 2 }

export const STEP_STATE_LABEL = {
  PENDING: 'Waiting',
  RUNNING: 'Running',
  PASSED: 'Passed',
  FAILED: 'Failed',
  SKIPPED: 'Skipped',
}

export const RUN_STATE_LABEL = { RUNNING: 'Running', PASSED: 'Passed', FAILED: 'Failed' }

export function isActive(run) {
  return run?.state === RUNNING
}

/** A run as the API shows it before any step has started: every step PENDING. */
export function skeletonRun({ runId, scenarioId, startedAt = null }, scenarios = []) {
  const scenario = scenarios.find((s) => s.id === scenarioId)
  return {
    runId,
    scenarioId,
    state: RUNNING,
    startedAt,
    finishedAt: null,
    verdict: null,
    endState: null,
    steps: (scenario?.steps ?? []).map((s) => ({
      index: s.index,
      narration: s.narration,
      state: 'PENDING',
      startedAt: null,
      durationMs: null,
      facts: {},
      message: null,
    })),
  }
}

/** The step's own message from an event message such as "Step 2 passed: Node 5 went down." */
export function stepMessage(message) {
  if (!message) return null
  return message.replace(/^Step \d+ (passed|failed|skipped): /, '')
}

function withStep(run, index, patch) {
  if (!run.steps.some((s) => s.index === index)) return run
  return { ...run, steps: run.steps.map((s) => (s.index === index ? { ...s, ...patch } : s)) }
}

/**
 * Folds one bus event into the run. Events of other modules or other runs leave it unchanged;
 * SCENARIO_STARTED of a new run starts a fresh skeleton. Never throws on an unknown type.
 */
export function applyEvent(run, event, scenarios = []) {
  if (!event || event.module !== SCENARIO_MODULE) return run
  const data = event.data ?? {}
  if (event.type === 'SCENARIO_STARTED') {
    if (run && run.runId === data.runId) return run
    if (run && run.runId > data.runId) return run
    return skeletonRun({ runId: data.runId, scenarioId: data.scenarioId, startedAt: event.wallTime ?? null }, scenarios)
  }
  if (!run || run.runId !== data.runId) {
    // A finish without a start (the reset before the first step failed) still names the run.
    if (event.type === 'SCENARIO_FINISHED' && (!run || data.runId > run.runId)) {
      return { ...skeletonRun({ runId: data.runId, scenarioId: data.scenarioId }, scenarios),
        state: data.outcome ?? 'FAILED', verdict: data.verdict ?? event.message ?? null,
        finishedAt: event.wallTime ?? null }
    }
    return run
  }
  const index = data.stepIndex
  switch (event.type) {
    case 'STEP_STARTED':
      return withStep(run, index, { state: 'RUNNING', startedAt: event.wallTime ?? null })
    case 'STEP_PASSED':
    case 'STEP_FAILED':
      return withStep(run, index, {
        state: event.type === 'STEP_PASSED' ? 'PASSED' : 'FAILED',
        durationMs: data.durationMs ?? null,
        facts: data.facts ?? {},
        message: stepMessage(event.message),
      })
    case 'STEP_SKIPPED':
      return withStep(run, index, { state: 'SKIPPED', message: stepMessage(event.message) })
    case 'SCENARIO_FINISHED':
      return { ...run, state: data.outcome ?? run.state, verdict: data.verdict ?? event.message ?? run.verdict,
        finishedAt: event.wallTime ?? run.finishedAt }
    default:
      return run
  }
}

/** How far a run has got: finished steps count double, a finished run most. */
export function progress(run) {
  if (!run) return -1
  const steps = run.steps.reduce((sum, s) => sum + (STEP_RANK[s.state] ?? 0), 0)
  return steps + (run.state === RUNNING ? 0 : 1000) + (run.endState ? 1 : 0)
}

/**
 * The run to show after a poll: a newer run wins; for the same run the polled one wins unless the
 * events have already taken this page further (a poll answered before the latest event).
 */
export function preferRun(current, polled) {
  if (!polled) return current
  if (!current || polled.runId > current.runId) return polled
  if (polled.runId < current.runId) return current
  return progress(polled) >= progress(current) ? polled : current
}

/** The step the audience is watching: the running one, else the last one that ended, else the first. */
export function currentStep(run) {
  if (!run?.steps?.length) return null
  const running = run.steps.find((s) => s.state === 'RUNNING')
  if (running) return running
  const ended = run.steps.filter((s) => s.state !== 'PENDING')
  return ended.at(-1) ?? run.steps[0]
}

/** "12 ms", "2.8 s", "1 min 04 s"; null for an unmeasured time. */
export function formatDuration(ms) {
  if (ms == null || Number.isNaN(ms)) return null
  if (ms < 1000) return `${Math.round(ms)} ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)} s`
  const s = Math.round(ms / 1000)
  return `${Math.floor(s / 60)} min ${String(s % 60).padStart(2, '0')} s`
}

/** The whole run's duration from its server timestamps, or null while it runs. */
export function runDuration(run) {
  if (!run?.startedAt || !run?.finishedAt) return null
  const ms = Date.parse(run.finishedAt) - Date.parse(run.startedAt)
  return Number.isFinite(ms) && ms >= 0 ? ms : null
}

/** "Exp 7: MapReduce" for a known module id; the raw id otherwise. */
export function moduleLabel(id) {
  const experiment = EXPERIMENTS.find((e) => e.id === id)
  return experiment ? `Exp ${experiment.lab}: ${experiment.title}` : id
}

/** "crashedNodeId" -> "Crashed node id". */
export function factLabel(key) {
  const words = String(key).replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

/** A fact's value as short text: lists joined, maps as "key value" pairs, booleans as yes/no. */
export function formatFact(value) {
  if (value == null) return '—'
  if (typeof value === 'boolean') return value ? 'yes' : 'no'
  if (Array.isArray(value)) return value.length ? value.map(formatFact).join(', ') : 'none'
  if (typeof value === 'object') {
    const entries = Object.entries(value)
    return entries.length ? entries.map(([k, v]) => `${k} ${formatFact(v)}`).join(', ') : 'none'
  }
  return String(value)
}

const TIMELINE_EXTRA_MODULES = ['scenario', 'cluster']

/**
 * The Timeline deep link for a scenario (docs/tracks/track-d-rohan.md, 9A.5): its modules plus the
 * scenario and cluster lanes, in bus order. Only module ids from the static catalog or the two
 * literals above go into the link, never a served string as is.
 */
export function timelineHref(scenario) {
  const known = (scenario?.modules ?? [])
    .map((id) => EXPERIMENTS.find((e) => e.id === id)?.id)
    .filter(Boolean)
  const modules = [...new Set([...TIMELINE_EXTRA_MODULES, ...known])]
  const params = new URLSearchParams({ modules: modules.join(','), order: 'arrival' })
  return `/timeline?${params.toString()}`
}
