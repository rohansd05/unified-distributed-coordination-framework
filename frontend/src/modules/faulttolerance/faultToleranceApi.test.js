import { describe, expect, it } from 'vitest'
import { createApi } from '@/services/api'
import overviewBefore from '@/test/fixtures/faulttolerance/overview-before-start.json'
import statusRunning from '@/test/fixtures/faulttolerance/status-stream-running.json'
import runRestored from '@/test/fixtures/faulttolerance/run-sync-restored.json'
import startFixture from '@/test/fixtures/faulttolerance/start.json'
import streamStart from '@/test/fixtures/faulttolerance/stream-start-sync.json'
import streamStop from '@/test/fixtures/faulttolerance/stream-stop.json'
import modeAsync from '@/test/fixtures/faulttolerance/mode-async.json'
import crash from '@/test/fixtures/faulttolerance/crash-primary-sync.json'
import recover from '@/test/fixtures/faulttolerance/recover.json'
import noRunYet from '@/test/fixtures/faulttolerance/error-404-no-run-yet.json'
import busy from '@/test/fixtures/faulttolerance/error-409-busy.json'
import noServingPrimary from '@/test/fixtures/faulttolerance/error-409-no-serving-primary.json'
import failoverInProgress from '@/test/fixtures/faulttolerance/error-409-failover-in-progress.json'
import nothingToRecover from '@/test/fixtures/faulttolerance/error-409-nothing-to-recover.json'
import streamRunning from '@/test/fixtures/faulttolerance/error-409-stream-running.json'
import streamNotRunning from '@/test/fixtures/faulttolerance/error-409-stream-not-running.json'
import limitReached from '@/test/fixtures/faulttolerance/error-409-limit-reached.json'
import validation from '@/test/fixtures/faulttolerance/error-400-validation.json'
import rateTooHigh from '@/test/fixtures/faulttolerance/error-400-rate-too-high.json'
import unreadable from '@/test/fixtures/faulttolerance/error-400-unreadable.json'
import modeMissing from '@/test/fixtures/faulttolerance/error-400-mode-missing.json'
import { createFaultToleranceApi, describeFaultProblem } from './faultToleranceApi'

const BASE = '/api/modules/faulttolerance'

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
  return { calls, api: createFaultToleranceApi(base) }
}

/** The ApiError a real ProblemDetail body becomes through the shared client. */
async function errorFor(body, call = (api) => api.crashPrimary()) {
  const { api } = apiAnswering(() => ({ status: body.status, data: body }))
  return call(api).catch((err) => err)
}

function timingOut() {
  const base = createApi({
    baseURL: 'http://localhost:8080',
    adapter: async (config) => {
      const error = new Error(`timeout of ${config.timeout}ms exceeded`)
      error.code = 'ECONNABORTED'
      error.config = config
      throw error
    },
  })
  return createFaultToleranceApi(base)
}

describe('faultToleranceApi requests', () => {
  it('uses the right verb, path and body for every call, all on the shared 10000 ms timeout', async () => {
    const answers = {
      [`get ${BASE}`]: overviewBefore,
      [`get ${BASE}/status`]: statusRunning,
      [`get ${BASE}/runs/latest`]: runRestored,
      [`post ${BASE}/start`]: startFixture,
      [`post ${BASE}/stream/start`]: streamStart,
      [`post ${BASE}/stream/stop`]: streamStop,
      [`put ${BASE}/mode`]: modeAsync,
      [`post ${BASE}/crash-primary`]: crash,
      [`post ${BASE}/recover`]: recover,
    }
    const { calls, api } = apiAnswering((config) => ({ data: answers[`${config.method} ${config.url}`] }))
    expect(await api.getOverview()).toEqual(overviewBefore)
    expect(await api.getStatus()).toEqual(statusRunning)
    expect(await api.getLatestRun()).toEqual(runRestored)
    expect(await api.start()).toEqual(startFixture)
    expect(await api.startStream({ mode: 'SYNCHRONOUS', ratePerSecond: 4 })).toEqual(streamStart)
    expect(await api.stopStream()).toEqual(streamStop)
    expect(await api.setMode('ASYNCHRONOUS')).toEqual(modeAsync)
    expect(await api.crashPrimary()).toEqual(crash)
    expect(await api.recover()).toEqual(recover)
    expect(calls.map((c) => [c.method, c.url])).toEqual(Object.keys(answers).map((key) => key.split(' ')))
    expect(calls.every((c) => c.timeout === 10000)).toBe(true)
    expect(JSON.parse(calls[4].data)).toEqual({ mode: 'SYNCHRONOUS', ratePerSecond: 4 })
    expect(JSON.parse(calls[6].data)).toEqual({ mode: 'ASYNCHRONOUS' })
  })

  it('reads a 404 from runs/latest as "no run yet" (null), not as an error', async () => {
    const { api } = apiAnswering(() => ({ status: 404, data: noRunYet }))
    expect(await api.getLatestRun()).toBeNull()
  })

  it('still rejects any other runs/latest failure', async () => {
    const { api } = apiAnswering(() => ({ status: 500, data: { title: 'Internal Server Error', status: 500 } }))
    await expect(api.getLatestRun()).rejects.toMatchObject({ status: 500 })
  })
})

describe('describeFaultProblem', () => {
  it('returns null without an error', () => {
    expect(describeFaultProblem(null)).toBeNull()
  })

  it('keeps 409 busy as the shared busy kind, with the action in progress', async () => {
    const problem = describeFaultProblem(await errorFor(busy), 'crash')
    expect(problem.kind).toBe('busy')
    expect(problem.actionInProgress).toBe('Start of the fault tolerance experiment')
  })

  it.each([
    ['NO_SERVING_PRIMARY (not started)', noServingPrimary, 'No primary to crash right now. Experiment 8 has not started yet'],
    ['NO_SERVING_PRIMARY (failover in progress)', failoverInProgress, 'No primary to crash right now. A failover is in progress'],
    ['NOTHING_TO_RECOVER', nothingToRecover, 'Nothing to recover. No primary has failed since the last reset'],
    ['STREAM_RUNNING', streamRunning, 'The update stream is already running.'],
    ['STREAM_NOT_RUNNING', streamNotRunning, 'The update stream is not running, so there is nothing to stop.'],
  ])('maps %s to its own message', async (_, body, start) => {
    const problem = describeFaultProblem(await errorFor(body))
    expect(problem.kind).toBe('conflict')
    expect(problem.reason).toBe(body.reason)
    expect(problem.message.startsWith(start)).toBe(true)
    expect(problem.resetLink).toBe(false)
  })

  it('shows UPDATE_LIMIT_REACHED with the backend text verbatim and the reset link', async () => {
    const problem = describeFaultProblem(await errorFor(limitReached, (api) => api.startStream({ mode: 'SYNCHRONOUS', ratePerSecond: 4 })), 'stream')
    expect(problem.reason).toBe('UPDATE_LIMIT_REACHED')
    expect(problem.message).toBe(limitReached.detail)
    expect(problem.resetLink).toBe(true)
  })

  it.each([
    ['missing fields', validation, { mode: 'must not be null', ratePerSecond: 'must not be null' }],
    ['rate too high', rateTooHigh, rateTooHigh.errors],
    ['mode missing', modeMissing, modeMissing.errors],
  ])('lists the 400 field errors (%s)', async (_, body, errors) => {
    const problem = describeFaultProblem(await errorFor(body))
    expect(problem.kind).toBe('validation')
    expect(problem.fieldErrors).toEqual(errors)
    expect(problem.message).toBe('The backend refused the request:')
  })

  it('says an unreadable body could not be read', async () => {
    const problem = describeFaultProblem(await errorFor(unreadable))
    expect(problem.kind).toBe('bad-request')
    expect(problem.message).toContain('Failed to read request')
  })

  it.each([
    ['crash', (api) => api.crashPrimary(), 'The request timed out after 10 s. The crash may still have happened; reading the status again.'],
    ['recover', (api) => api.recover(), 'The request timed out after 10 s. The recovery may still have happened; reading the status again.'],
  ])('never calls a timeout of %s a failure', async (action, call, message) => {
    const problem = describeFaultProblem(await call(timingOut()).catch((e) => e), action)
    expect(problem.kind).toBe('timeout')
    expect(problem.message).toBe(message)
    expect(problem.message).not.toMatch(/fail/i)
  })
})
