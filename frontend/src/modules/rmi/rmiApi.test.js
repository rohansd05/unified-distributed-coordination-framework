import { describe, expect, it } from 'vitest'
import { AxiosError } from 'axios'
import { ApiError, createApi } from '@/services/api'
import overviewAfterStart from '@/test/fixtures/rmi/overview-after-start.json'
import startFixture from '@/test/fixtures/rmi/start.json'
import registriesAllUp from '@/test/fixtures/rmi/registries-all-up.json'
import registryNode2 from '@/test/fixtures/rmi/registry-node-2.json'
import callsHistory from '@/test/fixtures/rmi/calls-history.json'
import callLatest from '@/test/fixtures/rmi/call-latest.json'
import callById from '@/test/fixtures/rmi/call-by-id.json'
import callPing from '@/test/fixtures/rmi/call-ping.json'
import comparisonHash from '@/test/fixtures/rmi/comparison-compute-hash.json'
import fieldNotTaken from '@/test/fixtures/rmi/error-400-field-not-taken.json'
import unreadable from '@/test/fixtures/rmi/error-400-unreadable.json'
import badId from '@/test/fixtures/rmi/error-400-bad-id.json'
import noCallYet from '@/test/fixtures/rmi/error-404-no-call-yet.json'
import unknownCall from '@/test/fixtures/rmi/error-404-unknown-call.json'
import unknownNode from '@/test/fixtures/rmi/error-404-unknown-node.json'
import busy from '@/test/fixtures/rmi/error-409-busy.json'
import callerCrashed from '@/test/fixtures/rmi/error-409-caller-crashed.json'
import compareTargetDown from '@/test/fixtures/rmi/error-409-compare-target-down.json'
import nodeStateConflict from '@/test/fixtures/replication/error-409-node-state.json'
import { COMPARE_TIMEOUT_MS, CALL_TIMEOUT_MS, createRmiApi, describeProblem } from './rmiApi'

const SHARED_TIMEOUT_MS = 10000

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
  return { calls, api: createRmiApi(base) }
}

/** The ApiError a real ProblemDetail body becomes, through the shared client. */
async function errorFor(status, body) {
  const { api } = apiAnswering(() => ({ status, data: body }))
  return api.call({ fromNode: 1, targetNode: 2, method: 'PING' }).catch((err) => err)
}

describe('rmiApi requests', () => {
  it('reads the overview, registries, one registry, the history, the latest call and one call', async () => {
    const answers = {
      '/api/modules/rmi': overviewAfterStart,
      '/api/modules/rmi/registries': registriesAllUp,
      '/api/modules/rmi/registries/2': registryNode2,
      '/api/modules/rmi/calls': callsHistory,
      '/api/modules/rmi/calls/latest': callLatest,
      '/api/modules/rmi/calls/1': callById,
    }
    const { calls, api } = apiAnswering((config) => ({ data: answers[config.url] }))

    expect(await api.getOverview()).toEqual(overviewAfterStart)
    expect(await api.getRegistries()).toEqual(registriesAllUp)
    expect(await api.getRegistry(2)).toEqual(registryNode2)
    expect(await api.getHistory()).toEqual(callsHistory)
    expect(await api.getLatest()).toEqual(callLatest)
    expect(await api.getCall(1)).toEqual(callById)
    expect(calls.map((c) => [c.method, c.url])).toEqual(Object.keys(answers).map((url) => ['get', url]))
  })

  it('gives no GET a per-request timeout: every GET keeps the shared client default', async () => {
    const { calls, api } = apiAnswering(() => ({ data: {} }))
    await Promise.all([api.getOverview(), api.getRegistries(), api.getRegistry(1), api.getHistory(), api.getLatest(), api.getCall(1)])
    expect(calls).toHaveLength(6)
    expect(calls.every((c) => c.timeout === SHARED_TIMEOUT_MS)).toBe(true)
  })

  it('POSTs /start with no body and the shared timeout', async () => {
    const { calls, api } = apiAnswering(() => ({ data: startFixture }))
    expect(await api.start()).toEqual(startFixture)
    expect([calls[0].method, calls[0].url, calls[0].data]).toEqual(['post', '/api/modules/rmi/start', undefined])
    expect(calls[0].timeout).toBe(SHARED_TIMEOUT_MS)
  })

  it('POSTs /calls with exactly fromNode, targetNode, method, text and rounds, and CALL_TIMEOUT_MS', async () => {
    const { calls, api } = apiAnswering(() => ({ data: callPing }))
    expect(await api.call({ fromNode: 1, targetNode: 2, method: 'PING', extra: true })).toEqual(callPing)
    await api.call({ fromNode: 1, targetNode: 4, method: 'ECHO', text: '' })
    expect(calls[0].url).toBe('/api/modules/rmi/calls')
    expect(JSON.parse(calls[0].data)).toEqual({ fromNode: 1, targetNode: 2, method: 'PING' })
    expect(JSON.parse(calls[1].data)).toEqual({ fromNode: 1, targetNode: 4, method: 'ECHO', text: '' })
    expect(calls[0].timeout).toBe(CALL_TIMEOUT_MS)
  })

  it('POSTs /comparisons with COMPARE_TIMEOUT_MS', async () => {
    const { calls, api } = apiAnswering(() => ({ data: comparisonHash }))
    expect(await api.compare({ fromNode: 1, targetNode: 2, method: 'COMPUTE_HASH', rounds: 100000 })).toEqual(comparisonHash)
    expect(calls[0].url).toBe('/api/modules/rmi/comparisons')
    expect(JSON.parse(calls[0].data)).toEqual({ fromNode: 1, targetNode: 2, method: 'COMPUTE_HASH', rounds: 100000 })
    expect(calls[0].timeout).toBe(COMPARE_TIMEOUT_MS)
  })

  it('names the two timeouts: 20000 ms for a call and 25000 ms for a comparison, both above the shared 10000 ms', () => {
    expect(CALL_TIMEOUT_MS).toBe(20000)
    expect(COMPARE_TIMEOUT_MS).toBe(25000)
  })

  it('crashes and recovers through the shared cluster endpoints, not new ones', async () => {
    const { calls, api } = apiAnswering(() => ({ data: {} }))
    await api.crashNode(3)
    await api.recoverNode(3)
    expect(calls.map((c) => [c.method, c.url])).toEqual([
      ['post', '/api/cluster/nodes/3/crash'],
      ['post', '/api/cluster/nodes/3/recover'],
    ])
  })
})

describe('describeProblem', () => {
  it('is null for no error', () => {
    expect(describeProblem(null)).toBeNull()
  })

  it('reads a 400 with errors as a validation problem with every field', async () => {
    const problem = describeProblem(await errorFor(400, fieldNotTaken))
    expect(problem.kind).toBe('validation')
    expect(problem.fieldErrors).toEqual(fieldNotTaken.errors)
    expect(problem.message).toContain('text must be left out for PING')
    expect(problem.message).toContain('rounds must be left out for PING')
  })

  it('reads both 400s without errors (unreadable body, bad id) as a bad request', async () => {
    for (const body of [unreadable, badId]) {
      const problem = describeProblem(await errorFor(400, body))
      expect(problem.kind).toBe('bad-request')
      expect(problem.fieldErrors).toBeNull()
      expect(problem.message).toBe(`The backend could not read the request: ${body.detail}.`)
    }
  })

  it('reads the three 404s with their own title and detail', async () => {
    for (const body of [noCallYet, unknownCall, unknownNode]) {
      const problem = describeProblem(await errorFor(404, body))
      expect(problem.kind).toBe('not-found')
      expect(problem.message).toBe(`${body.title}: ${body.detail}.`)
    }
  })

  it('reads the real 409 busy with the server message and the actionInProgress ApiError drops', async () => {
    const error = await errorFor(409, busy)
    expect(error).toBeInstanceOf(ApiError)
    const problem = describeProblem(error)
    expect(problem.kind).toBe('busy')
    expect(problem.actionInProgress).toBe('COMPUTE_HASH from node 1 to node 2')
    expect(problem.message).toContain(busy.detail)
    expect(problem.message).toContain('Nothing was retried')
  })

  it('reads both real 409 "Node down" bodies (crashed caller, crashed comparison target)', async () => {
    for (const body of [callerCrashed, compareTargetDown]) {
      const problem = describeProblem(await errorFor(409, body))
      expect(problem.kind).toBe('node-down')
      expect(problem.nodeId).toBe(3)
      expect(problem.message).toBe('Node down: Node 3 is crashed.')
    }
  })

  it('reads 409 "Node state conflict" (capture from the shared exception handler, replication fixtures)', async () => {
    const problem = describeProblem(await errorFor(409, nodeStateConflict))
    expect(problem.kind).toBe('state-conflict')
    expect(problem.message).toBe('Node state conflict: Node 2 is already up.')
  })

  it('reads a client timeout, with the request\'s own timeout, apart from a network error', async () => {
    const timedOut = createApi({
      baseURL: 'http://localhost:8080',
      adapter: async (config) => { throw new AxiosError(`timeout of ${config.timeout}ms exceeded`, 'ECONNABORTED', config) },
    })
    const timeoutProblem = describeProblem(await createRmiApi(timedOut).call({ fromNode: 1, targetNode: 2, method: 'PING' }).catch((e) => e))
    expect(timeoutProblem.kind).toBe('timeout')
    expect(timeoutProblem.message).toContain('within 20 s')

    const offline = createApi({
      baseURL: 'http://localhost:8080',
      adapter: async (config) => { throw new AxiosError('Network Error', 'ERR_NETWORK', config) },
    })
    const networkProblem = describeProblem(await createRmiApi(offline).getOverview().catch((e) => e))
    expect(networkProblem.kind).toBe('network')
    expect(networkProblem.message).not.toBe(timeoutProblem.message)
  })
})
