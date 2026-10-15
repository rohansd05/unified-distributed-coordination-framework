import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, renderHook } from '@testing-library/react'
import * as useStompModule from '@/hooks/useStomp'
import { ApiError, createApi } from '@/services/api'
import overviewBefore from '@/test/fixtures/faulttolerance/overview-before-start.json'
import overviewAfterRuns from '@/test/fixtures/faulttolerance/overview-after-runs.json'
import statusRunning from '@/test/fixtures/faulttolerance/status-stream-running.json'
import statusDuringFailover from '@/test/fixtures/faulttolerance/status-during-failover.json'
import statusAfterDemotion from '@/test/fixtures/faulttolerance/status-after-demotion.json'
import runSyncRestored from '@/test/fixtures/faulttolerance/run-sync-restored.json'
import runSyncRecovered from '@/test/fixtures/faulttolerance/run-sync-recovered.json'
import crashFixture from '@/test/fixtures/faulttolerance/crash-primary-sync.json'
import streamStart from '@/test/fixtures/faulttolerance/stream-start-sync.json'
import noRunYet from '@/test/fixtures/faulttolerance/error-404-no-run-yet.json'
import limitReached from '@/test/fixtures/faulttolerance/error-409-limit-reached.json'
import busy from '@/test/fixtures/faulttolerance/error-409-busy.json'
import runInProgress from '@/test/fixtures/faulttolerance/synthetic/run-in-progress.json'
import runRecovering from '@/test/fixtures/faulttolerance/synthetic/run-recovering.json'
import statusRejoinStalled from '@/test/fixtures/faulttolerance/synthetic/status-rejoin-stalled.json'
import syntheticEvents from '@/test/fixtures/faulttolerance/synthetic/events-synthetic.json'
import { createFaultToleranceApi } from './faultToleranceApi'
import {
  CLUSTER_TOPIC,
  FALLBACK_POLL_INTERVAL_MS,
  MODULE_TOPIC,
  STATUS_POLL_INTERVAL_MS,
  useFaultTolerance,
} from './useFaultTolerance'

let handlers
let connectHandlers
let stomp

beforeEach(() => {
  vi.useFakeTimers()
  handlers = {}
  connectHandlers = []
  stomp = {
    status: 'connected',
    subscribe: (topic, callback) => {
      (handlers[topic] ??= []).push(callback)
      return () => { handlers[topic] = handlers[topic].filter((cb) => cb !== callback) }
    },
    onConnect: (callback) => {
      connectHandlers.push(callback)
      return () => {}
    },
  }
  vi.spyOn(useStompModule, 'useStomp').mockImplementation(() => stomp)
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.restoreAllMocks()
})

const overviewWith = (current, latestRun = null) => ({ ...overviewBefore, current, latestRun })

function makeApi(overview = overviewBefore, overrides = {}) {
  return {
    getOverview: vi.fn().mockResolvedValue(overview),
    getStatus: vi.fn().mockResolvedValue(overview.current),
    getLatestRun: vi.fn().mockResolvedValue(overview.latestRun),
    start: vi.fn(),
    startStream: vi.fn().mockResolvedValue(streamStart),
    stopStream: vi.fn(),
    setMode: vi.fn(),
    crashPrimary: vi.fn().mockResolvedValue(crashFixture),
    recover: vi.fn(),
    ...overrides,
  }
}

/** An ApiError exactly as services/api.js builds it from a real ProblemDetail body. */
function problem(body) {
  return new ApiError({ status: body.status, title: body.title, detail: body.detail, moduleId: body.moduleId ?? null,
    errors: body.errors ?? null, originalError: { response: { status: body.status, data: body } } })
}

/** An ApiError as services/api.js builds it from an axios timeout of the shared 10000 ms. */
function timeoutError() {
  return new ApiError({ status: 0, title: 'Backend unreachable', detail: 'timeout of 10000ms exceeded',
    originalError: { code: 'ECONNABORTED', config: { timeout: 10000 } } })
}

async function flush(ms = 0) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

async function mount(api) {
  const hook = renderHook(() => useFaultTolerance({ api }))
  await flush()
  return hook
}

describe('useFaultTolerance loading', () => {
  it('loads status, run, settings, glossary and notes from one overview read, and polls nothing while idle', async () => {
    const api = makeApi()
    const { result } = await mount(api)
    expect(api.getOverview).toHaveBeenCalledTimes(1)
    expect(result.current.loading).toBe(false)
    expect(result.current.status).toEqual(overviewBefore.current)
    expect(result.current.run).toBeNull()
    expect(result.current.overview.settings).toEqual(overviewBefore.settings)
    await flush(10 * STATUS_POLL_INTERVAL_MS)
    expect(api.getStatus).not.toHaveBeenCalled()
    expect(api.getLatestRun).not.toHaveBeenCalled()
  })

  it('reads a 404 from runs/latest as the empty state, not an error (real client, real 404 body)', async () => {
    const base = createApi({
      baseURL: 'http://localhost:8080',
      adapter: async (config) => {
        if (config.url.endsWith('/runs/latest')) {
          const error = new Error('Request failed with status code 404')
          error.response = { status: 404, data: noRunYet, statusText: '', headers: {}, config }
          throw error
        }
        const data = config.url.endsWith('/status') ? overviewAfterRuns.current : overviewAfterRuns
        return { status: 200, data, statusText: 'OK', headers: {}, config }
      },
    })
    const { result } = await mount(createFaultToleranceApi(base))
    expect(result.current.run).toEqual(overviewAfterRuns.latestRun)
    await act(async () => {
      handlers[MODULE_TOPIC].forEach((cb) => cb({ type: 'STREAM_STOPPED' }))
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(result.current.run).toBeNull()
    expect(result.current.error).toBeNull()
  })

  it('reports a failed first load, and loads again on the next trigger', async () => {
    const api = makeApi(overviewBefore, { getOverview: vi.fn().mockRejectedValueOnce(problem(busy)).mockResolvedValue(overviewBefore) })
    const { result } = await mount(api)
    expect(result.current.error).toBeTruthy()
    expect(result.current.status).toBeNull()
    await act(async () => {
      result.current.refresh()
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(api.getOverview).toHaveBeenCalledTimes(2)
    expect(result.current.status).toEqual(overviewBefore.current)
  })
})

describe('useFaultTolerance polling', () => {
  it('polls only the status every 500 ms while the stream runs, and stops when it stops', async () => {
    const api = makeApi(overviewWith(statusRunning))
    await mount(api)
    await flush(STATUS_POLL_INTERVAL_MS - 1)
    expect(api.getStatus).not.toHaveBeenCalled()
    await flush(1)
    expect(api.getStatus).toHaveBeenCalledTimes(1)
    expect(api.getLatestRun).not.toHaveBeenCalled()
    api.getStatus.mockResolvedValue(overviewAfterRuns.current)
    await flush(STATUS_POLL_INTERVAL_MS)
    expect(api.getStatus).toHaveBeenCalledTimes(2)
    await flush(10 * STATUS_POLL_INTERVAL_MS)
    expect(api.getStatus).toHaveBeenCalledTimes(2)
  })

  it('C5: mounted mid-failover (synthetic run-in-progress), it restores the run and polls status and run from the overview alone', async () => {
    const api = makeApi(overviewWith(statusDuringFailover, runInProgress))
    const { result } = await mount(api)
    expect(result.current.run).toEqual(runInProgress)
    await flush(STATUS_POLL_INTERVAL_MS)
    expect(api.getStatus).toHaveBeenCalledTimes(1)
    expect(api.getLatestRun).toHaveBeenCalledTimes(1)
    api.getStatus.mockResolvedValue(statusAfterDemotion)
    api.getLatestRun.mockResolvedValue(runSyncRestored)
    await flush(STATUS_POLL_INTERVAL_MS)
    expect(result.current.run).toEqual(runSyncRestored)
    // Restored: the stream still runs, so the status is still polled, but the run no longer.
    await flush(4 * STATUS_POLL_INTERVAL_MS)
    expect(api.getStatus).toHaveBeenCalledTimes(6)
    expect(api.getLatestRun).toHaveBeenCalledTimes(2)
  })

  it('polls the run while the old primary rejoins (synthetic run-recovering) until recoveryMillis is read from the run', async () => {
    const api = makeApi(overviewWith(statusRejoinStalled, runRecovering))
    const { result } = await mount(api)
    await flush(STATUS_POLL_INTERVAL_MS)
    expect(api.getLatestRun).toHaveBeenCalledTimes(1)
    api.getLatestRun.mockResolvedValue(runSyncRecovered)
    await flush(STATUS_POLL_INTERVAL_MS)
    expect(result.current.run.measurements.recoveryMillis).toBe(runSyncRecovered.measurements.recoveryMillis)
    await flush(4 * STATUS_POLL_INTERVAL_MS)
    expect(api.getLatestRun).toHaveBeenCalledTimes(2)
  })

  it('falls back to a slower poll of status and run while the WebSocket is down', async () => {
    stomp.status = 'reconnecting'
    const api = makeApi()
    await mount(api)
    await flush(FALLBACK_POLL_INTERVAL_MS - 1)
    expect(api.getStatus).not.toHaveBeenCalled()
    await flush(1)
    expect(api.getStatus).toHaveBeenCalledTimes(1)
    expect(api.getLatestRun).toHaveBeenCalledTimes(1)
  })
})

describe('useFaultTolerance live refresh', () => {
  it('refreshes status and run on module events (synthetic ROLE_QUERY_FAILED and NO_CANDIDATE) and cluster events, coalesced', async () => {
    let release
    const api = makeApi()
    await mount(api)
    api.getStatus.mockImplementationOnce(() => new Promise((resolve) => { release = () => resolve(overviewBefore.current) }))
    await act(async () => {
      syntheticEvents.forEach((event) => handlers[MODULE_TOPIC].forEach((cb) => cb(event)))
      handlers[CLUSTER_TOPIC].forEach((cb) => cb({ type: 'NODE_CRASHED' }))
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(api.getStatus).toHaveBeenCalledTimes(1)
    await act(async () => {
      release()
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(api.getStatus).toHaveBeenCalledTimes(2)
    expect(api.getLatestRun).toHaveBeenCalledTimes(2)
  })

  it('reads the overview again after a STOMP reconnect, not on the first connect', async () => {
    const api = makeApi()
    await mount(api)
    await act(async () => {
      connectHandlers.forEach((cb) => cb())
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(api.getOverview).toHaveBeenCalledTimes(1)
    await act(async () => {
      connectHandlers.forEach((cb) => cb())
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(api.getOverview).toHaveBeenCalledTimes(2)
  })
})

describe('useFaultTolerance actions', () => {
  it('applies the status a crash returns, then reads again', async () => {
    const api = makeApi(overviewWith(statusRunning))
    const { result } = await mount(api)
    let answer
    await act(async () => {
      answer = await result.current.crashPrimary()
    })
    expect(answer).toEqual(crashFixture)
    expect(api.getStatus).toHaveBeenCalled()
    expect(result.current.problem).toBeNull()
  })

  it('applies a stream response to the stream only', async () => {
    const api = makeApi(overviewWith(overviewAfterRuns.current), { getStatus: vi.fn(() => new Promise(() => {})) })
    const { result } = await mount(api)
    await act(async () => {
      await result.current.startStream(4)
    })
    expect(api.startStream).toHaveBeenCalledWith({ mode: overviewAfterRuns.current.stream.mode, ratePerSecond: 4 })
    expect(result.current.status.stream).toEqual(streamStart)
    expect(result.current.status.nodes).toEqual(overviewAfterRuns.current.nodes)
  })

  it('runs one action at a time', async () => {
    let release
    const api = makeApi(overviewWith(statusRunning), { crashPrimary: vi.fn(() => new Promise((resolve) => { release = resolve })) })
    const { result } = await mount(api)
    let second
    await act(async () => {
      result.current.crashPrimary()
      second = await result.current.crashPrimary()
    })
    expect(second).toBeNull()
    expect(result.current.locked).toBe(true)
    expect(result.current.pending).toBe('Crashing the primary...')
    await act(async () => {
      release(crashFixture)
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(api.crashPrimary).toHaveBeenCalledTimes(1)
    expect(result.current.locked).toBe(false)
  })

  it('maps a 409 UPDATE_LIMIT_REACHED to its message with the reset link', async () => {
    const api = makeApi(overviewWith(overviewAfterRuns.current), { startStream: vi.fn().mockRejectedValue(problem(limitReached)) })
    const { result } = await mount(api)
    await act(async () => {
      await result.current.startStream(4)
    })
    expect(result.current.problem).toMatchObject({ reason: 'UPDATE_LIMIT_REACHED', resetLink: true, message: limitReached.detail })
  })

  it('maps a 409 busy to the calm busy kind and does not lock', async () => {
    const api = makeApi(overviewWith(statusRunning), { crashPrimary: vi.fn().mockRejectedValue(problem(busy)) })
    const { result } = await mount(api)
    await act(async () => {
      await result.current.crashPrimary()
    })
    expect(result.current.problem.kind).toBe('busy')
    expect(result.current.locked).toBe(false)
  })

  it('C2: after a crash timeout every action stays locked while the status says busy, then unlocks and reads everything again', async () => {
    const busyStatus = { ...statusRunning, status: 'BUSY', actionInProgress: 'Crash of the primary' }
    const api = makeApi(overviewWith(statusRunning), {
      crashPrimary: vi.fn().mockRejectedValue(timeoutError()),
      getStatus: vi.fn().mockResolvedValue(busyStatus),
    })
    const { result } = await mount(api)
    await act(async () => {
      await result.current.crashPrimary()
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(result.current.problem.message).toBe('The request timed out after 10 s. The crash may still have happened; reading the status again.')
    expect(result.current.awaitingIdle).toBe(true)
    expect(result.current.locked).toBe(true)
    await flush(3 * STATUS_POLL_INTERVAL_MS)
    expect(result.current.locked).toBe(true)
    const runReadsWhileBusy = api.getLatestRun.mock.calls.length
    api.getStatus.mockResolvedValue(statusDuringFailover)
    await flush(STATUS_POLL_INTERVAL_MS)
    expect(result.current.locked).toBe(false)
    expect(result.current.awaitingIdle).toBe(false)
    expect(api.getLatestRun.mock.calls.length).toBeGreaterThan(runReadsWhileBusy)
  })

  it('C2: a recover timeout is never reported as a failure', async () => {
    const api = makeApi(overviewWith(statusRunning), { recover: vi.fn().mockRejectedValue(timeoutError()) })
    const { result } = await mount(api)
    await act(async () => {
      await result.current.recover()
    })
    expect(result.current.problem.message).toBe('The request timed out after 10 s. The recovery may still have happened; reading the status again.')
  })

  it('updates nothing after unmount', async () => {
    let release
    const api = makeApi(overviewWith(statusRunning), { crashPrimary: vi.fn(() => new Promise((resolve) => { release = resolve })) })
    const { result, unmount } = await mount(api)
    const status = result.current.status
    act(() => {
      result.current.crashPrimary()
    })
    unmount()
    await act(async () => {
      release(crashFixture)
      await vi.advanceTimersByTimeAsync(5 * STATUS_POLL_INTERVAL_MS)
    })
    expect(result.current.status).toBe(status)
  })
})
