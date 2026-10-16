import { api as defaultApi } from '@/services/api'
import { describeProblem } from '@/modules/rmi/rmiApi'

const BASE = '/api/modules/matrix'

/**
 * Added to the backend's own bound for one POST /start or /runs. The bound
 * (limits.recommendedMinClientTimeoutMillis = worstCaseRunMillis) comes from the configured
 * timeouts and leaves out CPU time (E10c), so the page waits a little longer.
 */
export const RUN_TIMEOUT_MARGIN_MS = 3000

/**
 * Used only when the overview could not be read: E10c's local-profile bound plus the margin.
 * Every other wait comes from the served limits.
 */
export const RUN_TIMEOUT_FALLBACK_MS = 29001

/** The per-request timeout of POST /start and POST /runs for this overview, in ms. */
export function runTimeoutMs(overview) {
  const bound = overview?.limits?.recommendedMinClientTimeoutMillis
  return typeof bound === 'number' ? bound + RUN_TIMEOUT_MARGIN_MS : RUN_TIMEOUT_FALLBACK_MS
}

/**
 * Experiment 10's REST calls (docs/tracks/track-c-jai.md, "E10c interfaces for E10d"), on the
 * shared axios client, so the base URL and the ProblemDetail-to-ApiError mapping are the same as
 * everywhere else. POST /start and POST /runs get the run timeout; the reads keep the shared
 * client's.
 *
 * @param {object} [base] an api from createApi(); its client and getEvents are reused
 */
export function createMatrixApi(base = defaultApi) {
  const { client } = base
  return {
    getOverview: () => client.get(BASE).then((res) => res.data),
    /** @param {object|null} overview for the timeout */
    start: (overview) => client.post(`${BASE}/start`, null, { timeout: runTimeoutMs(overview) }).then((res) => res.data),
    /** @param {object} body from runBody; @param {object|null} overview for the timeout */
    runMatrix: (body, overview) =>
      client.post(`${BASE}/runs`, body, { timeout: runTimeoutMs(overview) }).then((res) => res.data),
    getRuns: () => client.get(`${BASE}/runs`).then((res) => res.data),
    getSeries: () => client.get(`${BASE}/series`).then((res) => res.data),
    getEvents: (options) => base.getEvents(options),
  }
}

export const matrixApi = createMatrixApi()

/** The same problem kinds and messages as Experiments 1 and 9 (400, 404, 409 busy / node down / state conflict, timeout, network). */
export { describeProblem }
