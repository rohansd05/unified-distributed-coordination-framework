/**
 * Plain-English names for Experiment 2's enum values, so no ALL-CAPS text reaches the page
 * (R17). An unknown value falls back to sentence case ("SOME_VALUE" becomes "Some value").
 */

const WORKLOADS = { CPU_HASH: 'Hashing (CPU)', IO_SIMULATED: 'Waiting (IO)', MIXED: 'Mixed' }
const REQUEST_STATUSES = {
  QUEUED: 'Queued',
  PROCESSING: 'Running',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
  REJECTED: 'Rejected',
}
const CAPACITIES = { FAST: 'Fast', MEDIUM: 'Medium', SLOW: 'Slow' }
const MODULE_STATUSES = { IDLE: 'Idle', RUNNING: 'Running', BUSY: 'Busy', ERROR: 'Error' }

function sentenceCase(value) {
  if (!value) {
    return ''
  }
  const words = String(value).toLowerCase().split('_').join(' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

function lookup(table, value) {
  return table[value] ?? sentenceCase(value)
}

export const workloadLabel = (type) => lookup(WORKLOADS, type)
export const requestStatusLabel = (status) => lookup(REQUEST_STATUSES, status)
export const capacityLabel = (capacity) => lookup(CAPACITIES, capacity)
export const moduleStatusLabel = (status) => lookup(MODULE_STATUSES, status)

/** Milliseconds for display: one decimal below 10 ms, whole numbers above; null when not a finite number. */
export function formatMillis(value) {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return null
  }
  return value < 10 ? value.toFixed(1) : String(Math.round(value))
}
