import { isAvailable } from '@/components/experiment/isAvailable'

/**
 * Plain-English names for Experiment 3's enums and values, so no ALL-CAPS words reach the page (R17).
 * An unknown value falls back to sentence case ("SOME_VALUE" becomes "Some value").
 */

const NODE_STATUSES = {
  UP: 'Up',
  CRASHED: 'Crashed',
  DOWN: 'Crashed',
}

const MODULE_STATUSES = {
  IDLE: 'Idle',
  RUNNING: 'Running',
  BUSY: 'Busy',
  ERROR: 'Error',
}

const ACTION_STATUSES = {
  ACCEPTED: 'Accepted',
  RUNNING: 'Running',
  FINISHED: 'Finished',
  FAILED: 'Failed',
}

const DELIVERY_STATUSES = {
  SENT: 'Sent',
  UNKNOWN: 'Unknown',
}

const EVENT_TYPES = {
  SEND: 'Send',
  RECV: 'Receive',
  LOCAL: 'Local',
}

const VIOLATION_TYPES = {
  RECEIVE_NOT_AFTER_SEND: 'Receive not after send',
  LOCAL_CLOCK_NOT_MONOTONIC: 'Local clock not monotonic',
}

export function sentenceCase(value) {
  if (!value) return ''
  const words = String(value).toLowerCase().split('_').join(' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

const lookup = (table, value) => table[value] ?? sentenceCase(value)

export const nodeStatusText = (status) => lookup(NODE_STATUSES, status)
export const moduleStatusText = (status) => lookup(MODULE_STATUSES, status)
export const actionStatusText = (status) => lookup(ACTION_STATUSES, status)
export const deliveryStatusText = (status) => lookup(DELIVERY_STATUSES, status)
export const eventTypeLabel = (type) => lookup(EVENT_TYPES, type)
export const violationTypeLabel = (type) => lookup(VIOLATION_TYPES, type)

/** Formats offset in milliseconds with an explicit sign (+242 ms, -165 ms, 0 ms), or null if not available. */
export function formatOffset(offsetMillis) {
  if (!isAvailable(offsetMillis)) return null
  const num = Number(offsetMillis)
  const sign = num > 0 ? '+' : ''
  return `${sign}${num} ms`
}

/** Formats drift rate in ms/s with an explicit sign (+1.5 ms/s, -1.0 ms/s, 0.0 ms/s), or null if not available. */
export function formatDriftRate(rate) {
  if (!isAvailable(rate)) return null
  const num = Number(rate)
  const sign = num > 0 ? '+' : ''
  return `${sign}${num.toFixed(1)} ms/s`
}

/** Formats spread in milliseconds, or null if not available. */
export function formatSpread(spread) {
  if (!isAvailable(spread)) return null
  return `${Number(spread)} ms`
}

/** Formats latency/RTT in milliseconds, or null if not available. */
export function formatMillis(ms) {
  if (!isAvailable(ms)) return null
  const num = Number(ms)
  return `${num.toFixed(1)} ms`
}
