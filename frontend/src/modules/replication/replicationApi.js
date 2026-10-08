import { api as defaultApi } from '@/services/api'

const BASE = '/api/modules/replication'

/**
 * Experiment 5's REST calls (docs/tracks/track-c-jai.md, "E5c — replication module and API"),
 * made on the shared axios client so the base URL and the ProblemDetail-to-ApiError mapping are
 * the same as everywhere else. Kept in the module folder so the shared services/api.js is
 * untouched. Crash and recover go through the module's own endpoints (backups only for a crash).
 *
 * @param {object} [base] an api from createApi(); its client and getEvents are reused
 */
export function createReplicationApi(base = defaultApi) {
  const { client } = base
  return {
    getOverview: () => client.get(BASE).then((res) => res.data),
    getReplicas: () => client.get(`${BASE}/replicas`).then((res) => res.data),
    read: (nodeId, key) => client.get(`${BASE}/nodes/${nodeId}/values`, { params: { key } }).then((res) => res.data),
    write: ({ key, value, model }) => client.post(`${BASE}/writes`, { key, value, model }).then((res) => res.data),
    crashBackup: (nodeId) => client.post(`${BASE}/nodes/${nodeId}/crash`).then((res) => res.data),
    recover: (nodeId) => client.post(`${BASE}/nodes/${nodeId}/recover`).then((res) => res.data),
    antiEntropy: (targetNodeId) => client.post(`${BASE}/anti-entropy`, { targetNodeId }).then((res) => res.data),
    injectStale: ({ backupNodeId, key, staleValue }) =>
      client.post(`${BASE}/stale-injections`, { backupNodeId, key, staleValue }).then((res) => res.data),
    getEvents: (options) => base.getEvents(options),
  }
}

export const replicationApi = createReplicationApi()
