import { api as defaultApi } from '@/services/api'

const BASE = '/api/modules/rmi'

/**
 * Per-request timeout of POST /calls. A remote call is at most three exchanges (the registry
 * lookup, RMI's DGC dirty call inside it, the method call), each bounded by the 1 s connect
 * timeout plus the 3 s read timeout: about 12 s, above the shared client's 10 s (E1c).
 */
export const CALL_TIMEOUT_MS = 20000

/**
 * Per-request timeout of POST /comparisons: the remote half as above, plus the local half,
 * which has no timeout (up to the maximum hash rounds on the request thread). E1c recommends
 * at least 25 s.
 */
export const COMPARE_TIMEOUT_MS = 25000

/**
 * Experiment 1's REST calls (docs/tracks/track-c-jai.md, "E1c interfaces for E1d"), made on the
 * shared axios client so the base URL and the ProblemDetail-to-ApiError mapping are the same as
 * everywhere else. Only the two long POSTs get their own timeout; the shared client is untouched.
 * Crash and recover are the shared cluster calls, as on the Cluster page.
 *
 * @param {object} [base] an api from createApi(); its client, crashNode, recoverNode and getEvents are reused
 */
export function createRmiApi(base = defaultApi) {
  const { client } = base
  const body = ({ fromNode, targetNode, method, text, rounds }) => ({ fromNode, targetNode, method, text, rounds })
  return {
    getOverview: () => client.get(BASE).then((res) => res.data),
    start: () => client.post(`${BASE}/start`).then((res) => res.data),
    getRegistries: () => client.get(`${BASE}/registries`).then((res) => res.data),
    getRegistry: (nodeId) => client.get(`${BASE}/registries/${nodeId}`).then((res) => res.data),
    call: (request) => client.post(`${BASE}/calls`, body(request), { timeout: CALL_TIMEOUT_MS }).then((res) => res.data),
    compare: (request) =>
      client.post(`${BASE}/comparisons`, body(request), { timeout: COMPARE_TIMEOUT_MS }).then((res) => res.data),
    getHistory: () => client.get(`${BASE}/calls`).then((res) => res.data),
    getLatest: () => client.get(`${BASE}/calls/latest`).then((res) => res.data),
    getCall: (id) => client.get(`${BASE}/calls/${id}`).then((res) => res.data),
    crashNode: (nodeId) => base.crashNode(nodeId),
    recoverNode: (nodeId) => base.recoverNode(nodeId),
    getEvents: (options) => base.getEvents(options),
  }
}

export const rmiApi = createRmiApi()

const TIMEOUT_CODES = new Set(['ECONNABORTED', 'ETIMEDOUT'])

/**
 * What went wrong, as the page tells it: one kind per case, each with its own message.
 * The shared ApiError keeps title, detail, nodeId and errors; actionInProgress (409 busy) and the
 * request's timeout are read from the original axios error here, so ApiError stays unchanged.
 *
 * Kinds: validation (400 with errors), bad-request (400 without), not-found (404), busy,
 * node-down and state-conflict (409 by title), timeout and network (no response), other.
 *
 * @param {Error|null} error an ApiError from services/api.js
 * @returns {{kind: string, status: number, title: string, detail: string, message: string,
 *   fieldErrors: object|null, actionInProgress: string|null, nodeId: number|null}|null}
 */
export function describeProblem(error) {
  if (!error) return null
  const status = error.status ?? 0
  const original = error.originalError
  const data = original?.response?.data ?? {}
  const title = error.title || 'Request failed'
  const detail = error.detail || error.message || ''
  const problem = { status, title, detail, fieldErrors: null, actionInProgress: null, nodeId: error.nodeId ?? null }

  if (status === 0) {
    if (TIMEOUT_CODES.has(original?.code)) {
      const ms = original?.config?.timeout
      const limit = typeof ms === 'number' ? ` within ${ms / 1000} s` : ' in time'
      return { ...problem, kind: 'timeout',
        message: `No answer from the backend${limit}. The request may still finish there; refresh to see the history.` }
    }
    return { ...problem, kind: 'network',
      message: 'The backend is not reachable. Start it again; this page reconnects by itself.' }
  }
  if (status === 400) {
    const errors = error.errors && typeof error.errors === 'object' ? error.errors : null
    if (errors && Object.keys(errors).length > 0) {
      return { ...problem, kind: 'validation', fieldErrors: errors,
        message: `The backend refused the request: ${Object.values(errors).join('; ')}.` }
    }
    return { ...problem, kind: 'bad-request', message: `The backend could not read the request: ${detail}.` }
  }
  if (status === 404) {
    return { ...problem, kind: 'not-found', message: `${title}: ${detail}.` }
  }
  if (status === 409 && title === 'Module busy') {
    return { ...problem, kind: 'busy', actionInProgress: data.actionInProgress ?? null,
      message: `${detail}. Nothing was retried; try again when it has finished.` }
  }
  if (status === 409 && title === 'Node down') {
    return { ...problem, kind: 'node-down', message: `Node down: ${detail}.` }
  }
  if (status === 409 && title === 'Node state conflict') {
    return { ...problem, kind: 'state-conflict', message: `Node state conflict: ${detail}.` }
  }
  return { ...problem, kind: 'other', message: detail && detail !== title ? `${title}: ${detail}` : title }
}
