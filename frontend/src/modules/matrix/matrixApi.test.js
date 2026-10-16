import { describe, expect, it } from 'vitest'
import { createApi } from '@/services/api'
import overviewAfter from '@/test/fixtures/matrix/overview-after-start.json'
import startFixture from '@/test/fixtures/matrix/start.json'
import runsHistory from '@/test/fixtures/matrix/runs-history.json'
import series from '@/test/fixtures/matrix/series.json'
import complete50 from '@/test/fixtures/matrix/run-complete-50.json'
import missingFields from '@/test/fixtures/matrix/error-400-missing-fields.json'
import aboveMax from '@/test/fixtures/matrix/error-400-size-above-max.json'
import unreadable from '@/test/fixtures/matrix/error-400-unreadable.json'
import unknownNode from '@/test/fixtures/matrix/error-404-unknown-node.json'
import busy from '@/test/fixtures/matrix/error-409-busy.json'
import rootDown from '@/test/fixtures/matrix/error-409-root-down.json'
import noLiveNode from '@/test/fixtures/matrix/error-409-no-live-node.json'
import { RUN_TIMEOUT_FALLBACK_MS, RUN_TIMEOUT_MARGIN_MS, createMatrixApi, describeProblem, runTimeoutMs } from './matrixApi'

const SHARED_TIMEOUT_MS = 10000

/** An api whose axios adapter records each request and answers with `respond(config)`; no network. */
function apiAnswering(respond) {
  const calls = []
  const base = createApi({
    baseURL: 'http://localhost:8080',
    adapter: async (config) => {
      calls.push(config)
      const { status = 200, data, code } = respond(config)
      if (code) {
        const error = new Error('timeout')
        error.code = code
        error.config = config
        throw error
      }
      if (status >= 400) {
        const error = new Error(`Request failed with status code ${status}`)
        error.response = { status, data, statusText: '', headers: {}, config }
        throw error
      }
      return { status, data, statusText: 'OK', headers: {}, config }
    },
  })
  return { calls, api: createMatrixApi(base) }
}

async function problemFor(body) {
  const { api } = apiAnswering(() => ({ status: body.status, data: body }))
  return describeProblem(await api.runMatrix({ size: 50 }, overviewAfter).catch((err) => err))
}

describe('matrixApi requests', () => {
  it('reads the overview, the runs and the series with the shared timeout', async () => {
    const answers = { '/api/modules/matrix': overviewAfter, '/api/modules/matrix/runs': runsHistory, '/api/modules/matrix/series': series }
    const { calls, api } = apiAnswering((config) => ({ data: answers[config.url] }))
    expect(await api.getOverview()).toEqual(overviewAfter)
    expect(await api.getRuns()).toEqual(runsHistory)
    expect(await api.getSeries()).toEqual(series)
    expect(calls.map((c) => [c.method, c.url, c.timeout])).toEqual([
      ['get', '/api/modules/matrix', SHARED_TIMEOUT_MS],
      ['get', '/api/modules/matrix/runs', SHARED_TIMEOUT_MS],
      ['get', '/api/modules/matrix/series', SHARED_TIMEOUT_MS],
    ])
  })

  it('starts and runs with the served bound plus the margin as the timeout, and posts the body as given', async () => {
    const { calls, api } = apiAnswering((config) => ({ data: config.url.endsWith('/start') ? startFixture : complete50 }))
    expect(await api.start(overviewAfter)).toEqual(startFixture)
    expect(await api.runMatrix({ size: 50, rootNode: 3 }, overviewAfter)).toEqual(complete50)
    const expected = overviewAfter.limits.recommendedMinClientTimeoutMillis + RUN_TIMEOUT_MARGIN_MS
    expect(calls.map((c) => [c.method, c.url, c.timeout])).toEqual([
      ['post', '/api/modules/matrix/start', expected],
      ['post', '/api/modules/matrix/runs', expected],
    ])
    expect(JSON.parse(calls[1].data)).toEqual({ size: 50, rootNode: 3 })
  })

  it('uses the named fallback only without a served bound (synthetic limits in the test)', async () => {
    const { calls, api } = apiAnswering(() => ({ data: startFixture }))
    await api.start(null)
    expect(calls[0].timeout).toBe(RUN_TIMEOUT_FALLBACK_MS)
    expect(runTimeoutMs({ limits: { recommendedMinClientTimeoutMillis: 7000 } })).toBe(7000 + RUN_TIMEOUT_MARGIN_MS)
    expect(runTimeoutMs({ limits: {} })).toBe(RUN_TIMEOUT_FALLBACK_MS)
    expect([RUN_TIMEOUT_MARGIN_MS, RUN_TIMEOUT_FALLBACK_MS]).toEqual([3000, 29001])
  })

  it('reports a client timeout as a timeout, with the wait it used', async () => {
    const { api } = apiAnswering(() => ({ code: 'ECONNABORTED' }))
    const problem = describeProblem(await api.runMatrix({ size: 50 }, overviewAfter).catch((err) => err))
    expect(problem.kind).toBe('timeout')
    expect(problem.message).toContain(`${runTimeoutMs(overviewAfter) / 1000} s`)
  })
})

describe('matrixApi problems, from the real fixtures', () => {
  it('maps the module\'s 400s to field errors and Spring\'s default 400 to a generic message', async () => {
    expect((await problemFor(missingFields)).fieldErrors).toEqual(missingFields.errors)
    expect((await problemFor(aboveMax)).fieldErrors).toEqual({ size: 'size must be 2-316, was 317' })
    const generic = await problemFor(unreadable)
    expect(generic.kind).toBe('bad-request')
    expect(generic.fieldErrors).toBeNull()
  })

  it('maps 404 unknown node and the three 409s, keeping nodeId 0 for "no live node"', async () => {
    expect((await problemFor(unknownNode)).kind).toBe('not-found')
    const busyProblem = await problemFor(busy)
    expect(busyProblem.kind).toBe('busy')
    expect(busyProblem.actionInProgress).toBe('Matrix run of size 316')
    expect((await problemFor(rootDown)).kind).toBe('node-down')
    const none = await problemFor(noLiveNode)
    expect(none.kind).toBe('state-conflict')
    expect(none.nodeId).toBe(0)
  })
})
