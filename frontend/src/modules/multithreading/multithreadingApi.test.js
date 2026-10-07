import { describe, expect, it } from 'vitest'
import { ApiError, createApi } from '@/services/api'
import overviewBefore from '@/test/fixtures/multithreading/overview-before.json'
import batchFixture from '@/test/fixtures/multithreading/batch.json'
import busyFixture from '@/test/fixtures/multithreading/error-409-module-busy.json'
import validationFixture from '@/test/fixtures/multithreading/error-400-validation.json'
import { createMultithreadingApi } from './multithreadingApi'

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
  return { calls, api: createMultithreadingApi(base) }
}

describe('multithreadingApi', () => {
  it('GET /api/modules/multithreading for the overview', async () => {
    const { calls, api } = apiAnswering(() => ({ data: overviewBefore }))

    expect(await api.getOverview()).toEqual(overviewBefore)
    expect(calls[0].method).toBe('get')
    expect(calls[0].url).toBe('/api/modules/multithreading')
  })

  it('POSTs a batch body to /nodes/{id}/batches', async () => {
    const { calls, api } = apiAnswering(() => ({ status: 202, data: batchFixture }))

    const batch = await api.submitBatch(1, { count: 100, type: 'MIXED', payloadSize: 50, extra: 'dropped' })

    expect(batch).toEqual(batchFixture)
    expect(calls[0].method).toBe('post')
    expect(calls[0].url).toBe('/api/modules/multithreading/nodes/1/batches')
    expect(JSON.parse(calls[0].data)).toEqual({ count: 100, type: 'MIXED', payloadSize: 50 })
  })

  it('POSTs /nodes/{id}/backpressure with no body', async () => {
    const { calls, api } = apiAnswering(() => ({ status: 202, data: batchFixture }))

    await api.runBackpressure(3)

    expect(calls[0].method).toBe('post')
    expect(calls[0].url).toBe('/api/modules/multithreading/nodes/3/backpressure')
    expect(calls[0].data).toBeUndefined()
  })

  it('GETs /nodes/{id}/requests with the limit', async () => {
    const { calls, api } = apiAnswering(() => ({ data: [] }))

    await api.getRequests(2, 100)

    expect(calls[0].url).toBe('/api/modules/multithreading/nodes/2/requests')
    expect(calls[0].params).toEqual({ limit: 100 })
  })

  it('turns the real 409 Module busy body into an ApiError with its detail', async () => {
    const { api } = apiAnswering(() => ({ status: 409, data: busyFixture }))

    const error = await api.runBackpressure(1).catch((err) => err)

    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(409)
    expect(error.title).toBe('Module busy')
    expect(error.detail).toBe(busyFixture.detail)
    expect(error.moduleId).toBe('multithreading')
  })

  it('turns the real 400 body into an ApiError carrying the field errors', async () => {
    const { api } = apiAnswering(() => ({ status: 400, data: validationFixture }))

    const error = await api.submitBatch(1, { count: 0, type: 'CPU_HASH', payloadSize: 5001 }).catch((err) => err)

    expect(error.status).toBe(400)
    expect(error.errors).toEqual(validationFixture.errors)
  })
})
