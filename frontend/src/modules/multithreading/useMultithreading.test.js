import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as useStompModule from '@/hooks/useStomp'
import overviewBefore from '@/test/fixtures/multithreading/overview-before.json'
import overviewAfter from '@/test/fixtures/multithreading/overview-after-batch.json'
import requestsFixture from '@/test/fixtures/multithreading/requests.json'
import {
  CLUSTER_TOPIC,
  MODULE_TOPIC,
  POLL_INTERVAL_MS,
  REQUEST_LIMIT,
  isActive,
  useMultithreading,
} from './useMultithreading'

const running = { ...overviewAfter, status: 'RUNNING' }

/** A promise the test resolves by hand. */
function deferred() {
  let resolve
  const promise = new Promise((done) => { resolve = done })
  return { promise, resolve }
}

describe('useMultithreading', () => {
  let handlers
  let unsubscribers
  let connectHandler
  let connectUnsubscribe

  beforeEach(() => {
    vi.useFakeTimers()
    handlers = {}
    unsubscribers = []
    connectHandler = null
    connectUnsubscribe = vi.fn()
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
        return connectUnsubscribe
      }),
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  const flush = () => act(async () => { await vi.advanceTimersByTimeAsync(0) })

  function makeApi(overview = overviewBefore, requests = []) {
    return {
      getOverview: vi.fn().mockResolvedValue(overview),
      getRequests: vi.fn().mockResolvedValue(requests),
    }
  }

  function render(api, nodeId = 1) {
    return renderHook(({ selectedNodeId }) => useMultithreading({ api, selectedNodeId }), {
      initialProps: { selectedNodeId: nodeId },
    })
  }

  it('loads the overview and the selected node requests; requests are null until then', async () => {
    const api = makeApi(overviewAfter, requestsFixture)
    const { result } = render(api)
    expect(result.current.requests).toBeNull()
    expect(result.current.loading).toBe(true)

    await flush()

    expect(api.getRequests).toHaveBeenCalledWith(1, REQUEST_LIMIT)
    expect(result.current.overview).toEqual(overviewAfter)
    expect(result.current.requests).toEqual(requestsFixture)
    expect(result.current.loading).toBe(false)
    expect(result.current.throughput.map((sample) => sample.value)).toEqual([3.33])
  })

  it('refreshes on BATCH_* module events and on cluster events, not on other module events', async () => {
    const api = makeApi()
    render(api)
    await flush()

    await act(async () => handlers[MODULE_TOPIC]({ type: 'BATCH_FINISHED' }))
    await act(async () => handlers[MODULE_TOPIC]({ type: 'REQUEST_COMPLETED' }))
    expect(api.getOverview).toHaveBeenCalledTimes(2)

    await act(async () => handlers[CLUSTER_TOPIC]({ type: 'NODE_CRASHED' }))
    expect(api.getOverview).toHaveBeenCalledTimes(3)
  })

  it('polls every second while the module is RUNNING and stops once it is idle', async () => {
    const api = makeApi()
    api.getOverview.mockResolvedValueOnce(running).mockResolvedValueOnce(running).mockResolvedValue(overviewAfter)
    const { result } = render(api)
    await flush()
    expect(result.current.active).toBe(true)

    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS) })
    expect(api.getOverview).toHaveBeenCalledTimes(2)
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS) })
    expect(api.getOverview).toHaveBeenCalledTimes(3)
    expect(result.current.active).toBe(false)

    await act(async () => { await vi.advanceTimersByTimeAsync(5 * POLL_INTERVAL_MS) })
    expect(api.getOverview).toHaveBeenCalledTimes(3)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('leaves no timer and no subscription behind after unmount, even while polling', async () => {
    const api = makeApi(running)
    const { result, unmount } = render(api)
    await flush()
    expect(result.current.active).toBe(true)
    expect(vi.getTimerCount()).toBeGreaterThan(0)

    unmount()

    expect(vi.getTimerCount()).toBe(0)
    expect(unsubscribers.length).toBeGreaterThanOrEqual(2)
    expect(unsubscribers.every((unsubscribe) => unsubscribe.mock.calls.length === 1)).toBe(true)
    expect(connectUnsubscribe).toHaveBeenCalledTimes(1)
  })

  it('reloads after a reconnect, but not on the first connect', async () => {
    const api = makeApi()
    render(api)
    await flush()

    await act(async () => connectHandler())
    expect(api.getOverview).toHaveBeenCalledTimes(1)
    await act(async () => connectHandler())
    expect(api.getOverview).toHaveBeenCalledTimes(2)
  })

  it('ignores a response for the old node after the selected node changed', async () => {
    const slow = deferred()
    const api = makeApi(overviewAfter)
    api.getRequests.mockImplementation((nodeId) => (nodeId === 1 ? slow.promise : Promise.resolve([])))
    const { result, rerender } = render(api, 1)

    rerender({ selectedNodeId: 3 })
    await flush()
    expect(result.current.requests).toEqual([])   // node 3's own (empty) list

    await act(async () => { slow.resolve(requestsFixture); await vi.advanceTimersByTimeAsync(0) })

    expect(result.current.requests).toEqual([])    // node 1's late rows never appear for node 3
    expect(result.current.throughput).toEqual([])  // node 3 has no stats: no samples, and none from node 1
  })

  it('starts a fresh throughput series when the node changes', async () => {
    const api = makeApi(overviewAfter, requestsFixture)
    const { result, rerender } = render(api, 1)
    await flush()
    await act(async () => handlers[MODULE_TOPIC]({ type: 'BATCH_FINISHED' }))
    expect(result.current.throughput).toHaveLength(2)

    rerender({ selectedNodeId: 2 })
    expect(result.current.throughput).toEqual([])
    expect(result.current.requests).toBeNull()
  })

  it('ignores a response that arrives after unmount', async () => {
    const late = deferred()
    const api = makeApi()
    api.getOverview.mockReturnValue(late.promise)
    const { result, unmount } = render(api)

    unmount()
    await act(async () => { late.resolve(overviewAfter); await vi.advanceTimersByTimeAsync(0) })

    expect(result.current.overview).toBeNull()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('exposes a failed fetch as an error without throwing', async () => {
    const api = makeApi()
    api.getOverview.mockRejectedValue(Object.assign(new Error('Backend unreachable'), { status: 0 }))
    const { result } = render(api)

    await flush()

    expect(result.current.error.message).toBe('Backend unreachable')
    expect(result.current.overview).toBeNull()
    expect(result.current.loading).toBe(false)
    expect(result.current.active).toBe(false)
  })
})

describe('isActive', () => {
  it('is true while the module is RUNNING or BUSY', () => {
    expect(isActive({ ...overviewBefore, status: 'RUNNING' }, 1)).toBe(true)
    expect(isActive({ ...overviewBefore, status: 'BUSY' }, 1)).toBe(true)
  })

  it('is true while the selected node has work in its executor, even if the module is idle', () => {
    const node = overviewAfter.nodes[0]
    const withWork = { ...overviewAfter, nodes: [{ ...node, stats: { ...node.stats, queuedRequests: 3 } }] }
    expect(isActive(withWork, 1)).toBe(true)
  })

  it('is false when idle, without stats, or without an overview', () => {
    expect(isActive(overviewAfter, 1)).toBe(false)
    expect(isActive(overviewBefore, 1)).toBe(false)
    expect(isActive(null, 1)).toBe(false)
  })
})
