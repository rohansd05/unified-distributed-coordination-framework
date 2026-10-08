import { isNodeCrashed } from '@/lib/clusterStatus'
import { formatMillis, moduleStatusLabel } from '@/modules/multithreading/labels'

/**
 * Plain-English names for Experiment 5's enum values, so no ALL-CAPS text reaches the page
 * (R17). Module status and millisecond formatting are Experiment 2's, reused. An unknown value
 * falls back to sentence case ("SOME_VALUE" becomes "Some value").
 */
export { formatMillis, moduleStatusLabel }

/** Every replica state the backend sends (ReplicaState), in the order the legend shows them. */
export const CELL_STATES = ['CURRENT', 'STALE', 'MISSING', 'AHEAD', 'CONFLICT', 'UNREACHABLE', 'ABSENT']

const CELL_STATES_TEXT = {
  CURRENT: 'Current',
  STALE: 'Stale',
  MISSING: 'Missing',
  AHEAD: 'Ahead',
  CONFLICT: 'Conflict',
  UNREACHABLE: 'Unreachable',
  ABSENT: 'Not held',
}
const CELL_MEANINGS = {
  CURRENT: 'holds the same version as the primary',
  STALE: 'holds an older version than the primary',
  MISSING: 'holds nothing, although the primary holds this key',
  AHEAD: 'holds a newer version than the primary',
  CONFLICT: 'holds the same version as the primary with a different value',
  UNREACHABLE: 'could not be read: the node is crashed or its service has not started',
  ABSENT: 'holds nothing, and neither does the primary',
}
const MODELS = { SYNCHRONOUS: 'Synchronous', ASYNCHRONOUS: 'Asynchronous' }
const PUSH_STATUSES = { ACKED: 'Acknowledged', FAILED: 'Failed', NOT_SENT: 'Not sent', ABANDONED: 'Abandoned' }
const APPLY_RESULTS = {
  APPLIED: 'Applied',
  DUPLICATE: 'Already held',
  STALE: 'Rejected as stale',
  STALE_EPOCH: 'Refused: older epoch',
}
const ROLES = { PRIMARY: 'Primary', BACKUP: 'Backup' }

function sentenceCase(value) {
  if (!value) {
    return ''
  }
  const words = String(value).toLowerCase().split('_').join(' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

const lookup = (table, value) => table[value] ?? sentenceCase(value)

/** "Stale"; a null state (no readable primary to compare with) is "Not compared". */
export const cellStateLabel = (state) => (state ? lookup(CELL_STATES_TEXT, state) : 'Not compared')

/** What a replica in this state is, as a predicate: "holds an older version than the primary". */
export const cellStateMeaning = (state) =>
  state ? (CELL_MEANINGS[state] ?? sentenceCase(state).toLowerCase()) : 'is not compared: there is no readable primary'

export const modelLabel = (model) => lookup(MODELS, model)
export const pushStatusLabel = (status) => lookup(PUSH_STATUSES, status)
export const applyResultLabel = (result) => lookup(APPLY_RESULTS, result)
export const roleLabel = (role) => (role ? lookup(ROLES, role) : 'No role')

/** "Up" or "Crashed", through the shared status helper (the DTO field is nodeStatus). */
export const nodeStatusText = (nodeStatus) => (isNodeCrashed({ status: nodeStatus }) ? 'Crashed' : 'Up')

/** A stored value for display: an empty string is shown as "(empty)". */
export const valueText = (value) => (value === '' ? '(empty)' : String(value))

/** "node 3", "nodes 2 and 3", "nodes 2, 3 and 4". */
export function nodesText(ids) {
  const list = Array.isArray(ids) ? ids : []
  if (list.length === 0) {
    return 'no node'
  }
  if (list.length === 1) {
    return `node ${list[0]}`
  }
  return `nodes ${list.slice(0, -1).join(', ')} and ${list[list.length - 1]}`
}

/** "1 item", "2 items". */
export const itemsText = (count) => `${count} ${count === 1 ? 'item' : 'items'}`

/** A ProblemDetail as the user reads it: "Node down: Node 4 is crashed". */
export function problemText(error) {
  if (!error) return ''
  const detail = error.detail || error.message || 'The request failed.'
  return error.title && error.title !== detail ? `${error.title}: ${detail}` : detail
}
