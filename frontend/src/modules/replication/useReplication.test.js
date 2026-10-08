import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as useStompModule from '@/hooks/useStomp'
import overviewBefore from '@/test/fixtures/replication/overview-before.json'
import overviewAfterAsync from '@/test/fixtures/replication/overview-after-async.json'
import replicasConverged from '@/test/fixtures/replication/replicas-converged.json'
import writeAsync from '@/test/fixtures/replication/write-async.json'
import { CLUSTER_TOPIC, MODULE_TOPIC, POLL_INTERVAL_MS, isActive, useReplication } from './useReplication'

/** A promise the test resolves by hand. */
function deferred() {
  let resolve
  const promise = new Promise((done) => { resolve = done })
  return { promise, resolve }
}

/** The real after-async overview, put back into the state it had while the window was open. */
const pendingOverview = () => ({ ...JSON.parse(JSON.stringify(overviewAfterAsync)), status: 'RUNNING', latestWrite: writeAsync })

describe('useReplication', () => {
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
  const makeApi = (overview = overviewBefore, replicas = replicasConverged) => ({
    getOverview: vi.fn().mockResolvedValue(overview),
    getReplicas: vi.fn().mockResolvedValue(replicas),
  })

  it('loads the overview and the replicas together', async () => {
    const api = makeApi()
    const { result } = renderHook(() => useReplication({ api }))
    expect(result.current.loading).toBe(true)

    await flush()

    expect(result.current.overview).toEqual(overviewBefore)
    expect(result.current.replicas).toEqual(replicasConverged)
    expect(result.current.loading).toBe(false)
    expect(result.current.active).toBe(false)
  })

  it('isActive: BUSY, RUNNING, or the latest write still PENDING', () => {
    expect(isActive(null)).toBe(false)
    expect(isActive(overviewBefore)).toBe(false)
    expect(isActive(overviewAfterAsync)).toBe(false)
    expect(isActive({ status: 'BUSY' })).toBe(true)
    expect(isActive({ status: 'RUNNING' })).toBe(true)
    expect(isActive({ status: 'IDLE', latestWrite: writeAsync })).toBe(true)
  })

  it('refreshes on any module event, on cluster events and after a reconnect (not on the first connect)', async () => {
    const api = makeApi()
    renderHook(() => useReplication({ api }))
    await flush()
    expect(api.getOverview).toHaveBeenCalledTimes(1)

    await act(async () => { handlers[MODULE_TOPIC]({ type: 'ACK' }) })
    await flush()
    await act(async () => { handlers[CLUSTER_TOPIC]({ type: 'NODE_CRASHED' }) })
    await flush()
    await act(async () => { connectHandler() })
    await flush()
    expect(api.getOverview).toHaveBeenCalledTimes(3)
    await act(async () => { connectHandler() })
    await flush()

    expect(api.getOverview).toHaveBeenCalledTimes(4)
    expect(api.getReplicas).toHaveBeenCalledTimes(4)
  })

  it('coalesces a burst of events into one more read while one is in flight', async () => {
    const first = deferred()
    const api = makeApi()
    api.getOverview.mockReturnValueOnce(first.promise)
    renderHook(() => useReplication({ api }))

    for (let i = 0; i < 10; i++) {
      await act(async () => { handlers[MODULE_TOPIC]({ type: 'REPLICA_APPLIED' }) })
    }
    expect(api.getOverview).toHaveBeenCalledTimes(1)

    await act(async () => { first.resolve(overviewBefore) })
    await flush()

    expect(api.getOverview).toHaveBeenCalledTimes(2)
  })

  it('polls only while active, and stops when the window closes', async () => {
    const api = makeApi(pendingOverview())
    const { result } = renderHook(() => useReplication({ api }))
    await flush()
    expect(result.current.active).toBe(true)

    api.getOverview.mockResolvedValue(overviewAfterAsync)
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS) })
    expect(result.current.active).toBe(false)
    const calls = api.getOverview.mock.calls.length

    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 5) })
    expect(api.getOverview).toHaveBeenCalledTimes(calls)
  })

  it('keeps the overview when only the replicas read fails, and reports each error separately', async () => {
    const api = makeApi()
    api.getReplicas.mockRejectedValue(new Error('replicas down'))
    const { result } = renderHook(() => useReplication({ api }))
    await flush()

    expect(result.current.overview).toEqual(overviewBefore)
    expect(result.current.error).toBeNull()
    expect(result.current.replicasError.message).toBe('replicas down')

    api.getOverview.mockRejectedValue(new Error('backend down'))
    await act(async () => { await result.current.refresh() })
    expect(result.current.error.message).toBe('backend down')
    expect(result.current.overview).toEqual(overviewBefore)
  })

  it('ignores a response that arrives after unmount', async () => {
    const late = deferred()
    const api = makeApi()
    api.getOverview.mockReturnValueOnce(late.promise)
    const { result, unmount } = renderHook(() => useReplication({ api }))
    unmount()

    await act(async () => { late.resolve(overviewAfterAsync) })
    await flush()

    expect(result.current.overview).toBeNull()
  })
})
