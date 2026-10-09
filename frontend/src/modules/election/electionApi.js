import { api as defaultApi } from '@/services/api'

const BASE = '/api/modules/election'

/**
 * Experiment 4's REST calls (docs/tracks/track-b-swanand.md, "E4c APIs"), made on the shared
 * axios client so the base URL and the ProblemDetail-to-ApiError mapping are the same as
 * everywhere else. Kept in the module folder so the shared services/api.js is untouched. Crash
 * and recover use the shared cluster API, as the cluster page does.
 *
 * @param {object} [base] an api from createApi(); its client, crashNode, recoverNode and getEvents are reused
 */
export function createElectionApi(base = defaultApi) {
  const { client } = base
  return {
    getOverview: () => client.get(BASE).then((res) => res.data),
    startElection: ({ algorithm, nodeId }) => client.post(`${BASE}/elections`, { algorithm, nodeId }).then((res) => res.data),
    crashNode: (nodeId) => base.crashNode(nodeId),
    recoverNode: (nodeId) => base.recoverNode(nodeId),
    getEvents: (options) => base.getEvents(options),
  }
}

export const electionApi = createElectionApi()
