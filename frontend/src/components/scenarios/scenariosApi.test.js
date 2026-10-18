import { describe, expect, it } from 'vitest'
import { createApi } from '@/services/api'
import { createScenariosApi } from './scenariosApi'

/** An api whose axios adapter records each request and answers with `respond(config)`; no network. */
function recording(respond) {
  const calls = []
  const base = createApi({
    baseURL: 'http://localhost:8080',
    adapter: async (config) => {
      calls.push(config)
      const { status = 200, data = null } = respond(config)
      if (status >= 400) {
        const error = new Error(`Request failed with status code ${status}`)
        error.response = { status, data, statusText: '', headers: {}, config }
        throw error
      }
      return { status, data, statusText: '', headers: {}, config }
    },
  })
  return { calls, api: createScenariosApi(base) }
}

describe('scenariosApi', () => {
  it('lists the scenarios with GET /api/scenarios', async () => {
    const { calls, api } = recording(() => ({ data: [{ id: 'a' }] }))
    expect(await api.getScenarios()).toEqual([{ id: 'a' }])
    expect(calls[0].method).toBe('get')
    expect(calls[0].url).toBe('/api/scenarios')
  })

  it('starts a run with POST /api/scenarios/{id}/runs and returns the 202 body', async () => {
    const { calls, api } = recording(() => ({ status: 202, data: { runId: 3, scenarioId: 'matrix-leader-crash' } }))
    expect(await api.startRun('matrix-leader-crash')).toEqual({ runId: 3, scenarioId: 'matrix-leader-crash' })
    expect(calls[0].method).toBe('post')
    expect(calls[0].url).toBe('/api/scenarios/matrix-leader-crash/runs')
  })

  it('reads the latest run, and null for 204 No Content', async () => {
    const run = { runId: 2, state: 'RUNNING', steps: [] }
    expect(await recording(() => ({ data: run })).api.getLatestRun()).toEqual(run)
    const empty = recording(() => ({ status: 204, data: '' }))
    expect(await empty.api.getLatestRun()).toBeNull()
    expect(empty.calls[0].url).toBe('/api/scenarios/runs/latest')
  })

  it('reads one run by id', async () => {
    const { calls, api } = recording(() => ({ data: { runId: 7 } }))
    expect(await api.getRun(7)).toEqual({ runId: 7 })
    expect(calls[0].url).toBe('/api/scenarios/runs/7')
  })

  it('rejects a busy engine as an ApiError with status 409 and the ProblemDetail title', async () => {
    const { api } = recording(() => ({ status: 409, data: { title: 'Scenario busy', status: 409, reason: 'SCENARIO_BUSY', runId: 4 } }))
    await expect(api.startRun('mapreduce-incident')).rejects.toMatchObject({ status: 409, title: 'Scenario busy' })
  })
})
