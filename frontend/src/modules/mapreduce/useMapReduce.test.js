import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as useStompModule from '@/hooks/useStomp'
import { ApiError } from '@/services/api'
import overviewIdle from '@/test/fixtures/mapreduce/overview-idle.json'
import overviewAfterRuns from '@/test/fixtures/mapreduce/overview-after-runs.json'
import runAccepted from '@/test/fixtures/mapreduce/run-accepted.json'
import sampleRun from '@/test/fixtures/mapreduce/run-sample-word-count.json'
import crashRun from '@/test/fixtures/mapreduce/run-crash-retry.json'
import eventLogRun from '@/test/fixtures/mapreduce/run-event-log-event-category.json'
import history from '@/test/fixtures/mapreduce/runs-history.json'
import { CLUSTER_TOPIC, MODULE_TOPIC, POLL_INTERVAL_MS, REFRESH_ON, useMapReduce } from './useMapReduce'

function makeApi(overrides = {}) {
  return {
    getOverview: vi.fn().mockResolvedValue(overviewIdle),
    getRuns: vi.fn().mockResolvedValue([]),
    getLatestRun: vi.fn().mockResolvedValue(null),
    getRun: vi.fn().mockResolvedValue(sampleRun),
    ...overrides,
  }
}

/** Lets pending promises (mocked api calls) settle. */
const flush = () => act(async () => {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
})

describe('useMapReduce', () => {
  let handlers
  let unsubscribers
  let connectHandler

  beforeEach(() => {
    vi.useFakeTimers()
    handlers = {}
    unsubscribers = []
    connectHandler = null
    vi.spyOn(useStompModule, 'useStomp').mockReturnValue({
      status: 'connected',
      subscribe: vi.fn((destination, handler) => {
        handlers[destination] = handler
        const unsubscribe = vi.fn()
        unsubscribers.push(unsubscribe)
        return unsubscribe
      }),
      onConnect: vi.fn((handler) => {
        connectHandler = handler
        return () => {}
      }),
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('loads the overview, the history and the latest run; no run yet is the empty state, not an error', async () => {
    const api = makeApi()
    const { result } = renderHook(() => useMapReduce({ api }))
    await flush()

    expect(result.current.overview).toEqual(overviewIdle)
    expect(result.current.run).toBeNull()
    expect(result.current.error).toBeNull()
    expect(result.current.loading).toBe(false)
  })

  it('polls the shown run every 500 ms only while it is RUNNING, and stops when it completes', async () => {
    const api = makeApi({
      getRun: vi.fn().mockResolvedValueOnce(runAccepted).mockResolvedValue(sampleRun),
      getLatestRun: vi.fn().mockResolvedValueOnce(null).mockResolvedValue(sampleRun),
    })
    const { result } = renderHook(() => useMapReduce({ api }))
    await flush()
    expect(api.getRun).not.toHaveBeenCalled()

    act(() => result.current.showAccepted(runAccepted))
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS) })
    expect(api.getRun).toHaveBeenCalledTimes(1)
    expect(result.current.run.state).toBe('RUNNING')

    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS) })
    await flush()
    expect(result.current.run.state).toBe('COMPLETED')
    const calls = api.getRun.mock.calls.length

    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 4) })
    expect(api.getRun.mock.calls.length).toBe(calls)
    expect(api.getOverview.mock.calls.length).toBeGreaterThan(1)   // refreshed once the run ended
  })

  it('stops polling on unmount', async () => {
    const api = makeApi({ getRun: vi.fn().mockResolvedValue(runAccepted) })
    const { result, unmount } = renderHook(() => useMapReduce({ api }))
    await flush()
    act(() => result.current.showAccepted(runAccepted))
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS) })
    const calls = api.getRun.mock.calls.length

    unmount()
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 4) })
    expect(api.getRun.mock.calls.length).toBe(calls)
  })

  it('a 404 while polling means the run was cleared (cluster reset): polling stops and the page is told', async () => {
    const api = makeApi({ getRun: vi.fn().mockRejectedValue(new ApiError({ status: 404, title: 'Unknown run' })) })
    const { result } = renderHook(() => useMapReduce({ api }))
    await flush()
    act(() => result.current.showAccepted(runAccepted))
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS) })
    await flush()

    expect(result.current.run).toBeNull()
    expect(result.current.cleared).toBe(true)
    const calls = api.getRun.mock.calls.length
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3) })
    expect(api.getRun.mock.calls.length).toBe(calls)
  })

  it('refreshes on the four JOB_* and crash event types, on any cluster message and on reconnect, not on TASK_*', async () => {
    expect(REFRESH_ON).toEqual(['JOB_STARTED', 'JOB_COMPLETED', 'JOB_FAILED', 'WORKER_CRASH_TRIGGERED'])
    const api = makeApi()
    const { unmount } = renderHook(() => useMapReduce({ api }))
    await flush()
    const base = api.getOverview.mock.calls.length

    await act(async () => { handlers[MODULE_TOPIC]({ type: 'TASK_SENT' }) })
    expect(api.getOverview.mock.calls.length).toBe(base)
    await act(async () => { handlers[MODULE_TOPIC]({ type: 'JOB_COMPLETED' }) })
    await act(async () => { handlers[CLUSTER_TOPIC]({ type: 'NODE_CRASHED' }) })
    await act(async () => { connectHandler() })   // the first connect is not a reconnect
    await act(async () => { connectHandler() })
    await flush()
    expect(api.getOverview.mock.calls.length).toBe(base + 3)

    unmount()
    expect(unsubscribers.every((unsubscribe) => unsubscribe.mock.calls.length === 1)).toBe(true)
  })

  it('a refresh that answers late never turns a finished run back into RUNNING', async () => {
    let answerLatest
    const api = makeApi({
      getLatestRun: vi.fn()
        .mockResolvedValueOnce(null)
        .mockImplementationOnce(() => new Promise((resolve) => { answerLatest = resolve })),
      getRun: vi.fn().mockResolvedValue(sampleRun),
    })
    const { result } = renderHook(() => useMapReduce({ api }))
    await flush()
    act(() => result.current.showAccepted(runAccepted))
    // The poll sees COMPLETED and starts a refresh, whose /runs/latest answer is held back.
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS) })
    await flush()
    expect(result.current.run.state).toBe('COMPLETED')
    expect(answerLatest).toBeTypeOf('function')

    // It finally answers with an older, still-RUNNING copy of the same run.
    await act(async () => { answerLatest(runAccepted) })
    await flush()
    expect(result.current.run.state).toBe('COMPLETED')
  })

  it('compares a completed crash run with its matching earlier run, loaded by id', async () => {
    const api = makeApi({
      getOverview: vi.fn().mockResolvedValue(overviewAfterRuns),
      getRuns: vi.fn().mockResolvedValue(history),
      getLatestRun: vi.fn().mockResolvedValue(crashRun),
      getRun: vi.fn().mockResolvedValue(sampleRun),
    })
    const { result } = renderHook(() => useMapReduce({ api }))
    await flush()
    await flush()

    expect(api.getRun).toHaveBeenCalledWith(sampleRun.runId)
    expect(result.current.comparison).toMatchObject({ verdict: 'identical', baselineId: sampleRun.runId, runId: crashRun.runId })
  })

  it('never compares the live event log', async () => {
    const run = { ...eventLogRun, crash: { workerId: 3, triggered: true, nodeCrashed: true, taskType: 'MAP' } }
    const api = makeApi({ getRuns: vi.fn().mockResolvedValue(history), getLatestRun: vi.fn().mockResolvedValue(run) })
    const { result } = renderHook(() => useMapReduce({ api }))
    await flush()

    expect(api.getRun).not.toHaveBeenCalled()
    expect(result.current.comparison).toMatchObject({ verdict: 'not-comparable', reason: 'event-log' })
  })

  it('selectRun shows a history entry by id, and null follows the latest run again', async () => {
    const api = makeApi({ getRun: vi.fn().mockResolvedValue(eventLogRun) })
    const { result } = renderHook(() => useMapReduce({ api }))
    await flush()

    await act(async () => { await result.current.selectRun(eventLogRun.runId) })
    expect(result.current.run).toEqual(eventLogRun)
    expect(result.current.pinnedRunId).toBe(eventLogRun.runId)

    await act(async () => { await result.current.selectRun(null) })
    await flush()
    expect(result.current.pinnedRunId).toBeNull()
    expect(api.getLatestRun.mock.calls.length).toBeGreaterThan(1)
  })

  it('reports an overview error, and recovers on the next refresh', async () => {
    const api = makeApi({
      getOverview: vi.fn()
        .mockRejectedValueOnce(new ApiError({ status: 0, title: 'Backend unreachable', detail: 'Network Error' }))
        .mockResolvedValue(overviewIdle),
    })
    const { result } = renderHook(() => useMapReduce({ api }))
    await flush()
    expect(result.current.error?.detail).toBe('Network Error')

    await act(async () => { await result.current.refresh() })
    expect(result.current.error).toBeNull()
    expect(result.current.overview).toEqual(overviewIdle)
  })
})
