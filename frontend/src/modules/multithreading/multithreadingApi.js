import { api as defaultApi } from '@/services/api'

const BASE = '/api/modules/multithreading'

/**
 * Experiment 2's REST calls (docs/tracks/track-a-nidhi.md, "Multithreading API"), made on the
 * shared axios client so the base URL and the ProblemDetail-to-ApiError mapping are the same
 * as everywhere else. Kept in the module folder so the shared services/api.js is untouched.
 *
 * @param {object} [base] an api from createApi(); its client and getEvents are reused
 */
export function createMultithreadingApi(base = defaultApi) {
  const { client } = base
  return {
    getOverview: () => client.get(BASE).then((res) => res.data),
    submitBatch: (nodeId, { count, type, payloadSize }) =>
      client.post(`${BASE}/nodes/${nodeId}/batches`, { count, type, payloadSize }).then((res) => res.data),
    runBackpressure: (nodeId) => client.post(`${BASE}/nodes/${nodeId}/backpressure`).then((res) => res.data),
    getRequests: (nodeId, limit) =>
      client.get(`${BASE}/nodes/${nodeId}/requests`, { params: { limit } }).then((res) => res.data),
    getEvents: (options) => base.getEvents(options),
  }
}

export const multithreadingApi = createMultithreadingApi()
