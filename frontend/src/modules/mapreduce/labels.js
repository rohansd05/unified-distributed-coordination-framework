import { formatMillis, moduleStatusLabel } from '@/modules/multithreading/labels'

/**
 * Plain-English names for Experiment 7's enum values, so no ALL-CAPS text reaches the page
 * (R17). Module status and millisecond formatting are Experiment 2's, reused. An unknown value
 * falls back to sentence case ("SOME_VALUE" becomes "Some value").
 */
export { formatMillis, moduleStatusLabel }

const RUN_STATES = { RUNNING: 'Running', COMPLETED: 'Completed', FAILED: 'Failed' }
const TASK_TYPES = { MAP: 'Map', REDUCE: 'Reduce' }
const INPUT_TYPES = { SAMPLE: 'Bundled sample text', UPLOAD: 'Uploaded file', EVENT_LOG: 'Live cluster event log' }

function sentenceCase(value) {
  if (!value) {
    return ''
  }
  const words = String(value).toLowerCase().split(/[_-]/).join(' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

const lookup = (table, value) => table[value] ?? sentenceCase(value)

export const runStateLabel = (state) => lookup(RUN_STATES, state)
export const taskTypeLabel = (type) => lookup(TASK_TYPES, type)
/** Fallback only: the overview's inputTypes[].title is shown when it is available. */
export const inputTypeLabel = (type) => lookup(INPUT_TYPES, type)
/** Fallback only: a run's jobTitle is shown when it is available ("word-count" becomes "Word count"). */
export const jobFallbackLabel = (jobId) => sentenceCase(jobId)

/** A task type for the middle of a sentence: "map", "reduce". */
export const taskTypePhrase = (type) => taskTypeLabel(type).toLowerCase()

/** "1 task", "2 tasks". */
export const plural = (count, noun) => `${count} ${count === 1 ? noun : `${noun}s`}`
