/**
 * Pure helpers for the Experiment 8 page: what state the experiment is in, which controls are
 * enabled (from E8c's failure and conflict matrix, plus the E8d rulings Q1, Q3 and C3), the
 * timeline of a run, and the measurements. Nothing here reads a clock or makes up a number:
 * every figure comes from the backend, and a null stays null.
 */

/** The served notes, by position (E8c "Notes", served verbatim; contract.test.js pins the count). */
export const NOTE = { asyncWindow: 1, syncSafe: 2, lossCounts: 3, observedCrash: 4, selector: 5, epochFence: 6, exp5Interference: 7 }

/** Milestone kinds in the order a run passes them (E8c "Response shapes"). */
export const MILESTONE_KINDS = ['CRASHED', 'DETECTED', 'ELECTED', 'PROMOTED', 'SERVICE_RESTORED',
  'OLD_PRIMARY_RECOVERED', 'DEMOTED', 'RESYNCHRONISED']

const RECOVERY_KINDS = new Set(['OLD_PRIMARY_RECOVERED', 'DEMOTED', 'RESYNCHRONISED'])

export const MILESTONE_LABELS = {
  CRASHED: 'Primary crashed',
  DETECTED: 'Failure detected',
  ELECTED: 'New primary chosen',
  PROMOTED: 'New primary promoted',
  SERVICE_RESTORED: 'Service restored',
  OLD_PRIMARY_RECOVERED: 'Old primary back',
  DEMOTED: 'Old primary demoted',
  RESYNCHRONISED: 'Old primary resynchronised',
}

/**
 * The event type in the module's event log that belongs to each milestone. Milestones and events
 * are linked by kind, never by time: offsets are on the backend's monotonic clock, event wall
 * times are display only (E8c "For E8d" item 2). The choice of the new primary has no event of
 * its own; it is reported in the promotion event.
 */
export const MILESTONE_EVENTS = {
  CRASHED: 'PRIMARY_CRASHED',
  DETECTED: 'FAILURE_DETECTED',
  ELECTED: 'PRIMARY_PROMOTED',
  PROMOTED: 'PRIMARY_PROMOTED',
  SERVICE_RESTORED: 'SERVICE_RESTORED',
  OLD_PRIMARY_RECOVERED: 'OLD_PRIMARY_RECOVERED',
  DEMOTED: 'OLD_PRIMARY_DEMOTED',
  RESYNCHRONISED: 'RESYNCHRONISED',
}

export const OUTCOME_LABELS = {
  IN_PROGRESS: 'In progress',
  RESTORED: 'Service restored',
  INTERRUPTED: 'Interrupted by another crash',
  PRIMARY_RETURNED: 'Old primary returned first',
}

/** Any UPPER_SNAKE value as sentence-case words (PRIMARY -> Primary), or null. */
export function words(value) {
  if (value === null || value === undefined) return null
  const text = String(value).toLowerCase().replaceAll('_', ' ')
  return text.charAt(0).toUpperCase() + text.slice(1)
}

/** A duration in ms with one decimal ("2238.1 ms"), or null. */
export function formatMillis(value) {
  return typeof value === 'number' && Number.isFinite(value) ? `${value.toFixed(1)} ms` : null
}

function milestone(run, kind) {
  return run?.milestones?.find((m) => m.kind === kind) ?? null
}

function nodeOf(status, nodeId) {
  return status?.nodes?.find((node) => node.nodeId === nodeId) ?? null
}

/** A failover is in progress: the latest run is open, or the detector or promotion phase says so. */
export function isFailoverInProgress(status, run) {
  return run?.outcome === 'IN_PROGRESS' || status?.phase === 'SUSPECTED' || status?.phase === 'PROMOTING'
}

/** The old primary is back but has not resynchronised yet (the recovery interval is still open). */
export function isRecovering(run) {
  return run?.outcome === 'RESTORED' && Boolean(milestone(run, 'OLD_PRIMARY_RECOVERED')) && !milestone(run, 'RESYNCHRONISED')
}

/** The latest run's old primary is down, so "Recover old primary" applies. */
export function isOldPrimaryDown(status, run) {
  if (!run) return false
  const node = nodeOf(status, run.oldPrimaryId)
  return Boolean(node) && node.nodeStatus !== 'UP'
}

/**
 * The experiment's state for the page (E8d requirement 11 plus C3), most specific first:
 * not-started, failover-waiting (C3: a failover cannot finish without updates), failover,
 * appointing (started, nobody serves), recovering, restored (old primary still down),
 * streaming, demoted (the old primary came back as a backup and is not primary again), no-stream.
 * Since Phase 9A.1 a recovered old primary that wins the election again takes over after its
 * resync (a planned handover), so "demoted" ends once it serves as primary.
 */
export function uiState(status, run) {
  if (!status) return 'loading'
  if (!status.started) return 'not-started'
  const streaming = status.stream?.state === 'RUNNING'
  if (isFailoverInProgress(status, run)) return streaming ? 'failover' : 'failover-waiting'
  if (status.currentPrimaryId === null || status.currentPrimaryId === undefined) return 'appointing'
  if (isRecovering(run)) return 'recovering'
  if (run?.outcome === 'RESTORED' && isOldPrimaryDown(status, run)) return 'restored'
  if (streaming) return 'streaming'
  if (milestone(run, 'DEMOTED') && status.currentPrimaryId !== run.oldPrimaryId) return 'demoted'
  return 'no-stream'
}

/** The served budget sentence for Q1 ("Budget used: N of N updates this session. ..."). */
export function budgetUsedText(stream) {
  return `Budget used: ${stream.maxUpdates} of ${stream.maxUpdates} updates this session. A cluster reset gives a fresh budget.`
}

export const WAITING_FOR_UPDATE = 'The new primary only starts serving when the next update arrives. Start the update stream to finish the failover.'
export const WAITING_BUDGET_USED = 'The budget is used. Reset the cluster on the Cluster page.'
export const APPOINTING_FIRST = 'No primary serves yet: the first primary is being appointed.'
export const WAITING_FOR_LEADER = 'No primary serves right now: Experiment 8 waits for the elected leader to take over.'

/** Nobody serves and no primary was ever appointed: the first appointment (epoch 2) is the only one issued, and no run exists. */
const noPrimaryText = (status, run) => (!run && !(status?.highestEpoch > 2) ? APPOINTING_FIRST : WAITING_FOR_LEADER)

/** One sentence describing the state, for the page and the polite announcement. */
export function situationText(state, status, run) {
  const primary = status?.currentPrimaryId
  const epoch = status?.primaryEpoch
  switch (state) {
    case 'loading': return 'Loading Experiment 8...'
    case 'not-started': return 'Experiment 8 has not started. Start it, or start the update stream, which starts it too.'
    case 'appointing': return noPrimaryText(status, run)
    case 'failover': return `Failover in progress: node ${run?.oldPrimaryId ?? '?'} crashed and a backup is taking over.`
    case 'failover-waiting': return status?.stream?.remainingUpdates === 0 ? `${WAITING_FOR_UPDATE} ${WAITING_BUDGET_USED}` : WAITING_FOR_UPDATE
    case 'recovering': return `Node ${run.oldPrimaryId} is back and is asking the others who the primary is.`
    case 'restored': return `Node ${run.newPrimaryId} serves at epoch ${run.newEpoch}. Node ${run.oldPrimaryId}, the old primary, is still down: recover it to watch it demote itself.`
    case 'streaming': return `Updates flow to node ${primary}, the primary at epoch ${epoch}.`
    case 'demoted': return `Node ${run.oldPrimaryId} came back and demoted itself to backup at epoch ${nodeOf(status, run.oldPrimaryId)?.epoch ?? run.newEpoch}.`
    default: return `Node ${primary} is the primary at epoch ${epoch}. Start the update stream, then crash the primary.`
  }
}

/**
 * Which controls are enabled, each with the hint shown when it is not. `locked` is true while a
 * request from this page is pending or, after a timeout, until the backend is no longer busy (C2).
 *
 * @returns {{start, mode, rate, startStream, stopStream, crash, recover: {enabled: boolean, hint: string|null}}}
 */
export function controlsModel(status, run, { locked = false } = {}) {
  const on = (enabled, hint = null) => ({ enabled: enabled && !locked, hint: enabled ? null : hint })
  if (!status) {
    const off = { enabled: false, hint: null }
    return { start: off, mode: off, rate: off, startStream: off, stopStream: off, crash: off, recover: off }
  }
  const stream = status.stream ?? {}
  const running = stream.state === 'RUNNING'
  const budgetUsed = stream.remainingUpdates === 0
  const failover = isFailoverInProgress(status, run)
  const serving = status.currentPrimaryId !== null && status.currentPrimaryId !== undefined

  let crashHint = null
  if (failover) crashHint = 'A failover is in progress: wait until the service is restored.'
  else if (!running) crashHint = 'Start the update stream first: a failover is only measured once the new primary accepts an update.'
  else if (!serving) crashHint = noPrimaryText(status, run)

  let recoverHint = null
  const oldNode = run ? nodeOf(status, run.oldPrimaryId) : null
  if (!run) recoverHint = 'No primary has failed since the last reset.'
  else if (isRecovering(run)) recoverHint = `Node ${run.oldPrimaryId} is rejoining.`
  else if (oldNode && oldNode.nodeStatus === 'UP') recoverHint = `Node ${run.oldPrimaryId}, the old primary, is already up.`

  return {
    start: on(!status.started, 'Experiment 8 is started.'),
    mode: on(true),
    rate: on(!running, 'The rate is fixed while the stream runs.'),
    startStream: on(!running && !budgetUsed, budgetUsed ? budgetUsedText(stream) : null),
    stopStream: on(running, 'No update stream is running.'),
    crash: on(status.started && running && serving && !failover, crashHint),
    recover: on(isOldPrimaryDown(status, run), recoverHint),
  }
}

/**
 * The derived expected detection window, from the served detector settings only (C4): between
 * the heartbeat timeout minus one interval and the timeout plus one interval after the crash,
 * as the glossary entry "expectedDetection" defines it. Null when either setting is missing.
 */
export function expectedDetectionWindow(settings) {
  const timeout = settings?.heartbeatTimeoutMillis
  const interval = settings?.heartbeatIntervalMillis
  if (typeof timeout !== 'number' || typeof interval !== 'number') return null
  return { from: timeout - interval, to: timeout + interval }
}

const TICK_STEPS = [50, 100, 200, 250, 500, 1000, 2000, 5000, 10000, 20000, 50000]
const MAX_TICKS = 8

/** Axis end and ticks for a domain of `max` ms: the smallest step that needs at most 8 ticks. */
export function axisTicks(max) {
  const span = Math.max(max, 1)
  const step = TICK_STEPS.find((s) => span / s <= MAX_TICKS) ?? Math.ceil(span / MAX_TICKS)
  const end = Math.ceil(span / step) * step
  const ticks = []
  for (let t = 0; t <= end; t += step) ticks.push(t)
  return { end, step, ticks }
}

/** An axis tick as text: ms below one-second steps, seconds otherwise. */
export function tickLabel(value, step) {
  return step >= 1000 ? `${value / 1000} s` : `${value} ms`
}

const INTERVALS = [
  { id: 'detection', from: 'CRASHED', to: 'DETECTED' },
  { id: 'failover', from: 'DETECTED', to: 'PROMOTED' },
  { id: 'serviceRestored', from: 'PROMOTED', to: 'SERVICE_RESTORED' },
  { id: 'recovery', from: 'OLD_PRIMARY_RECOVERED', to: 'RESYNCHRONISED' },
]

function unrecordedRow(run, kind) {
  if (run.outcome === 'INTERRUPTED') return { state: 'not-reached', note: 'Not reached: another crash interrupted this run.' }
  if (run.outcome === 'PRIMARY_RETURNED') return { state: 'not-reached', note: 'Not reached: the old primary came back before anyone was chosen.' }
  if (RECOVERY_KINDS.has(kind) && !milestone(run, 'OLD_PRIMARY_RECOVERED')) {
    return { state: 'waiting', note: 'Waiting for "Recover old primary".' }
  }
  return { state: 'pending', note: 'Pending: not recorded yet.' }
}

/**
 * The timeline of one run: one row per milestone kind, recorded ones at their offset, the others
 * pending (never at 0), the intervals between recorded milestones, and the axis. While the
 * detection is still pending in an open run, the axis reaches at least to the end of the derived
 * expected detection window, so the band is visible while the run grows.
 *
 * @returns {null|{rows, intervals, band, axis, crashObserved: boolean}}
 */
export function timelineModel(run, settings) {
  if (!run) return null
  const band = expectedDetectionWindow(settings)
  const rows = MILESTONE_KINDS.map((kind) => {
    const found = milestone(run, kind)
    const base = { kind, label: MILESTONE_LABELS[kind], eventType: MILESTONE_EVENTS[kind] }
    if (found) return { ...base, state: 'recorded', nodeId: found.nodeId, offset: found.offsetMillis, note: null }
    return { ...base, nodeId: null, offset: null, ...unrecordedRow(run, kind) }
  })
  const offsets = rows.filter((row) => row.state === 'recorded').map((row) => row.offset)
  let max = Math.max(0, ...offsets)
  if (band && run.outcome === 'IN_PROGRESS' && !milestone(run, 'DETECTED')) max = Math.max(max, band.to)
  const intervals = INTERVALS
    .filter(({ from, to }) => milestone(run, from) && milestone(run, to))
    .map(({ id, from, to }) => ({ id, endKind: to, from: milestone(run, from).offsetMillis, to: milestone(run, to).offsetMillis }))
  return { rows, intervals, band, axis: axisTicks(max), crashObserved: run.crashSource === 'OBSERVED' }
}

const MEASUREMENT_FIELDS = {
  detection: 'detectionMillis',
  failover: 'failoverMillis',
  serviceRestored: 'serviceRestoredMillis',
  outage: 'outageMillis',
  recovery: 'recoveryMillis',
}

/** Why a measurement of this run is null, in a few words. */
function missingReason(id, run) {
  if (!run) return 'No failover since the reset'
  if (run.outcome === 'PRIMARY_RETURNED') return 'The old primary came back before anyone was chosen'
  if (run.outcome === 'INTERRUPTED') return 'Another crash interrupted this run'
  if (id === 'recovery') {
    return milestone(run, 'OLD_PRIMARY_RECOVERED') ? 'The old primary is still rejoining' : 'The old primary has not been recovered yet'
  }
  if (id === 'dataLoss') return 'Checked when the service is restored'
  return 'Not recorded yet in this run'
}

/**
 * One row per glossary measurement: its served label, kind and definition, and the latest run's
 * value (text) or null with the reason. Data loss is "lost of acknowledged".
 */
export function measurement(id, run, glossary) {
  const entry = glossary?.find((g) => g.id === id) ?? { id, label: id, kind: 'measured', definition: '' }
  let text = null
  let exact = null
  if (id === 'dataLoss') {
    const loss = run?.dataLoss
    if (loss && typeof loss.lost === 'number' && typeof loss.acknowledged === 'number') {
      text = `${loss.lost} of ${loss.acknowledged}`
    }
  } else {
    const value = run?.measurements?.[MEASUREMENT_FIELDS[id]]
    text = formatMillis(value)
    exact = text ? `${value} ms` : null
  }
  return { ...entry, text, exact, reason: text ? null : missingReason(id, run) }
}
