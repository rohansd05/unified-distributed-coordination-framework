import { describe, expect, it } from 'vitest'
import { ApiError, createApi } from '@/services/api'
import overviewAfterBully from '@/test/fixtures/election/overview-after-bully.json'
import startBully from '@/test/fixtures/election/start-bully.json'
import busyFixture from '@/test/fixtures/election/error-409-module-busy.json'
import nodeDownFixture from '@/test/fixtures/election/error-409-node-down.json'
import eventsCluster from '@/test/fixtures/election/events-cluster.json'
import { createElectionApi } from './electionApi'

/** An api whose axios adapter records each request and answers with `respond(config)`; no network. */
function apiAnswering(respond) {
  const calls = []
  const base = createApi({
    baseURL: 'http://localhost:8080',
    adapter: async (config) => {
      calls.push(config)
      const { status = 200, data } = respond(config)
      if (status >= 400) {
        const error = new Error(`Request failed with status code ${status}`)
        error.response = { status, data, statusText: '', headers: {}, config }
        throw error
      }
      return { status, data, statusText: 'OK', headers: {}, config }
    },
  })
  return { calls, api: createElectionApi(base) }
}

describe('electionApi', () => {
  it('reads the overview from GET /api/modules/election', async () => {
    const { calls, api } = apiAnswering(() => ({ data: overviewAfterBully }))
    await expect(api.getOverview()).resolves.toEqual(overviewAfterBully)
    expect(calls[0].method).toBe('get')
    expect(calls[0].url).toBe('/api/modules/election')
  })

  it('starts an election with POST /api/modules/election/elections and the body {algorithm, nodeId}', async () => {
    const { calls, api } = apiAnswering(() => ({ status: 202, data: startBully }))
    await expect(api.startElection({ algorithm: 'BULLY', nodeId: 1 })).resolves.toEqual(startBully)
    expect(calls[0].method).toBe('post')
    expect(calls[0].url).toBe('/api/modules/election/elections')
    expect(JSON.parse(calls[0].data)).toEqual({ algorithm: 'BULLY', nodeId: 1 })
  })

  it('crashes and recovers through the shared cluster API', async () => {
    const { calls, api } = apiAnswering(() => ({ data: {} }))
    await api.crashNode(5)
    await api.recoverNode(5)
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      'post /api/cluster/nodes/5/crash',
      'post /api/cluster/nodes/5/recover',
    ])
  })

  it('reads the cluster log through the shared events call', async () => {
    const { calls, api } = apiAnswering(() => ({ data: eventsCluster }))
    await expect(api.getEvents({ module: 'cluster', limit: 200 })).resolves.toEqual(eventsCluster)
    expect(calls[0].url).toBe('/api/events')
    expect(calls[0].params).toEqual({ module: 'cluster', limit: 200 })
  })

  it.each([
    ['module busy', busyFixture],
    ['node down', nodeDownFixture],
  ])('turns the real 409 %s ProblemDetail into an ApiError with its words', async (_, fixture) => {
    const { api } = apiAnswering(() => ({ status: fixture.status, data: fixture }))
    const error = await api.startElection({ algorithm: 'BULLY', nodeId: 1 }).catch((e) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(409)
    expect(error.title).toBe(fixture.title)
    expect(error.detail).toBe(fixture.detail)
  })
})
