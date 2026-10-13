import { describe, expect, it } from 'vitest'
import { createApi } from '@/services/api'
import overviewAfter from '@/test/fixtures/mpi/overview-after-start.json'
import overviewBefore from '@/test/fixtures/mpi/overview-before-start.json'
import startFixture from '@/test/fixtures/mpi/start.json'
import history from '@/test/fixtures/mpi/collectives-history.json'
import broadcast from '@/test/fixtures/mpi/collective-broadcast.json'
import missingFields from '@/test/fixtures/mpi/error-400-missing-fields.json'
import countError from '@/test/fixtures/mpi/error-400-count.json'
import seedError from '@/test/fixtures/mpi/error-400-seed.json'
import unreadable from '@/test/fixtures/mpi/error-400-unreadable.json'
import badId from '@/test/fixtures/mpi/error-400-bad-id.json'
import unknownNode from '@/test/fixtures/mpi/error-404-unknown-node.json'
import unknownCollective from '@/test/fixtures/mpi/error-404-unknown-collective.json'
import noCollectiveYet from '@/test/fixtures/mpi/error-404-no-collective-yet.json'
import busy from '@/test/fixtures/mpi/error-409-busy.json'
import rootDown from '@/test/fixtures/mpi/error-409-root-down.json'
import noLiveNode from '@/test/fixtures/mpi/error-409-no-live-node.json'
import {
  COLLECTIVE_TIMEOUT_FALLBACK_MS,
  COLLECTIVE_TIMEOUT_MARGIN_MS,
  collectiveTimeoutMs,
  createMpiApi,
  describeProblem,
} from './mpiApi'

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
  return { calls, api: createMpiApi(base) }
}

/** The problem a real ProblemDetail body becomes, through the shared client and describeProblem. */
async function problemFor(body) {
  const { api } = apiAnswering(() => ({ status: body.status, data: body }))
  return describeProblem(await api.runCollective({ operation: 'BROADCAST', count: 5 }, overviewAfter).catch((err) => err))
}

describe('mpiApi requests', () => {
  it('reads the overview and the history, and starts, with the shared timeout', async () => {
    const answers = { 'get /api/modules/mpi': overviewAfter, 'get /api/modules/mpi/collectives': history,
      'post /api/modules/mpi/start': startFixture }
    const { calls, api } = apiAnswering((config) => ({ data: answers[`${config.method} ${config.url}`] }))
    expect(await api.getOverview()).toEqual(overviewAfter)
    expect(await api.getHistory()).toEqual(history)
    expect(await api.start()).toEqual(startFixture)
    expect(calls.map((c) => [c.method, c.url, c.timeout])).toEqual([
      ['get', '/api/modules/mpi', SHARED_TIMEOUT_MS],
      ['get', '/api/modules/mpi/collectives', SHARED_TIMEOUT_MS],
      ['post', '/api/modules/mpi/start', SHARED_TIMEOUT_MS],
    ])
  })

  it('posts the collective body as given, with the overview\'s bound plus the margin as its timeout', async () => {
    const { calls, api } = apiAnswering(() => ({ data: broadcast }))
    const body = { operation: 'BROADCAST', count: 1000, seed: 42 }
    expect(await api.runCollective(body, overviewAfter)).toEqual(broadcast)
    expect(calls[0].method).toBe('post')
    expect(calls[0].url).toBe('/api/modules/mpi/collectives')
    expect(JSON.parse(calls[0].data)).toEqual(body)
    expect(calls[0].timeout).toBe(overviewAfter.limits.recommendedMinClientTimeoutMillis + COLLECTIVE_TIMEOUT_MARGIN_MS)
    expect(calls[0].timeout).toBe(25000)
  })

  it('uses the named fallback timeout before the overview has loaded', async () => {
    const { calls, api } = apiAnswering(() => ({ data: broadcast }))
    await api.runCollective({ operation: 'GATHER', count: 5 }, null)
    expect(calls[0].timeout).toBe(COLLECTIVE_TIMEOUT_FALLBACK_MS)
  })

  it('computes the timeout from any overview, and (synthetic limits) falls back when the bound is missing', () => {
    expect(collectiveTimeoutMs(overviewBefore)).toBe(22000 + COLLECTIVE_TIMEOUT_MARGIN_MS)
    expect(collectiveTimeoutMs({ limits: { recommendedMinClientTimeoutMillis: 7000 } })).toBe(7000 + COLLECTIVE_TIMEOUT_MARGIN_MS)
    expect(collectiveTimeoutMs({ limits: {} })).toBe(COLLECTIVE_TIMEOUT_FALLBACK_MS)
    expect(collectiveTimeoutMs(undefined)).toBe(COLLECTIVE_TIMEOUT_FALLBACK_MS)
    expect([COLLECTIVE_TIMEOUT_MARGIN_MS, COLLECTIVE_TIMEOUT_FALLBACK_MS]).toEqual([3000, 25000])
  })

  it('has no call for one collective or the latest one (the history holds whole entries)', () => {
    const { api } = apiAnswering(() => ({ data: null }))
    expect(api.getCollective).toBeUndefined()
    expect(api.getLatest).toBeUndefined()
  })
})

describe('mpiApi problems, from the real fixtures', () => {
  it('maps the module\'s 400s with an errors map to field errors', async () => {
    const missing = await problemFor(missingFields)
    expect(missing.kind).toBe('validation')
    expect(missing.fieldErrors).toEqual({ operation: 'operation is required', count: 'count is required' })
    expect((await problemFor(countError)).fieldErrors).toEqual({ count: 'count must be 1-100000, was 100001' })
    expect((await problemFor(seedError)).fieldErrors).toEqual({ seed: 'seed must be 0-9007199254740991, was -1' })
  })

  it('maps Spring\'s default 400s (no errors) to a generic message', async () => {
    expect(await problemFor(unreadable)).toMatchObject({ kind: 'bad-request', fieldErrors: null,
      message: 'The backend could not read the request: Failed to read request.' })
    expect(await problemFor(badId)).toMatchObject({ kind: 'bad-request',
      message: 'The backend could not read the request: Failed to convert \'id\' with value: \'abc\'.' })
  })

  it('maps each 404 to its own message', async () => {
    expect((await problemFor(unknownNode)).message).toBe('Unknown node: No node with id 9.')
    expect((await problemFor(unknownCollective)).message).toBe(`Unknown collective: ${unknownCollective.detail}.`)
    expect((await problemFor(noCollectiveYet)).message).toBe('Unknown collective: No collective has run yet.')
  })

  it('maps 409 busy (with what is running), node down and state conflict', async () => {
    expect(await problemFor(busy)).toMatchObject({ kind: 'busy', actionInProgress: 'BROADCAST of 100000 values' })
    expect(await problemFor(rootDown)).toMatchObject({ kind: 'node-down', nodeId: 3, message: 'Node down: Node 3 is crashed.' })
    expect(await problemFor(noLiveNode)).toMatchObject({ kind: 'state-conflict',
      message: 'Node state conflict: Every node is crashed, so no node can be the root of a collective.' })
  })

  it('reports a client timeout with the collective timeout it waited', async () => {
    const base = createApi({
      baseURL: 'http://localhost:8080',
      adapter: async (config) => {
        const error = new Error(`timeout of ${config.timeout}ms exceeded`)
        error.code = 'ECONNABORTED'
        error.config = config
        throw error
      },
    })
    const err = await createMpiApi(base).runCollective({ operation: 'BROADCAST', count: 5 }, overviewAfter).catch((e) => e)
    const problem = describeProblem(err)
    expect(problem.kind).toBe('timeout')
    expect(problem.message).toContain('within 25 s')
  })
})
