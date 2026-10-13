import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import * as useStompModule from '@/hooks/useStomp'
import { ApiError } from '@/services/api'
import overviewBefore from '@/test/fixtures/rmi/overview-before-start.json'
import overviewAfter from '@/test/fixtures/rmi/overview-after-start.json'
import overviewCrashed from '@/test/fixtures/rmi/overview-node-crashed.json'
import startFixture from '@/test/fixtures/rmi/start.json'
import registriesAllUp from '@/test/fixtures/rmi/registries-all-up.json'
import registriesBefore from '@/test/fixtures/rmi/registries-before-start.json'
import callsHistory from '@/test/fixtures/rmi/calls-history.json'
import { CLUSTER_TOPIC, MODULE_TOPIC, useRmi } from './useRmi'

let handlers
let connectHandlers
let stomp

beforeEach(() => {
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
  vi.restoreAllMocks()
})

function makeApi(overrides = {}) {
  return {
    getOverview: vi.fn().mockResolvedValue(overviewAfter),
    start: vi.fn().mockResolvedValue(startFixture),
    getHistory: vi.fn().mockResolvedValue(callsHistory),
    getRegistries: vi.fn().mockResolvedValue(registriesAllUp),
    ...overrides,
  }
}

function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}

const emit = (topic) => act(() => { (handlers[topic] ?? []).forEach((cb) => cb({ type: 'X' })) })

describe('useRmi', () => {
  it('starts RMI once on mount and shows the start response, then reads history and registries', async () => {
    const api = makeApi()
    const { result } = renderHook(() => useRmi({ api }))
    await waitFor(() => expect(result.current.registries).toEqual(registriesAllUp))
    expect(api.start).toHaveBeenCalledTimes(1)
    expect(result.current.overview.nodes.every((n) => n.state === 'EXPORTED')).toBe(true)
    expect(result.current.history).toEqual(callsHistory)
    expect(result.current.starting).toBe(false)
  })

  it('starts again when a cluster reset leaves up nodes not started (one start per trigger)', async () => {
    const api = makeApi()
    renderHook(() => useRmi({ api }))
    await waitFor(() => expect(api.start).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(api.getOverview).toHaveBeenCalled())

    api.getOverview.mockResolvedValue(overviewBefore)   // what the overview says right after a reset
    api.getRegistries.mockResolvedValue(registriesBefore)
    await emit(CLUSTER_TOPIC)
    await waitFor(() => expect(api.start).toHaveBeenCalledTimes(2))
  })

  it('does not start for a crash: a crashed node is not "up and not started"', async () => {
    const api = makeApi({ getOverview: vi.fn().mockResolvedValue(overviewCrashed) })
    const { result } = renderHook(() => useRmi({ api }))
    await waitFor(() => expect(result.current.registries).not.toBeNull())
    await emit(CLUSTER_TOPIC)
    await waitFor(() => expect(api.getOverview).toHaveBeenCalledTimes(3))
    expect(api.start).toHaveBeenCalledTimes(1)
  })

  it('never retries a failed start by itself; start() tries again once', async () => {
    const failure = new ApiError({ status: 0, title: 'Backend unreachable', detail: 'Network Error' })
    const api = makeApi({ getOverview: vi.fn().mockResolvedValue(overviewBefore), start: vi.fn().mockRejectedValue(failure) })
    const { result } = renderHook(() => useRmi({ api }))
    await waitFor(() => expect(result.current.startError).toBe(failure))
    await emit(CLUSTER_TOPIC)
    await emit(MODULE_TOPIC)
    await waitFor(() => expect(api.getOverview.mock.calls.length).toBeGreaterThanOrEqual(3))
    expect(api.start).toHaveBeenCalledTimes(1)

    await act(() => result.current.start())
    expect(api.start).toHaveBeenCalledTimes(2)
  })

  it('does not apply an overview read that was sent before a start answered, and reads again', async () => {
    const firstRead = deferred()
    const api = makeApi({ getOverview: vi.fn().mockReturnValueOnce(firstRead.promise).mockResolvedValue(overviewAfter) })
    const { result } = renderHook(() => useRmi({ api }))
    await waitFor(() => expect(result.current.overview).toEqual(startFixture))

    await act(async () => firstRead.resolve(overviewBefore))   // stale: says "not started"
    await waitFor(() => expect(api.getOverview).toHaveBeenCalledTimes(2))   // the stale read is followed by a fresh one
    expect(result.current.overview.nodes.every((n) => n.state === 'EXPORTED')).toBe(true)
    expect(api.start).toHaveBeenCalledTimes(1)
  })

  it('refreshes on module events and replaces the history wholesale, never merging by id', async () => {
    const api = makeApi()
    const { result } = renderHook(() => useRmi({ api }))
    await waitFor(() => expect(result.current.history).toEqual(callsHistory))

    const afterReset = [callsHistory[callsHistory.length - 1]]   // ids restart at 1 after a reset
    api.getHistory.mockResolvedValue(afterReset)
    await emit(MODULE_TOPIC)
    await waitFor(() => expect(result.current.history).toEqual(afterReset))
  })

  it('refreshes on a reconnect but not on the first connect', async () => {
    const api = makeApi()
    renderHook(() => useRmi({ api }))
    await waitFor(() => expect(api.getRegistries).toHaveBeenCalledTimes(2))   // mount read and the read after start
    act(() => connectHandlers.forEach((cb) => cb()))
    expect(api.getRegistries).toHaveBeenCalledTimes(2)
    act(() => connectHandlers.forEach((cb) => cb()))
    await waitFor(() => expect(api.getRegistries).toHaveBeenCalledTimes(3))
  })

  it('reports a failed overview read as the error, keeping loading false', async () => {
    const failure = new ApiError({ status: 0, title: 'Backend unreachable', detail: 'Network Error' })
    const api = makeApi({ getOverview: vi.fn().mockRejectedValue(failure), start: vi.fn().mockRejectedValue(failure) })
    const { result } = renderHook(() => useRmi({ api }))
    await waitFor(() => expect(result.current.error).toBe(failure))
    expect(result.current.loading).toBe(false)
    expect(result.current.overview).toBeNull()
  })
})
