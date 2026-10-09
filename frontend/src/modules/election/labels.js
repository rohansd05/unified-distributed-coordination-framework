import { isAvailable } from '@/components/experiment/isAvailable'

const ALGORITHMS = { BULLY: 'Bully', RING: 'Ring' }
const TRIGGERS = { MANUAL: 'Started by hand', LEADER_FAILURE: 'After the leader failed', RECOVERY: 'After a recovery' }
const OUTCOMES = { IN_PROGRESS: 'In progress', ELECTED: 'Elected', TIMED_OUT: 'Timed out' }
const STATUSES = { IDLE: 'Idle', RUNNING: 'Running', BUSY: 'Election in progress', ERROR: 'Error' }
const MESSAGES = {
  ELECTION: 'Election',
  OK: 'Answer',
  COORDINATOR: 'Coordinator',
  RING_ELECTION: 'Ring token',
  RING_COORDINATOR: 'Ring result',
}

/** Sentence case for a value the tables above do not know, so no all-capitals word reaches the page. */
function sentenceCase(value) {
  const words = String(value).toLowerCase().split('_')
  words[0] = words[0].charAt(0).toUpperCase() + words[0].slice(1)
  return words.join(' ')
}

const lookup = (table, value) => (value ? table[value] ?? sentenceCase(value) : '')

export const algorithmLabel = (value) => lookup(ALGORITHMS, value)
export const triggerLabel = (value) => lookup(TRIGGERS, value)
export const outcomeLabel = (value) => lookup(OUTCOMES, value)
export const moduleStatusLabel = (value) => lookup(STATUSES, value)
export const messageLabel = (value) => lookup(MESSAGES, value)

/** The note every LEADER_FAILURE duration carries: the clock starts at detection. */
export const DETECTION_NOTE = 'Measured from detection, not from the crash'

/** A measured duration in ms for display, or null when it was not measured (MetricCard shows "—"). */
export function formatMillis(value) {
  if (!isAvailable(value)) {
    return null
  }
  const number = Number(value)
  return number < 10 ? number.toFixed(1) : String(Math.round(number))
}

/** Whole seconds from a millisecond setting, for "times out after N s"; null when unknown. */
export function secondsFrom(millis) {
  return isAvailable(millis) ? Math.round(Number(millis) / 100) / 10 : null
}
