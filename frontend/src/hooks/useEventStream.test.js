import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useEventStream } from './useEventStream'
import * as useStompModule from '@/hooks/useStomp'

describe('useEventStream', () => {
  let mockApi
  let mockSubscribe
  let mockOnConnect
  let messageHandler
  let connectHandler

  beforeEach(() => {
    messageHandler = null
    connectHandler = null

    mockSubscribe = vi.fn((destination, handler) => {
      messageHandler = handler
      return vi.fn() // unsub
    })

    mockOnConnect = vi.fn((handler) => {
      connectHandler = handler
      return vi.fn() // unsub
    })

    vi.spyOn(useStompModule, 'useStomp').mockReturnValue({
      subscribe: mockSubscribe,
      onConnect: mockOnConnect,
    })

    mockApi = {
      getEvents: vi.fn().mockResolvedValue([
        { sequence: 1, lamportTime: 10, nodeId: 1, type: 'INIT', message: 'First' },
        { sequence: 2, lamportTime: 12, nodeId: 2, type: 'PING', message: 'Second' },
      ]),
    }
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('loads initial events from REST API and subscribes to /topic/events', async () => {
    const { result } = renderHook(() => useEventStream({ limit: 50, api: mockApi }))

    expect(result.current.loading).toBe(true)
    expect(mockApi.getEvents).toHaveBeenCalledWith({ limit: 50 })
    expect(mockSubscribe).toHaveBeenCalledWith('/topic/events', expect.any(Function))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.events).toHaveLength(2)
    expect(result.current.events[0].sequence).toBe(1)
    expect(result.current.events[1].sequence).toBe(2)
    expect(result.current.error).toBeNull()
  })

  it('appends incoming STOMP events in causal order', async () => {
    const { result } = renderHook(() => useEventStream({ limit: 50, api: mockApi }))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    // Incoming event with lower Lamport time should be inserted before event 2
    act(() => {
      messageHandler({
        sequence: 3,
        lamportTime: 11,
        nodeId: 1,
        type: 'NODE_CRASHED',
        message: 'Third with L11',
      })
    })

    expect(result.current.events).toHaveLength(3)
    expect(result.current.events.map((e) => e.sequence)).toEqual([1, 3, 2])
  })

  it('ignores duplicate events by sequence', async () => {
    const { result } = renderHook(() => useEventStream({ limit: 50, api: mockApi }))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    act(() => {
      messageHandler({
        sequence: 2,
        lamportTime: 12,
        nodeId: 2,
        type: 'DUPLICATE',
        message: 'Already exists',
      })
    })

    expect(result.current.events).toHaveLength(2)
  })

  it('caps the list at the specified limit, retaining highest causal events', async () => {
    mockApi.getEvents.mockResolvedValue([])
    const { result } = renderHook(() => useEventStream({ limit: 3, api: mockApi }))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    act(() => {
      messageHandler({ sequence: 1, lamportTime: 1, nodeId: 1, type: 'E1' })
      messageHandler({ sequence: 2, lamportTime: 2, nodeId: 1, type: 'E2' })
      messageHandler({ sequence: 3, lamportTime: 3, nodeId: 1, type: 'E3' })
      messageHandler({ sequence: 4, lamportTime: 4, nodeId: 1, type: 'E4' })
    })

    expect(result.current.events).toHaveLength(3)
    expect(result.current.events.map((e) => e.sequence)).toEqual([2, 3, 4])
  })

  it('replaces list with just that event on CLUSTER_RESET', async () => {
    const { result } = renderHook(() => useEventStream({ limit: 50, api: mockApi }))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.events).toHaveLength(2)

    act(() => {
      messageHandler({
        sequence: 10,
        lamportTime: 1,
        nodeId: 0,
        type: 'CLUSTER_RESET',
        message: 'Cluster reset clean slate',
      })
    })

    expect(result.current.events).toHaveLength(1)
    expect(result.current.events[0].type).toBe('CLUSTER_RESET')
    expect(result.current.events[0].sequence).toBe(10)
  })

  it('re-loads from REST on STOMP reconnect', async () => {
    const { result } = renderHook(() => useEventStream({ limit: 50, api: mockApi }))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(mockApi.getEvents).toHaveBeenCalledTimes(1)

    // Initial connect does not double-fetch
    act(() => {
      connectHandler?.()
    })
    expect(mockApi.getEvents).toHaveBeenCalledTimes(1)

    // Subsequent reconnect triggers reload
    mockApi.getEvents.mockResolvedValueOnce([
      { sequence: 10, lamportTime: 1, nodeId: 0, type: 'CLUSTER_RESET', message: 'Reset' },
    ])

    await act(async () => {
      connectHandler?.()
    })

    await waitFor(() => {
      expect(mockApi.getEvents).toHaveBeenCalledTimes(2)
      expect(result.current.events).toHaveLength(1)
      expect(result.current.events[0].sequence).toBe(10)
    })
  })

  it('surfaces error when initial REST load fails', async () => {
    const loadError = new Error('Network failure')
    mockApi.getEvents.mockRejectedValue(loadError)

    const { result } = renderHook(() => useEventStream({ limit: 50, api: mockApi }))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.error).toBe(loadError)
    expect(result.current.events).toEqual([])
  })
})
