import { moduleStatusLabel } from '@/modules/multithreading/labels'

/**
 * Plain-English names and number formats for Experiment 1, so no ALL-CAPS text reaches the page
 * (R17). Module status is Experiment 2's, reused. An unknown enum value falls back to sentence
 * case ("SOME_VALUE" becomes "Some value"). Failure kinds have no text here: their descriptions
 * come from the overview's failureKinds.
 */
export { moduleStatusLabel }

/** The micro sign, built from its code point so this file stays ASCII. */
const MICRO = String.fromCharCode(0xb5)

function sentenceCase(value) {
  if (!value) return ''
  const words = String(value).toLowerCase().split('_').join(' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

const lookup = (table, value) => table[value] ?? sentenceCase(value)

const METHODS = { PING: 'ping', NODE_INFO: 'nodeInfo', ECHO: 'echo', COMPUTE_HASH: 'computeHash' }
const NODE_STATES = { NOT_STARTED: 'Not started', EXPORTED: 'Exported', CRASHED: 'Crashed' }
const OUTCOMES = { SUCCESS: 'Succeeded', REMOTE_FAILURE: 'Remote failure' }
const PATHS = { LOCAL: 'Local (direct call)', REMOTE: 'Remote (through RMI)' }
const STAGES = { LOOKUP: 'registry lookup', CALL: 'method call' }

/** The Java method name: "ping", "nodeInfo", "echo", "computeHash". */
export const methodLabel = (method) => lookup(METHODS, method)
export const nodeStateLabel = (state) => lookup(NODE_STATES, state)
export const outcomeLabel = (outcome) => lookup(OUTCOMES, outcome)
export const pathLabel = (path) => lookup(PATHS, path)
/** Lower case, for use inside a sentence: "registry lookup", "method call". */
export const stageLabel = (stage) => (STAGES[stage] ?? sentenceCase(stage).toLowerCase())
/** "Connection refused", "Timed out": the enum in sentence case; the meaning is the overview's description. */
export const failureKindLabel = (kind) => sentenceCase(kind)

/** "Up" or "Down": the overview's nodeStatus is UP or CRASHED. */
export const nodeStatusText = (nodeStatus) => (nodeStatus === 'CRASHED' ? 'Down' : 'Up')

/** The overview's own description of a failure kind, or null if it has none. */
export function failureKindDescription(overview, kind) {
  const found = Array.isArray(overview?.failureKinds) ? overview.failureKinds.find((entry) => entry.kind === kind) : null
  return found?.description ?? null
}

const isNumber = (value) => typeof value === 'number' && Number.isFinite(value)

/**
 * A measured duration in nanoseconds, at a precision the data supports and never rounded to
 * zero: below 1 microsecond in ns, below 1 ms in microseconds with one decimal, otherwise in ms
 * with three decimals. Negative values keep a plain hyphen-minus. null when not a number.
 */
export function formatNanos(nanos) {
  if (!isNumber(nanos)) return null
  const size = Math.abs(nanos)
  if (size < 1000) return `${nanos} ns`
  if (size < 1e6) return `${(nanos / 1e3).toFixed(1)} ${MICRO}s`
  return `${(nanos / 1e6).toFixed(3)} ms`
}

/** The exact value, for a tooltip: "7237200 ns". */
export const exactNanos = (nanos) => (isNumber(nanos) ? `${nanos} ns` : null)

/** A ratio with three decimals ("0.949"); a tiny non-zero ratio keeps three significant digits. */
export function formatRatio(ratio) {
  if (!isNumber(ratio)) return null
  if (ratio !== 0 && Math.abs(ratio) < 0.001) return ratio.toPrecision(3)
  return ratio.toFixed(3)
}

/** "1 byte", "96 bytes"; null when not a number. */
export function formatBytes(bytes) {
  if (!isNumber(bytes)) return null
  return `${bytes} ${bytes === 1 ? 'byte' : 'bytes'}`
}

/** The wall-clock time of a call, for display only: "19:28:41". */
export function clockTime(iso) {
  if (!iso) return null
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? null : date.toISOString().slice(11, 19)
}
