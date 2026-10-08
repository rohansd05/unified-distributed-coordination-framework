import { api as defaultApi } from '@/services/api'

const BASE = '/api/modules/loadbalancing'

/**
 * Experiment 6's REST calls (docs/tracks/track-a-nidhi.md, "Load Balancing API"), made on the
 * shared axios client so the base URL and the ProblemDetail-to-ApiError mapping are the same
 * as everywhere else. Kept in the module folder so the shared services/api.js is untouched;
 * recovering a node uses the shared cluster call.
 *
 * @param {object} [base] an api from createApi(); its client, recoverNode and getEvents are reused
 */
export function createLoadBalancingApi(base = defaultApi) {
  const { client } = base
  return {
    getOverview: () => client.get(BASE).then((res) => res.data),
    startRun: ({ strategy, requestCount, workUnits, concurrency, crash }) => {
      const body = { strategy, requestCount, workUnits, concurrency }
      if (crash) {
        body.crash = { nodeId: crash.nodeId, afterServed: crash.afterServed }
      }
      return client.post(`${BASE}/runs`, body).then((res) => res.data)
    },
    startComparison: ({ requestCount, workUnits, concurrency }) =>
      client.post(`${BASE}/comparisons`, { requestCount, workUnits, concurrency }).then((res) => res.data),
    recoverNode: (nodeId) => base.recoverNode(nodeId),
    getEvents: (options) => base.getEvents(options),
  }
}

export const loadBalancingApi = createLoadBalancingApi()
