import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import * as useStompModule from '@/hooks/useStomp'
import { ApiError } from '@/services/api'
import overviewBefore from '@/test/fixtures/matrix/overview-before-start.json'
import overviewAfter from '@/test/fixtures/matrix/overview-after-start.json'
import startFixture from '@/test/fixtures/matrix/start.json'
import startWithFailure from '@/test/fixtures/matrix/start-with-failure.json'
import runsHistory from '@/test/fixtures/matrix/runs-history.json'
import series from '@/test/fixtures/matrix/series.json'
import complete50 from '@/test/fixtures/matrix/run-complete-50.json'
import refused from '@/test/fixtures/matrix/run-refused.json'
import busy from '@/test/fixtures/matrix/error-409-busy.json'
import { BUSY_POLL_INTERVAL_MS, CLUSTER_TOPIC, MODULE_TOPIC, useMatrix } from './useMatrix'

let handlers
let connectHandlers

beforeEach(() => {
  handlers = {}
  connectHandlers = []
  const stomp = {
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

const copy = (value) => JSON.parse(JSON.stringify(value))
const busyOverview = { ...copy(overviewAfter), status: 'BUSY', actionInProgress: busy.actionInProgress }

function makeApi(overrides = {}) {
  return {
    getOverview: vi.fn().mockResolvedValue(overviewAfter),
    start: vi.fn().mockResolvedValue(startFixture),
    getRuns: vi.fn().mockResolvedValue(runsHistory),
    getSeries: vi.fn().mockResolvedValue(series),
    runMatrix: vi.fn().mockResolvedValue(complete50),
    ...overrides,
  }
}

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function problem(body) {
  return new ApiError({ status: body.status, title: body.title, detail: body.detail, nodeId: body.nodeId ?? null,
    errors: body.errors ?? null, originalError: { response: { status: body.status, data: body } } })
}

const timeoutError = () => new ApiError({ status: 0, title: 'timeout', detail: 'timeout',
  originalError: { code: 'ECONNABORTED', config: { timeout: 29001 } } })

const emit = (topic) => act(() => { (handlers[topic] ?? []).forEach((cb) => cb({ type: 'X' })) })

/** waitFor's twin on setTimeout, so it still works while setInterval is faked for the polling tests. */
async function until(check) {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    try {
      check()
      return
    } catch {
      await act(() => new Promise((resolve) => setTimeout(resolve, 5)))
    }
  }
  check()
}

async function mounted(api) {
  const hook = renderHook(() => useMatrix({ api }))
  await until(() => expect(api.start).toHaveBeenCalledTimes(1))
  await until(() => expect(hook.result.current.starting).toBe(false))
  return hook
}

describe('useMatrix: start and reads', () => {
  it('reads the overview first, then starts once with it (for the served timeout), then has runs and series', async () => {
    const api = makeApi({ getOverview: vi.fn().mockResolvedValue(overviewBefore) })
    const { result } = await mounted(api)
    expect(api.start).toHaveBeenCalledWith(overviewBefore)
    expect(api.getOverview.mock.invocationCallOrder[0]).toBeLessThan(api.start.mock.invocationCallOrder[0])
    await waitFor(() => expect(result.current.series).toEqual(series))
    expect(result.current.runs).toEqual(runsHistory)
  })

  it('starts with no overview (so the fallback timeout) only when the overview could not be read', async () => {
    const api = makeApi({ getOverview: vi.fn().mockRejectedValue(new ApiError({ status: 0 })) })
    await mounted(api)
    expect(api.start).toHaveBeenCalledWith(null)
  })

  it('starts again on the edge to "a node needs a start" (a cluster reset), once per edge', async () => {
    const api = makeApi()
    const { result } = await mounted(api)
    await waitFor(() => expect(result.current.overview).toEqual(overviewAfter))
    api.getOverview.mockResolvedValue(overviewBefore)
    emit(CLUSTER_TOPIC)
    await waitFor(() => expect(api.start).toHaveBeenCalledTimes(2))
    api.start.mockResolvedValue(startWithFailure)   // the condition stays true: no new edge
    emit(MODULE_TOPIC)
    emit(MODULE_TOPIC)
    await waitFor(() => expect(api.getOverview.mock.calls.length).toBeGreaterThanOrEqual(4))
    expect(api.start).toHaveBeenCalledTimes(2)
  })

  it('does not retry a failed start by itself; start() tries again', async () => {
    const api = makeApi({ getOverview: vi.fn().mockResolvedValue(overviewBefore), start: vi.fn().mockRejectedValue(problem(busy)) })
    const { result } = await mounted(api)
    expect(result.current.startError).toBeTruthy()
    emit(MODULE_TOPIC)
    await waitFor(() => expect(api.getOverview).toHaveBeenCalledTimes(3))
    expect(api.start).toHaveBeenCalledTimes(1)
    api.start.mockResolvedValue(startFixture)
    api.getOverview.mockResolvedValue(overviewAfter)   // the start worked, so later reads show every node exported
    await act(() => result.current.start())
    expect(api.start).toHaveBeenCalledTimes(2)
    expect(result.current.startError).toBeNull()
  })

  it('coalesces refreshes: one read in flight and at most one queued', async () => {
    const api = makeApi()
    await mounted(api)
    await waitFor(() => expect(api.getSeries).toHaveBeenCalledTimes(2))
    const gate = deferred()
    api.getOverview.mockImplementation(() => gate.promise)
    emit(MODULE_TOPIC)
    emit(MODULE_TOPIC)
    emit(CLUSTER_TOPIC)
    expect(api.getOverview).toHaveBeenCalledTimes(3)
    api.getOverview.mockResolvedValue(overviewAfter)
    await act(async () => gate.resolve(overviewAfter))
    await waitFor(() => expect(api.getOverview).toHaveBeenCalledTimes(4))
  })
})

describe('useMatrix: runs', () => {
  it('runs one size with the current overview, keeps the run and reads again', async () => {
    const api = makeApi()
    const { result } = await mounted(api)
    const reads = api.getRuns.mock.calls.length
    await act(() => result.current.run('50', ''))
    expect(api.runMatrix).toHaveBeenCalledWith({ size: 50 }, overviewAfter)
    expect(result.current.lastRun).toEqual(complete50)
    expect(result.current.locked).toBe(false)
    await waitFor(() => expect(api.getRuns.mock.calls.length).toBeGreaterThan(reads))
  })

  it('reports a 409 as a problem and stays unlocked', async () => {
    const api = makeApi({ runMatrix: vi.fn().mockRejectedValue(problem(busy)) })
    const { result } = await mounted(api)
    await act(() => result.current.run('50', ''))
    expect(result.current.problem.kind).toBe('busy')
    expect(result.current.awaitingIdle).toBe(false)
  })

  it('after a timeout stays locked and polls until a later read is no longer busy', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const api = makeApi({ runMatrix: vi.fn().mockRejectedValue(timeoutError()) })
    const { result } = await mounted(api)
    api.getOverview.mockResolvedValue(busyOverview)
    await act(() => result.current.run('316', ''))
    expect(result.current.problem.kind).toBe('timeout')
    await until(() => expect(result.current.overview.status).toBe('BUSY'))
    expect(result.current.awaitingIdle).toBe(true)
    expect(result.current.locked).toBe(true)
    const before = api.getOverview.mock.calls.length
    await act(async () => { vi.advanceTimersByTime(BUSY_POLL_INTERVAL_MS) })
    await until(() => expect(api.getOverview.mock.calls.length).toBe(before + 1))
    expect(result.current.locked).toBe(true)
    api.getOverview.mockResolvedValue(overviewAfter)
    await act(async () => { vi.advanceTimersByTime(BUSY_POLL_INTERVAL_MS) })
    await until(() => expect(result.current.awaitingIdle).toBe(false))
    expect(result.current.locked).toBe(false)
  })

  it('polls while the module is busy elsewhere and stops when it is idle', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const api = makeApi({ getOverview: vi.fn().mockResolvedValue(busyOverview) })
    const { result } = await mounted(api)
    await until(() => expect(result.current.overview.status).toBe('BUSY'))
    expect(result.current.locked).toBe(false)
    api.getOverview.mockResolvedValue(overviewAfter)
    const before = api.getOverview.mock.calls.length
    await act(async () => { vi.advanceTimersByTime(BUSY_POLL_INTERVAL_MS) })
    await until(() => expect(result.current.overview.status).toBe('RUNNING'))
    expect(api.getOverview.mock.calls.length).toBe(before + 1)
    await act(async () => { vi.advanceTimersByTime(BUSY_POLL_INTERVAL_MS * 3) })
    expect(api.getOverview.mock.calls.length).toBe(before + 1)
  })
})

describe('useMatrix: run all sizes', () => {
  it('sends the served series sizes one at a time, each after the previous answered', async () => {
    const gates = overviewAfter.limits.seriesSizes.map(() => deferred())
    let call = 0
    const api = makeApi({ runMatrix: vi.fn().mockImplementation(() => gates[call++].promise) })
    const { result } = await mounted(api)
    let done
    act(() => { done = result.current.runAll('') })
    for (let i = 0; i < gates.length; i += 1) {
      await waitFor(() => expect(api.runMatrix).toHaveBeenCalledTimes(i + 1))
      expect(result.current.progress).toEqual({ index: i + 1, total: gates.length, size: overviewAfter.limits.seriesSizes[i] })
      expect(result.current.locked).toBe(true)
      await act(async () => gates[i].resolve(i === 1 ? refused : complete50))   // a REFUSED 200 does not stop the series
    }
    await act(() => done)
    expect(api.runMatrix.mock.calls.map(([body]) => body.size)).toEqual(overviewAfter.limits.seriesSizes)
    expect(result.current.progress).toBeNull()
    expect(result.current.runAllNote).toBe(`Ran all ${gates.length} sizes, one after another.`)
  })

  it('stops after the run in progress when asked, and sends no more', async () => {
    const gate = deferred()
    const api = makeApi({ runMatrix: vi.fn().mockImplementation(() => gate.promise) })
    const { result } = await mounted(api)
    let done
    act(() => { done = result.current.runAll('2') })
    await waitFor(() => expect(api.runMatrix).toHaveBeenCalledTimes(1))
    expect(api.runMatrix.mock.calls[0][0]).toEqual({ size: overviewAfter.limits.seriesSizes[0], rootNode: 2 })
    act(() => result.current.stopAfterCurrent())
    expect(result.current.stopRequested).toBe(true)
    await act(async () => gate.resolve(complete50))
    await act(() => done)
    expect(api.runMatrix).toHaveBeenCalledTimes(1)
    expect(result.current.runAllNote).toContain(`Stopped after size ${overviewAfter.limits.seriesSizes[0]}, as asked.`)
    expect(result.current.stopRequested).toBe(false)
  })

  it('stops at the first error and says which size', async () => {
    const api = makeApi({ runMatrix: vi.fn().mockResolvedValueOnce(complete50).mockRejectedValueOnce(problem(busy)) })
    const { result } = await mounted(api)
    await act(() => result.current.runAll(''))
    expect(api.runMatrix).toHaveBeenCalledTimes(2)
    expect(result.current.runAllNote).toContain(`Stopped at size ${overviewAfter.limits.seriesSizes[1]}`)
    expect(result.current.problem.kind).toBe('busy')
  })

  it('updates nothing after unmount', async () => {
    const gate = deferred()
    const api = makeApi({ runMatrix: vi.fn().mockImplementation(() => gate.promise) })
    const { result, unmount } = await mounted(api)
    act(() => { result.current.runAll('') })
    await waitFor(() => expect(api.runMatrix).toHaveBeenCalledTimes(1))
    unmount()
    await act(async () => gate.resolve(complete50))
    expect(api.runMatrix).toHaveBeenCalledTimes(1)
  })
})
