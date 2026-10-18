import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as useStompModule from '@/hooks/useStomp'
import { useTimelineEvents } from './useTimelineEvents'

const ev = (sequence, lamportTime = 1, nodeId = 1) => ({ sequence, lamportTime, nodeId, type: 'PING', module: 'test' })

describe('useTimelineEvents', () => {
  let onMessage
  let onConnected
  let api

  beforeEach(() => {
    vi.spyOn(useStompModule, 'useStomp').mockReturnValue({
      status: 'connected',
      subscribe: vi.fn((destination, handler) => { onMessage = handler; return () => {} }),
      onConnect: vi.fn((handler) => { onConnected = handler; return () => {} }),
    })
    api = { getEvents: vi.fn().mockResolvedValue([ev(1), ev(2)]) }
  })

  afterEach(() => vi.restoreAllMocks())

  it('loads 500 events from REST, then merges live events without duplicates', async () => {
    const { result } = renderHook(() => useTimelineEvents({ api }))
    expect(api.getEvents).toHaveBeenCalledWith({ limit: 500 })
    await waitFor(() => expect(result.current.loading).toBe(false))
    act(() => { onMessage(ev(3)); onMessage(ev(2)); onMessage('not json') })
    expect(result.current.events.map((e) => e.sequence)).toEqual([1, 2, 3])
    expect(result.current.status).toBe('connected')
  })

  it('reloads on every connect and merges what was missed', async () => {
    const { result } = renderHook(() => useTimelineEvents({ api }))
    await waitFor(() => expect(result.current.loading).toBe(false))
    api.getEvents.mockResolvedValue([ev(2), ev(4), ev(5)])
    await act(async () => { await onConnected() })
    expect(result.current.events.map((e) => e.sequence)).toEqual([1, 2, 4, 5])
  })

  it('reports a failed load and keeps live events', async () => {
    api.getEvents.mockRejectedValue(new Error('down'))
    const { result } = renderHook(() => useTimelineEvents({ api }))
    await waitFor(() => expect(result.current.error?.message).toBe('down'))
    act(() => onMessage(ev(7)))
    expect(result.current.events.map((e) => e.sequence)).toEqual([7])
  })
})
