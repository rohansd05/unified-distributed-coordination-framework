import { api as defaultApi } from '@/services/api'

const BASE = '/api/modules/clocksync'

/**
 * Experiment 3 (Clock Synchronization) REST API calls, made using the shared Axios client
 * so that baseURL resolution and RFC 7807 ProblemDetail error normalization match the rest of the application.
 *
 * @param {object} [base] an api from createApi(); its client and getEvents are reused
 */
export function createClockSyncApi(base = defaultApi) {
  const { client } = base
  return {
    getOverview: () => client.get(BASE).then((res) => res.data),
    recordLocalEvent: (nodeId, { description } = {}) =>
      client.post(`${BASE}/nodes/${nodeId}/local-events`, { description }).then((res) => res.data),
    sendMessage: ({ from, to, payload }) =>
      client.post(`${BASE}/messages`, { from, to, payload }).then((res) => res.data),
    startTraffic: ({ seconds, messagesPerSecond } = {}) =>
      client.post(`${BASE}/traffic`, { seconds, messagesPerSecond }).then((res) => res.data),
    runBerkeleyRound: ({ outlierThresholdMillis } = {}) =>
      client.post(`${BASE}/berkeley-rounds`, { outlierThresholdMillis }).then((res) => res.data),
    updateDrift: (nodeId, { initialOffsetMillis, driftRateMsPerSec } = {}) =>
      client.put(`${BASE}/nodes/${nodeId}/drift`, { initialOffsetMillis, driftRateMsPerSec }).then((res) => res.data),
    getVerification: () => client.get(`${BASE}/verification`).then((res) => res.data),
    getTimeline: (limit = 100) =>
      client.get(`${BASE}/timeline`, { params: { limit } }).then((res) => res.data),
    getEvents: (options) => base.getEvents(options),
  }
}

export const clockSyncApi = createClockSyncApi()
