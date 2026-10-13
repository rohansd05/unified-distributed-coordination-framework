import { api as defaultApi } from '@/services/api'
import { describeProblem } from '@/modules/rmi/rmiApi'

const BASE = '/api/modules/mpi'

/**
 * Added to the backend's own bound for one POST /collectives. The bound
 * (limits.recommendedMinClientTimeoutMillis = deadline + one worst-case transfer) is derived from
 * the configured timeouts and leaves out CPU time (E9c), so the page waits a little longer.
 */
export const COLLECTIVE_TIMEOUT_MARGIN_MS = 3000

/**
 * The POST /collectives timeout before the overview has loaded: E9c's local bound (22000 ms) plus
 * the margin. The form only shows once the overview is there, so this is a safety net.
 */
export const COLLECTIVE_TIMEOUT_FALLBACK_MS = 25000

/** The per-request timeout of POST /collectives for this overview, in ms. */
export function collectiveTimeoutMs(overview) {
  const bound = overview?.limits?.recommendedMinClientTimeoutMillis
  return typeof bound === 'number' ? bound + COLLECTIVE_TIMEOUT_MARGIN_MS : COLLECTIVE_TIMEOUT_FALLBACK_MS
}

/**
 * Experiment 9's REST calls (docs/tracks/track-c-jai.md, "E9c interfaces for E9d"), on the shared
 * axios client, so the base URL and the ProblemDetail-to-ApiError mapping are the same as
 * everywhere else. Only POST /collectives gets its own timeout; everything else keeps the shared
 * client's. Crash and recover are the shared cluster calls, as on the Cluster page.
 *
 * @param {object} [base] an api from createApi(); its client, crashNode, recoverNode and getEvents are reused
 */
export function createMpiApi(base = defaultApi) {
  const { client } = base
  return {
    getOverview: () => client.get(BASE).then((res) => res.data),
    start: () => client.post(`${BASE}/start`).then((res) => res.data),
    /** @param {object} body from collectiveBody; @param {object|null} overview for the timeout */
    runCollective: (body, overview) =>
      client.post(`${BASE}/collectives`, body, { timeout: collectiveTimeoutMs(overview) }).then((res) => res.data),
    getHistory: () => client.get(`${BASE}/collectives`).then((res) => res.data),
    crashNode: (nodeId) => base.crashNode(nodeId),
    recoverNode: (nodeId) => base.recoverNode(nodeId),
    getEvents: (options) => base.getEvents(options),
  }
}

export const mpiApi = createMpiApi()

/** The same problem kinds and messages as Experiment 1 (400, 404, 409 busy / node down / state conflict, timeout, network). */
export { describeProblem }
