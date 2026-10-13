import {
  clockTime,
  exactNanos,
  failureKindLabel,
  formatBytes,
  formatNanos,
  moduleStatusLabel,
  nodeStatusText,
  stageLabel,
} from '@/modules/rmi/labels'

/**
 * Plain-English names for Experiment 9, so no ALL-CAPS text reaches the page (R17). Number
 * formats, failure kinds, stages and module status are Experiment 1's, reused. An unknown enum
 * value falls back to sentence case ("SOME_VALUE" becomes "Some value").
 */
export { clockTime, exactNanos, failureKindLabel, formatBytes, formatNanos, moduleStatusLabel, nodeStatusText, stageLabel }

function sentenceCase(value) {
  if (!value) return ''
  const words = String(value).toLowerCase().split('_').join(' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

const lookup = (table, value) => table[value] ?? sentenceCase(value)

const OPERATIONS = { BROADCAST: 'Broadcast', SCATTER: 'Scatter', GATHER: 'Gather' }
const OUTCOMES = { COMPLETE: 'Complete', PARTIAL: 'Partial', FAILED: 'Failed', REFUSED: 'Refused' }
const ROOT_SOURCES = {
  REQUESTED: 'the node you chose',
  LEADER: 'the elected leader, read from the cluster',
  LOWEST_LIVE_ID: 'the lowest live node id; no leader is elected',
}
const DIRECTIONS = { TO_RANK: 'root to rank', FROM_RANK: 'rank to root' }
const TRANSFER_STATUSES = { LOCAL: 'Local (root)', DELIVERED: 'Delivered', FAILED: 'Failed', NOT_ATTEMPTED: 'Not attempted' }
const RMI_STATES = { NOT_STARTED: 'Not started', EXPORTED: 'Exported', CRASHED: 'Crashed' }

export const operationLabel = (operation) => lookup(OPERATIONS, operation)
export const outcomeLabel = (outcome) => lookup(OUTCOMES, outcome)
/** Lower case, for use inside a sentence: "the lowest live node id; no leader is elected". */
export const rootSourceLabel = (source) => (ROOT_SOURCES[source] ?? sentenceCase(source).toLowerCase())
export const directionLabel = (direction) => (DIRECTIONS[direction] ?? sentenceCase(direction).toLowerCase())
export const transferStatusLabel = (status) => lookup(TRANSFER_STATUSES, status)
export const rmiStateLabel = (state) => lookup(RMI_STATES, state)
/** "Count below participants": a refusal reason in sentence case; the meaning is the backend's message. */
export const refusalLabel = (reason) => sentenceCase(reason)

/** A SHA-256 checksum, shortened for a table cell; the full value goes in a tooltip. */
export function shortChecksum(hex) {
  if (typeof hex !== 'string') return null
  return hex.length > 12 ? `${hex.slice(0, 12)}...` : hex
}
