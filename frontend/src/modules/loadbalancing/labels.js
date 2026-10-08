import { capacityLabel, formatMillis, moduleStatusLabel } from '@/modules/multithreading/labels'

/**
 * Plain-English names for Experiment 6's enum values, so no ALL-CAPS text reaches the page
 * (R17). Capacity, module status and millisecond formatting are Experiment 2's, reused. An
 * unknown value falls back to sentence case ("SOME_VALUE" becomes "Some value").
 */
export { capacityLabel, formatMillis, moduleStatusLabel }

/** The strategies in the order the backend runs a comparison. */
export const STRATEGY_ORDER = ['ROUND_ROBIN', 'WEIGHTED_ROUND_ROBIN', 'LEAST_CONNECTIONS', 'LEAST_RESPONSE_TIME']

const STRATEGIES = {
  ROUND_ROBIN: 'Round robin',
  WEIGHTED_ROUND_ROBIN: 'Weighted round robin',
  LEAST_CONNECTIONS: 'Least connections',
  LEAST_RESPONSE_TIME: 'Least response time',
}
const ACTION_STATES = { RUNNING: 'Running', FINISHED: 'Finished', FAILED: 'Failed' }
const NODE_STATUSES = { UP: 'Up', CRASHED: 'Crashed' }

function sentenceCase(value) {
  if (!value) {
    return ''
  }
  const words = String(value).toLowerCase().split('_').join(' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

const lookup = (table, value) => table[value] ?? sentenceCase(value)

export const strategyLabel = (strategy) => lookup(STRATEGIES, strategy)
export const actionStateLabel = (state) => lookup(ACTION_STATES, state)
export const nodeStatusLabel = (status) => lookup(NODE_STATUSES, status)

/** The strategy for the middle of a sentence: "round robin". */
export const strategyPhrase = (strategy) => strategyLabel(strategy).toLowerCase()

/** "1 thread", "4 threads". */
export const threadsText = (threads) => `${threads} ${threads === 1 ? 'thread' : 'threads'}`
