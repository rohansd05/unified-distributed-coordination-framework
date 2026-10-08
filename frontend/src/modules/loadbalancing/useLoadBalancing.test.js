import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as useStompModule from '@/hooks/useStomp'
import overviewBefore from '@/test/fixtures/loadbalancing/overview-before.json'
import overviewAfterRun from '@/test/fixtures/loadbalancing/overview-after-run.json'
import { CLUSTER_TOPIC, MODULE_TOPIC, POLL_INTERVAL_MS, isActive, useLoadBalancing } from './useLoadBalancing'

/** The real "after a run" overview, put back into the state it had while the run executed. */
function runningCopy() {
  const running = JSON.parse(JSON.stringify(overviewAfterRun))
  running.status = 'BUSY'
  running.latestRun.state = 'RUNNING'
  running.latestRun.report = null
  return running
}

/** A promise the test resolves by hand. */
function deferred() {
  let resolve
  const promise = new Promise((done) => { resolve = done })
  return { promise, resolve }
}

describe('useLoadBalancing', () => {
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
  const makeApi = (overview = overviewBefore) => ({ getOverview: vi.fn().mockResolvedValue(overview) })
  const render = (api) => renderHook(() => useLoadBalancing({ api }))

  it('loads the overview', async () => {
    const api = makeApi()
    const { result } = render(api)
    expect(result.current.loading).toBe(true)

    await flush()

    expect(result.current.overview).toEqual(overviewBefore)
    expect(result.current.loading).toBe(false)
    expect(result.current.active).toBe(false)
  })

  it('isActive: BUSY, or a run or comparison still RUNNING', () => {
    expect(isActive(null)).toBe(false)
    expect(isActive(overviewBefore)).toBe(false)
    expect(isActive({ status: 'BUSY' })).toBe(true)
    expect(isActive({ status: 'IDLE', latestRun: { state: 'RUNNING' } })).toBe(true)
    expect(isActive({ status: 'IDLE', latestComparison: { state: 'RUNNING' } })).toBe(true)
  })

  it('refreshes on RUN_*, COMPARISON_* and CRASH_* events and on cluster events, not on DISPATCH_* bursts', async () => {
    const api = makeApi()
    render(api)
    await flush()

    for (const type of ['RUN_FINISHED', 'COMPARISON_FINISHED', 'CRASH_INJECTED']) {
      await act(async () => handlers[MODULE_TOPIC]({ type }))
    }
    await act(async () => handlers[MODULE_TOPIC]({ type: 'DISPATCH_FAILED' }))
    await act(async () => handlers[MODULE_TOPIC]({}))
    expect(api.getOverview).toHaveBeenCalledTimes(4)

    await act(async () => handlers[CLUSTER_TOPIC]({ type: 'CLUSTER_RESET' }))
    expect(api.getOverview).toHaveBeenCalledTimes(5)
  })

  it('refreshes after a reconnect, not on the first connect', async () => {
    const api = makeApi()
    render(api)
    await flush()

    await act(async () => connectHandler())
    expect(api.getOverview).toHaveBeenCalledTimes(1)
    await act(async () => connectHandler())
    expect(api.getOverview).toHaveBeenCalledTimes(2)
  })

  it('polls every 500 ms while a run executes and stops as soon as it has finished', async () => {
    const api = makeApi()
    api.getOverview.mockResolvedValueOnce(runningCopy()).mockResolvedValueOnce(runningCopy()).mockResolvedValue(overviewAfterRun)
    const { result } = render(api)
    await flush()
    expect(result.current.active).toBe(true)

    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS) })
    expect(api.getOverview).toHaveBeenCalledTimes(2)
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS) })
    expect(api.getOverview).toHaveBeenCalledTimes(3)
    expect(result.current.active).toBe(false)
    expect(result.current.overview.latestRun.state).toBe('FINISHED')

    await act(async () => { await vi.advanceTimersByTimeAsync(10 * POLL_INTERVAL_MS) })
    expect(api.getOverview).toHaveBeenCalledTimes(3)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('ignores a late poll that answers after the run has finished: no stale RUNNING state', async () => {
    const api = makeApi()
    const latePoll = deferred()
    api.getOverview
      .mockResolvedValueOnce(runningCopy())     // the first load: running
      .mockReturnValueOnce(latePoll.promise)    // the poll, which will answer late
      .mockResolvedValueOnce(overviewAfterRun)  // the refresh triggered by RUN_FINISHED
    const { result } = render(api)
    await flush()

    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS) })   // the poll starts and hangs
    await act(async () => handlers[MODULE_TOPIC]({ type: 'RUN_FINISHED' }))
    expect(result.current.overview.latestRun.state).toBe('FINISHED')

    await act(async () => { latePoll.resolve(runningCopy()) })                        // the stale answer arrives
    await flush()

    expect(result.current.overview.latestRun.state).toBe('FINISHED')
    expect(result.current.active).toBe(false)
  })

  it('after unmount: no timer, no subscription, and a late response changes nothing', async () => {
    const api = makeApi()
    const late = deferred()
    api.getOverview.mockResolvedValueOnce(runningCopy()).mockReturnValueOnce(late.promise)
    const { result, unmount } = render(api)
    await flush()
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS) })   // a poll in flight
    const before = result.current.overview

    unmount()
    await act(async () => { late.resolve(overviewAfterRun) })
    await flush()

    expect(result.current.overview).toBe(before)
    expect(unsubscribers.every((unsubscribe) => unsubscribe.mock.calls.length === 1)).toBe(true)
    expect(connectUnsubscribe).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('keeps a failed fetch as the error, and clears it on the next success', async () => {
    const api = makeApi()
    api.getOverview.mockRejectedValueOnce(new Error('connection refused')).mockResolvedValue(overviewBefore)
    const { result } = render(api)
    await flush()
    expect(result.current.error.message).toBe('connection refused')
    expect(result.current.overview).toBeNull()

    await act(async () => handlers[CLUSTER_TOPIC]({ type: 'NODE_RECOVERED' }))

    expect(result.current.error).toBeNull()
    expect(result.current.overview).toEqual(overviewBefore)
  })
})
