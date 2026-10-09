import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as useStompModule from '@/hooks/useStomp'
import eventsCluster from '@/test/fixtures/election/events-cluster.json'
import { CLUSTER_EVENT_LIMIT, useLeaderChanges } from './useLeaderChanges'

const realChanges = eventsCluster.filter((e) => e.type === 'LEADER_CHANGED')

describe('useLeaderChanges', () => {
  let clusterHandler
  let connectHandler

  beforeEach(() => {
    vi.spyOn(useStompModule, 'useStomp').mockReturnValue({
      status: 'connected',
      subscribe: vi.fn((destination, handler) => {
        clusterHandler = handler
        return vi.fn()
      }),
      onConnect: vi.fn((handler) => {
        connectHandler = handler
        return vi.fn()
      }),
    })
  })

  afterEach(() => vi.restoreAllMocks())

  const flush = () => act(async () => {})

  it('reads the cluster log once and keeps only its LEADER_CHANGED events, with their real Lamport values', async () => {
    const api = { getEvents: vi.fn().mockResolvedValue(eventsCluster) }
    const { result } = renderHook(() => useLeaderChanges({ api }))
    await flush()
    expect(api.getEvents).toHaveBeenCalledWith({ module: 'cluster', limit: CLUSTER_EVENT_LIMIT })
    expect(result.current.changes.map((e) => e.sequence)).toEqual(realChanges.map((e) => e.sequence))
    expect(result.current.changes.map((e) => e.lamportTime)).toEqual(realChanges.map((e) => e.lamportTime))
    expect(eventsCluster.length).toBeGreaterThan(realChanges.length)   // other cluster events were dropped
  })

  it('appends a live LEADER_CHANGED once, ignores other cluster events, and clears on CLUSTER_RESET', async () => {
    const api = { getEvents: vi.fn().mockResolvedValue(realChanges.slice(0, 1)) }
    const { result } = renderHook(() => useLeaderChanges({ api }))
    await flush()
    await act(async () => {
      clusterHandler(realChanges[1])
      clusterHandler(realChanges[1])
      clusterHandler({ ...realChanges[1], type: 'NODE_CRASHED', sequence: 9999 })
    })
    expect(result.current.changes.map((e) => e.sequence)).toEqual([realChanges[0].sequence, realChanges[1].sequence])
    await act(async () => { clusterHandler({ type: 'CLUSTER_RESET', sequence: 10000 }) })
    expect(result.current.changes).toEqual([])
  })

  it('reloads after a reconnect, not on the first connect, and reports a failed load', async () => {
    const api = { getEvents: vi.fn().mockResolvedValueOnce(realChanges).mockRejectedValue(new Error('down')) }
    const { result } = renderHook(() => useLeaderChanges({ api }))
    await flush()
    await act(async () => { connectHandler() })
    expect(api.getEvents).toHaveBeenCalledTimes(1)
    await act(async () => { connectHandler() })
    await flush()
    expect(api.getEvents).toHaveBeenCalledTimes(2)
    expect(result.current.error).toBeInstanceOf(Error)
  })
})
