import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import * as useStompModule from '@/hooks/useStomp'
import { ApiError } from '@/services/api'
import overviewBefore from '@/test/fixtures/mpi/overview-before-start.json'
import overviewAfter from '@/test/fixtures/mpi/overview-after-start.json'
import overviewCrashed from '@/test/fixtures/mpi/overview-node-crashed.json'
import startFixture from '@/test/fixtures/mpi/start.json'
import startWithFailure from '@/test/fixtures/mpi/start-with-failure.json'
import history from '@/test/fixtures/mpi/collectives-history.json'
import { CLUSTER_TOPIC, MODULE_TOPIC, useMpi } from './useMpi'

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
  vi.restoreAllMocks()
})

function makeApi(overrides = {}) {
  return {
    getOverview: vi.fn().mockResolvedValue(overviewAfter),
    start: vi.fn().mockResolvedValue(startFixture),
    getHistory: vi.fn().mockResolvedValue(history),
    ...overrides,
  }
}

function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}

const emit = (topic) => act(() => { (handlers[topic] ?? []).forEach((cb) => cb({ type: 'X' })) })

describe('useMpi', () => {
  it('starts once on mount, shows the start response, then reads the history', async () => {
    const api = makeApi()
    const { result } = renderHook(() => useMpi({ api }))
    await waitFor(() => expect(result.current.history).toEqual(history))
    expect(api.start).toHaveBeenCalledTimes(1)
    expect(result.current.overview.participants.every((n) => n.collectiveEndpointExported)).toBe(true)
    expect(result.current.starting).toBe(false)
  })

  it('starts again when a cluster reset leaves up nodes unexported (one start per edge)', async () => {
    const api = makeApi()
    renderHook(() => useMpi({ api }))
    await waitFor(() => expect(api.start).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(api.getOverview).toHaveBeenCalled())

    api.getOverview.mockResolvedValue(overviewBefore)   // what the overview says right after a reset
    await emit(CLUSTER_TOPIC)
    await waitFor(() => expect(api.start).toHaveBeenCalledTimes(2))
  })

  it('does not start for a crash: a crashed node is not "up and unexported"', async () => {
    const api = makeApi({ getOverview: vi.fn().mockResolvedValue(overviewCrashed) })
    const { result } = renderHook(() => useMpi({ api }))
    await waitFor(() => expect(result.current.history).not.toBeNull())
    await emit(CLUSTER_TOPIC)
    await waitFor(() => expect(api.getOverview).toHaveBeenCalledTimes(3))
    expect(api.start).toHaveBeenCalledTimes(1)
  })

  it('does not loop when a start leaves a node unexported (start-with-failure)', async () => {
    const api = makeApi({ start: vi.fn().mockResolvedValue(startWithFailure), getOverview: vi.fn().mockResolvedValue(startWithFailure) })
    const { result } = renderHook(() => useMpi({ api }))
    await waitFor(() => expect(result.current.overview).toEqual(startWithFailure))
    await emit(MODULE_TOPIC)
    await emit(CLUSTER_TOPIC)
    await waitFor(() => expect(api.getOverview.mock.calls.length).toBeGreaterThanOrEqual(3))
    expect(api.start).toHaveBeenCalledTimes(1)
  })

  it('never retries a failed start by itself; start() tries again once', async () => {
    const failure = new ApiError({ status: 0, title: 'Backend unreachable', detail: 'Network Error' })
    const api = makeApi({ getOverview: vi.fn().mockResolvedValue(overviewBefore), start: vi.fn().mockRejectedValue(failure) })
    const { result } = renderHook(() => useMpi({ api }))
    await waitFor(() => expect(result.current.startError).toBe(failure))
    await emit(CLUSTER_TOPIC)
    await waitFor(() => expect(api.getOverview.mock.calls.length).toBeGreaterThanOrEqual(3))
    expect(api.start).toHaveBeenCalledTimes(1)

    await act(() => result.current.start())
    expect(api.start).toHaveBeenCalledTimes(2)
  })

  it('does not apply an overview read that was sent before a start answered, and reads again', async () => {
    const firstRead = deferred()
    const api = makeApi({ getOverview: vi.fn().mockReturnValueOnce(firstRead.promise).mockResolvedValue(overviewAfter) })
    const { result } = renderHook(() => useMpi({ api }))
    await waitFor(() => expect(result.current.overview).toEqual(startFixture))

    await act(async () => firstRead.resolve(overviewBefore))   // stale: says "not exported"
    await waitFor(() => expect(api.getOverview).toHaveBeenCalledTimes(2))
    expect(result.current.overview.participants.every((n) => n.collectiveEndpointExported)).toBe(true)
    expect(api.start).toHaveBeenCalledTimes(1)
  })

  it('coalesces bursts of events into one read in flight plus one queued', async () => {
    const api = makeApi()
    renderHook(() => useMpi({ api }))
    await waitFor(() => expect(api.getOverview).toHaveBeenCalledTimes(2))   // mount read and the read after start
    const pending = deferred()
    api.getOverview.mockReturnValueOnce(pending.promise)
    await emit(MODULE_TOPIC)
    await emit(MODULE_TOPIC)
    await emit(MODULE_TOPIC)
    expect(api.getOverview).toHaveBeenCalledTimes(3)
    await act(async () => pending.resolve(overviewAfter))
    await waitFor(() => expect(api.getOverview).toHaveBeenCalledTimes(4))
  })

  it('replaces the history wholesale, since ids restart after a reset', async () => {
    const api = makeApi()
    const { result } = renderHook(() => useMpi({ api }))
    await waitFor(() => expect(result.current.history).toEqual(history))
    const afterReset = [history[history.length - 1]]
    api.getHistory.mockResolvedValue(afterReset)
    await emit(MODULE_TOPIC)
    await waitFor(() => expect(result.current.history).toEqual(afterReset))
  })

  it('refreshes on a reconnect but not on the first connect', async () => {
    const api = makeApi()
    renderHook(() => useMpi({ api }))
    await waitFor(() => expect(api.getHistory).toHaveBeenCalledTimes(2))
    act(() => connectHandlers.forEach((cb) => cb()))
    expect(api.getHistory).toHaveBeenCalledTimes(2)
    act(() => connectHandlers.forEach((cb) => cb()))
    await waitFor(() => expect(api.getHistory).toHaveBeenCalledTimes(3))
  })

  it('reports a failed overview read as the error, keeping loading false', async () => {
    const failure = new ApiError({ status: 0, title: 'Backend unreachable', detail: 'Network Error' })
    const api = makeApi({ getOverview: vi.fn().mockRejectedValue(failure), start: vi.fn().mockRejectedValue(failure) })
    const { result } = renderHook(() => useMpi({ api }))
    await waitFor(() => expect(result.current.error).toBe(failure))
    expect(result.current.loading).toBe(false)
    expect(result.current.overview).toBeNull()
  })
})
