import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as useStompModule from '@/hooks/useStomp'
import overviewAfterBully from '@/test/fixtures/election/overview-after-bully.json'
import overviewTimedOut from '@/test/fixtures/election/overview-ring-timed-out.json'
import startRing from '@/test/fixtures/election/start-ring.json'
import { CLUSTER_TOPIC, MODULE_TOPIC, TIMEOUT_MARGIN_MS, useElection } from './useElection'

const copy = (value) => JSON.parse(JSON.stringify(value))
/** A real overview with the real 202 round as its open round (no capture holds an open round). */
const withOpenRound = (round = startRing, timeout = 10000) => ({
  ...copy(overviewAfterBully),
  status: 'BUSY',
  currentRound: round,
  settings: { ...overviewAfterBully.settings, roundTimeoutMillis: timeout },
})

function deferred() {
  let resolve
  const promise = new Promise((done) => { resolve = done })
  return { promise, resolve }
}

describe('useElection', () => {
  let handlers
  let connectHandler

  beforeEach(() => {
    vi.useFakeTimers()
    handlers = {}
    connectHandler = null
    vi.spyOn(useStompModule, 'useStomp').mockReturnValue({
      status: 'connected',
      subscribe: vi.fn((destination, handler) => {
        handlers[destination] = handler
        return vi.fn()
      }),
      onConnect: vi.fn((handler) => {
        connectHandler = handler
        return vi.fn()
      }),
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  const flush = () => act(async () => { await vi.advanceTimersByTimeAsync(0) })

  it('loads the overview once on mount', async () => {
    const api = { getOverview: vi.fn().mockResolvedValue(overviewAfterBully) }
    const { result } = renderHook(() => useElection({ api }))
    expect(result.current.loading).toBe(true)
    await flush()
    expect(result.current.overview).toEqual(overviewAfterBully)
    expect(result.current.loading).toBe(false)
    expect(api.getOverview).toHaveBeenCalledTimes(1)
  })

  it('refreshes on module and cluster events and after a reconnect, not on the first connect', async () => {
    const api = { getOverview: vi.fn().mockResolvedValue(overviewAfterBully) }
    renderHook(() => useElection({ api }))
    await flush()
    await act(async () => { handlers[MODULE_TOPIC]({}) })
    await flush()
    await act(async () => { handlers[CLUSTER_TOPIC]({}) })
    await flush()
    await act(async () => { connectHandler() })
    await flush()
    expect(api.getOverview).toHaveBeenCalledTimes(3)
    await act(async () => { connectHandler() })
    await flush()
    expect(api.getOverview).toHaveBeenCalledTimes(4)
  })

  it('coalesces a burst of events into one read in flight and one queued', async () => {
    const first = deferred()
    const api = { getOverview: vi.fn().mockReturnValueOnce(first.promise).mockResolvedValue(overviewAfterBully) }
    renderHook(() => useElection({ api }))
    for (let i = 0; i < 20; i += 1) {
      handlers[MODULE_TOPIC]({})
    }
    expect(api.getOverview).toHaveBeenCalledTimes(1)
    await act(async () => { first.resolve(overviewAfterBully) })
    await flush()
    expect(api.getOverview).toHaveBeenCalledTimes(2)
  })

  it('never polls while no round is open', async () => {
    const api = { getOverview: vi.fn().mockResolvedValue(overviewAfterBully) }
    renderHook(() => useElection({ api }))
    await flush()
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
    expect(api.getOverview).toHaveBeenCalledTimes(1)
  })

  it('refreshes exactly once at the round timeout from the overview + 250 ms while a round is open', async () => {
    const api = { getOverview: vi.fn().mockResolvedValueOnce(withOpenRound(startRing, 4000)).mockResolvedValue(overviewTimedOut) }
    const { result } = renderHook(() => useElection({ api }))
    await flush()
    await act(async () => { await vi.advanceTimersByTimeAsync(4000 + TIMEOUT_MARGIN_MS - 1) })
    expect(api.getOverview).toHaveBeenCalledTimes(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(1) })
    await flush()
    expect(api.getOverview).toHaveBeenCalledTimes(2)
    expect(result.current.overview.lastRound.outcome).toBe('TIMED_OUT')
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
    expect(api.getOverview).toHaveBeenCalledTimes(2)
  })

  it('keeps one timer at a time: a new round replaces the old timer, and unmount clears it', async () => {
    const nextRound = { ...startRing, roundId: startRing.roundId + 1 }
    const api = {
      getOverview: vi.fn()
        .mockResolvedValueOnce(withOpenRound(startRing, 4000))
        .mockResolvedValueOnce(withOpenRound(nextRound, 4000))
        .mockResolvedValue(overviewTimedOut),
    }
    const { unmount } = renderHook(() => useElection({ api }))
    await flush()
    expect(vi.getTimerCount()).toBe(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(2000) })
    await act(async () => { handlers[MODULE_TOPIC]({}) })
    await flush()
    expect(vi.getTimerCount()).toBe(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(2000 + TIMEOUT_MARGIN_MS) })
    expect(api.getOverview).toHaveBeenCalledTimes(2)   // the first round's timer was cleared, not fired
    unmount()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('ignores a response that arrives after unmount', async () => {
    const late = deferred()
    const api = { getOverview: vi.fn().mockReturnValue(late.promise) }
    const { result, unmount } = renderHook(() => useElection({ api }))
    unmount()
    await act(async () => { late.resolve(overviewAfterBully) })
    expect(result.current.overview).toBeNull()
  })
})
