import { api as defaultApi } from '@/services/api'

const BASE = '/api/modules/mapreduce'

/**
 * Experiment 7's REST calls (docs/tracks/track-d-rohan.md, "E7c interfaces"), made on the shared
 * axios client so the base URL and the ProblemDetail-to-ApiError mapping are the same as
 * everywhere else. Kept in the module folder so the shared services/api.js is untouched;
 * recovering a node uses the shared cluster call.
 *
 * @param {object} [base] an api from createApi(); its client and recoverNode are reused
 */
export function createMapReduceApi(base = defaultApi) {
  const { client } = base
  return {
    getOverview: () => client.get(BASE).then((res) => res.data),
    getRuns: () => client.get(`${BASE}/runs`).then((res) => res.data),
    /** The latest run, or null when the backend keeps none (its 404 is the empty state, not an error). */
    getLatestRun: () => client.get(`${BASE}/runs/latest`)
      .then((res) => res.data)
      .catch((err) => {
        if (err?.status === 404) {
          return null
        }
        throw err
      }),
    getRun: (runId) => client.get(`${BASE}/runs/${encodeURIComponent(runId)}`).then((res) => res.data),
    /**
     * POST /runs. The upload, when present, is the JSON/Base64 body the API expects; it is sent
     * once and never stored anywhere.
     */
    startRun: ({ jobId, inputType, upload, crashWorkerId }) => {
      const body = { jobId, inputType }
      if (upload) {
        body.upload = { fileName: upload.fileName, contentType: upload.contentType, contentBase64: upload.contentBase64 }
      }
      if (crashWorkerId !== null && crashWorkerId !== undefined) {
        body.crashWorkerId = crashWorkerId
      }
      return client.post(`${BASE}/runs`, body).then((res) => res.data)
    },
    /** GET /api/events/export as text (link L5); module undefined exports every module. */
    exportEvents: ({ module, limit } = {}) => {
      const params = {}
      if (module) params.module = module
      if (limit !== undefined && limit !== null) params.limit = limit
      return client.get('/api/events/export', {
        params,
        responseType: 'text',
        transformResponse: [(data) => data],
      }).then((res) => res.data)
    },
    recoverNode: (nodeId) => base.recoverNode(nodeId),
  }
}

export const mapReduceApi = createMapReduceApi()
