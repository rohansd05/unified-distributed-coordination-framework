import { describe, expect, it, vi } from 'vitest'
import { ApiError, createApi } from '@/services/api'
import overviewBefore from '@/test/fixtures/loadbalancing/overview-before.json'
import runAccepted from '@/test/fixtures/loadbalancing/run-accepted.json'
import comparisonAccepted from '@/test/fixtures/loadbalancing/comparison-accepted.json'
import busyFixture from '@/test/fixtures/loadbalancing/error-409-module-busy.json'
import validationFixture from '@/test/fixtures/loadbalancing/error-400-validation.json'
import { createLoadBalancingApi } from './loadBalancingApi'

/** An api whose axios adapter records each request and answers with `respond(config)`. */
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
  return { calls, base, api: createLoadBalancingApi(base) }
}

describe('loadBalancingApi', () => {
  it('GET /api/modules/loadbalancing for the overview', async () => {
    const { calls, api } = apiAnswering(() => ({ data: overviewBefore }))

    expect(await api.getOverview()).toEqual(overviewBefore)
    expect(calls[0].method).toBe('get')
    expect(calls[0].url).toBe('/api/modules/loadbalancing')
  })

  it('POSTs a run without a crash plan, dropping unknown fields', async () => {
    const { calls, api } = apiAnswering(() => ({ status: 202, data: runAccepted }))

    const run = await api.startRun({ strategy: 'ROUND_ROBIN', requestCount: 60, workUnits: 400, concurrency: 12, extra: 1 })

    expect(run).toEqual(runAccepted)
    expect(calls[0].method).toBe('post')
    expect(calls[0].url).toBe('/api/modules/loadbalancing/runs')
    expect(JSON.parse(calls[0].data)).toEqual({ strategy: 'ROUND_ROBIN', requestCount: 60, workUnits: 400, concurrency: 12 })
  })

  it('POSTs a run with its crash plan', async () => {
    const { calls, api } = apiAnswering(() => ({ status: 202, data: runAccepted }))

    await api.startRun({ strategy: 'ROUND_ROBIN', requestCount: 60, workUnits: 400, concurrency: 12,
      crash: { nodeId: 3, afterServed: 20, note: 'dropped' } })

    expect(JSON.parse(calls[0].data).crash).toEqual({ nodeId: 3, afterServed: 20 })
  })

  it('POSTs a comparison to /comparisons', async () => {
    const { calls, api } = apiAnswering(() => ({ status: 202, data: comparisonAccepted }))

    expect(await api.startComparison({ requestCount: 60, workUnits: 400, concurrency: 12 })).toEqual(comparisonAccepted)
    expect(calls[0].url).toBe('/api/modules/loadbalancing/comparisons')
    expect(JSON.parse(calls[0].data)).toEqual({ requestCount: 60, workUnits: 400, concurrency: 12 })
  })

  it('turns the real 409 and 400 bodies into ApiErrors with their fields', async () => {
    const busy = apiAnswering(() => ({ status: 409, data: busyFixture }))
    const busyError = await busy.api.startComparison({ requestCount: 60, workUnits: 400, concurrency: 12 }).catch((e) => e)
    expect(busyError).toBeInstanceOf(ApiError)
    expect(busyError.title).toBe('Module busy')
    expect(busyError.moduleId).toBe('loadbalancing')

    const invalid = apiAnswering(() => ({ status: 400, data: validationFixture }))
    const invalidError = await invalid.api.startRun({ strategy: 'ROUND_ROBIN', requestCount: 0, workUnits: 400, concurrency: 12 })
      .catch((e) => e)
    expect(invalidError.errors).toEqual(validationFixture.errors)
  })

  it('recovers a node and reads events through the shared api', async () => {
    const { base, api } = apiAnswering(() => ({ data: {} }))
    const recover = vi.spyOn(base, 'recoverNode').mockResolvedValue({ id: 3 })
    const events = vi.spyOn(base, 'getEvents').mockResolvedValue([])

    await api.recoverNode(3)
    await api.getEvents({ module: 'loadbalancing', limit: 50 })

    expect(recover).toHaveBeenCalledWith(3)
    expect(events).toHaveBeenCalledWith({ module: 'loadbalancing', limit: 50 })
  })
})
