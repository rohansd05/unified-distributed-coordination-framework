import { api as defaultApi } from '@/services/api'
import { describeProblem } from '@/modules/rmi/rmiApi'

const BASE = '/api/modules/faulttolerance'

/**
 * Experiment 8's REST calls (docs/tracks/track-b-swanand.md, "E8c interfaces"), on the shared
 * axios client, so the base URL, the 10000 ms timeout and the ProblemDetail-to-ApiError mapping are
 * the same as everywhere else. No call gets its own timeout: crash-primary and recover usually
 * answer in under 10 ms, and their worst cases (55 s and 75 s, E8c "For E8d" item 6) are longer
 * than any wait worth showing, so a timeout there is followed by a status read instead (E8d Q2).
 *
 * @param {object} [base] an api from createApi(); its client is reused
 */
export function createFaultToleranceApi(base = defaultApi) {
  const { client } = base
  const data = (res) => res.data
  return {
    getOverview: () => client.get(BASE).then(data),
    getStatus: () => client.get(`${BASE}/status`).then(data),
    /** The latest run, or null when there is none since the reset: that 404 is the empty state. */
    getLatestRun: () => client.get(`${BASE}/runs/latest`).then(data).catch((err) => {
      if (err?.status === 404) return null
      throw err
    }),
    start: () => client.post(`${BASE}/start`).then(data),
    startStream: ({ mode, ratePerSecond }) => client.post(`${BASE}/stream/start`, { mode, ratePerSecond }).then(data),
    stopStream: () => client.post(`${BASE}/stream/stop`).then(data),
    setMode: (mode) => client.put(`${BASE}/mode`, { mode }).then(data),
    crashPrimary: () => client.post(`${BASE}/crash-primary`).then(data),
    recover: () => client.post(`${BASE}/recover`).then(data),
  }
}

export const faultToleranceApi = createFaultToleranceApi()

/** Request fields as the page names them. */
export const FIELD_LABELS = { mode: 'Mode', ratePerSecond: 'Rate' }

const REASON_MESSAGES = {
  NO_SERVING_PRIMARY: (detail) => `No primary to crash right now. ${detail}`,
  NOTHING_TO_RECOVER: (detail) => `Nothing to recover. ${detail}`,
  STREAM_RUNNING: () => 'The update stream is already running. Stop it first to change its rate.',
  STREAM_NOT_RUNNING: () => 'The update stream is not running, so there is nothing to stop.',
  UPDATE_LIMIT_REACHED: (detail) => detail,
}

/**
 * The shared describeProblem (Experiment 1) plus what Experiment 8 adds: the 409 `reason` (read
 * from the response body, which ApiError does not keep) with one message per reason, and the
 * timeout of crash-primary or recover, which never means the action failed.
 *
 * @param {Error|null} error an ApiError
 * @param {string} [action] 'crash', 'recover' or another action name
 * @returns {null|object} describeProblem's result plus `reason`; `resetLink` is true for UPDATE_LIMIT_REACHED
 */
export function describeFaultProblem(error, action) {
  const problem = describeProblem(error)
  if (!problem) return null
  const reason = error.originalError?.response?.data?.reason ?? null
  if (problem.kind === 'timeout' && (action === 'crash' || action === 'recover')) {
    const ms = error.originalError?.config?.timeout
    const after = typeof ms === 'number' ? ` after ${ms / 1000} s` : ''
    const what = action === 'crash' ? 'crash' : 'recovery'
    return { ...problem, reason, message: `The request timed out${after}. The ${what} may still have happened; reading the status again.` }
  }
  if (problem.kind === 'validation') {
    return { ...problem, reason, message: 'The backend refused the request:' }
  }
  if (problem.status === 409 && REASON_MESSAGES[reason]) {
    return { ...problem, kind: 'conflict', reason, resetLink: reason === 'UPDATE_LIMIT_REACHED', message: REASON_MESSAGES[reason](problem.detail) }
  }
  return { ...problem, reason }
}
