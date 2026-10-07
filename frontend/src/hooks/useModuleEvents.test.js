import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as useStompModule from '@/hooks/useStomp'
import eventsFixture from '@/test/fixtures/multithreading/events.json'
import { useModuleEvents } from './useModuleEvents'

describe('useModuleEvents', () => {
  let handlers
  let unsubscribers
  let connectHandler
  let api

  beforeEach(() => {
    handlers = {}
    unsubscribers = []
    connectHandler = null
    vi.spyOn(useStompModule, 'useStomp').mockReturnValue({
      subscribe: vi.fn((destination, handler) => {
        handlers[destination] = handler
        const unsubscribe = vi.fn()
        unsubscribers.push(unsubscribe)
        return unsubscribe
      }),
      onConnect: vi.fn((handler) => {
        connectHandler = handler
        return vi.fn()
      }),
    })
    api = { getEvents: vi.fn().mockResolvedValue(eventsFixture) }
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('loads the module filter with limit 50 and orders the real events causally', async () => {
    const { result } = renderHook(() => useModuleEvents({ moduleId: 'multithreading', api }))

    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(api.getEvents).toHaveBeenCalledWith({ module: 'multithreading', limit: 50 })
    const order = result.current.events.map((event) => [event.lamportTime, event.nodeId, event.sequence])
    expect(order).toEqual([...order].sort((a, b) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2]))
    expect(result.current.events).toHaveLength(eventsFixture.length)
  })

  it('appends a live event from the module topic and ignores a duplicate', async () => {
    const { result } = renderHook(() => useModuleEvents({ moduleId: 'multithreading', api }))
    await waitFor(() => expect(result.current.loading).toBe(false))
    const live = { ...eventsFixture[0], sequence: 99, lamportTime: 50, type: 'BATCH_SUBMITTED' }

    act(() => handlers['/topic/modules/multithreading'](live))
    act(() => handlers['/topic/modules/multithreading'](live))

    expect(result.current.events.filter((event) => event.sequence === 99)).toHaveLength(1)
    expect(result.current.events.at(-1).sequence).toBe(99)
  })

  it('ignores an event of another module', async () => {
    const { result } = renderHook(() => useModuleEvents({ moduleId: 'multithreading', api }))
    await waitFor(() => expect(result.current.loading).toBe(false))

    act(() => handlers['/topic/modules/multithreading']({ ...eventsFixture[0], sequence: 77, module: 'election' }))

    expect(result.current.events.some((event) => event.sequence === 77)).toBe(false)
  })

  it('clears on CLUSTER_RESET from /topic/cluster, and ignores other cluster events', async () => {
    const { result } = renderHook(() => useModuleEvents({ moduleId: 'multithreading', api }))
    await waitFor(() => expect(result.current.events.length).toBeGreaterThan(0))

    act(() => handlers['/topic/cluster']({ type: 'NODE_CRASHED', module: 'cluster', sequence: 200 }))
    expect(result.current.events.length).toBeGreaterThan(0)

    act(() => handlers['/topic/cluster']({ type: 'CLUSTER_RESET', module: 'cluster', sequence: 201 }))
    expect(result.current.events).toEqual([])
  })

  it('reloads after a reconnect, but not on the first connect', async () => {
    const { result } = renderHook(() => useModuleEvents({ moduleId: 'multithreading', api }))
    await waitFor(() => expect(result.current.loading).toBe(false))

    await act(async () => connectHandler())
    expect(api.getEvents).toHaveBeenCalledTimes(1)
    await act(async () => connectHandler())
    expect(api.getEvents).toHaveBeenCalledTimes(2)
  })

  it('exposes a failed load as an error, without throwing', async () => {
    api.getEvents.mockRejectedValue(new Error('Backend unreachable'))

    const { result } = renderHook(() => useModuleEvents({ moduleId: 'multithreading', api }))

    await waitFor(() => expect(result.current.error?.message).toBe('Backend unreachable'))
    expect(result.current.loading).toBe(false)
  })

  it('removes both subscriptions on unmount and ignores a late response', async () => {
    let resolve
    api.getEvents.mockReturnValue(new Promise((done) => { resolve = done }))
    const { result, unmount } = renderHook(() => useModuleEvents({ moduleId: 'multithreading', api }))

    unmount()
    await act(async () => resolve(eventsFixture))

    expect(unsubscribers.length).toBeGreaterThanOrEqual(2)
    expect(unsubscribers.every((unsubscribe) => unsubscribe.mock.calls.length === 1)).toBe(true)
    expect(result.current.events).toEqual([])
  })
})
