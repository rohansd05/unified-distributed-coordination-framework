import { api as defaultApi } from '@/services/api'

const BASE = '/api/scenarios'

/**
 * The scenario engine's REST calls (docs/tracks/track-b-swanand.md, "9A.6 APIs"), on the shared
 * axios client, so the base URL and the ProblemDetail-to-ApiError mapping are the same as
 * everywhere else.
 *
 * @param {object} [base] an api from createApi(); its client is reused
 */
export function createScenariosApi(base = defaultApi) {
  const { client } = base
  return {
    /** Every scenario with its steps' narration, in order. */
    getScenarios: () => client.get(BASE).then((res) => res.data),
    /** 202 {runId, scenarioId}; rejects with an ApiError (404 unknown, 409 another run in progress). */
    startRun: (scenarioId) =>
      client.post(`${BASE}/${encodeURIComponent(scenarioId)}/runs`).then((res) => res.data),
    /** The most recently started run, or null (204) when none is kept. */
    getLatestRun: () =>
      client.get(`${BASE}/runs/latest`).then((res) => (res.status === 204 || !res.data ? null : res.data)),
    getRun: (runId) => client.get(`${BASE}/runs/${Number(runId)}`).then((res) => res.data),
  }
}

export const scenariosApi = createScenariosApi()
