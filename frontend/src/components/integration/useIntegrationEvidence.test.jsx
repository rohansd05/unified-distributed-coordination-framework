import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { StompContext } from '@/services/stomp/StompContext'
import { MAX_WAIT_MS, SETTLE_MS, staleSources, useIntegrationEvidence } from './useIntegrationEvidence'

function fakeStomp() {
  const handlers = {}
  const connects = []
  return {
    value: {
      subscribe: (topic, handler) => {
        handlers[topic] = handler
        return () => delete handlers[topic]
      },
      onConnect: (handler) => {
        connects.push(handler)
        return () => {}
      },
    },
    emit: (topic, message) => handlers[topic]?.(message),
    connect: () => connects.forEach((h) => h()),
  }
}

function readersCalling(calls, overrides = {}) {
  const ids = ['election', 'clocksync', 'replication', 'mapreduce', 'mapreduceRuns', 'faulttolerance', 'mpi', 'matrix', 'loadbalancing']
  return Object.fromEntries(ids.map((id) => [id, overrides[id] || (() => {
    calls.push(id)
    return Promise.resolve({ id, n: calls.filter((c) => c === id).length })
  })]))
}

async function flushPromises() {
  await act(async () => {
    await Promise.resolve()
    await Promise.resolve()
  })
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

function setup(readers) {
  const stomp = fakeStomp()
  const wrapper = ({ children }) => <StompContext.Provider value={stomp.value}>{children}</StompContext.Provider>
  const hook = renderHook(() => useIntegrationEvidence({ readers }), { wrapper })
  return { stomp, hook }
}

describe('staleSources', () => {
  it('maps a module event to the sources it owns', () => {
    expect(staleSources({ module: 'mpi' })).toEqual(['mpi'])
    expect(staleSources({ module: 'mapreduce' })).toEqual(['mapreduce', 'mapreduceRuns'])
    expect(staleSources({ module: 'rmi' })).toEqual([])
    expect(staleSources(null)).toEqual([])
  })

  it('a cluster event (leader change, crash, reset) can move every role, so it refreshes all', () => {
    expect(staleSources({ module: 'cluster' })).toHaveLength(9)
  })
})

describe('useIntegrationEvidence', () => {
  it('reads every source once on mount and marks them ok', async () => {
    const calls = []
    const { hook } = setup(readersCalling(calls))
    expect(hook.result.current.overviews.mpi.status).toBe('loading')
    await flushPromises()
    expect(calls).toHaveLength(9)
    expect(hook.result.current.overviews.mpi).toEqual({ status: 'ok', data: { id: 'mpi', n: 1 } })
  })

  it('re-reads only the affected source once a burst of its events settles; no polling otherwise', async () => {
    const calls = []
    const { stomp } = setup(readersCalling(calls))
    await flushPromises()
    calls.length = 0

    act(() => {
      stomp.emit('/topic/events', { module: 'mpi' })
      stomp.emit('/topic/events', { module: 'mpi' })
    })
    await act(async () => vi.advanceTimersByTime(SETTLE_MS - 1))
    expect(calls).toEqual([])
    await act(async () => vi.advanceTimersByTime(1))
    expect(calls).toEqual(['mpi'])

    await act(async () => vi.advanceTimersByTime(60_000))
    expect(calls).toEqual(['mpi'])
  })

  it('a steady stream of events cannot hold a read back past MAX_WAIT_MS', async () => {
    const calls = []
    const { stomp } = setup(readersCalling(calls))
    await flushPromises()
    calls.length = 0
    for (let t = 0; t <= MAX_WAIT_MS; t += 300) {
      act(() => stomp.emit('/topic/events', { module: 'loadbalancing' }))
      await act(async () => vi.advanceTimersByTime(300))
    }
    expect(calls).toContain('loadbalancing')
  })

  it('a failed read is reported as an error for that source only', async () => {
    const calls = []
    const { hook } = setup(readersCalling(calls, { matrix: () => Promise.reject(new Error('down')) }))
    await flushPromises()
    expect(hook.result.current.overviews.matrix).toEqual({ status: 'error', data: null })
    expect(hook.result.current.overviews.mpi.status).toBe('ok')
  })

  it('an older reply that lands after a newer one is dropped', async () => {
    const pending = []
    const calls = []
    const readers = readersCalling(calls, {
      election: () => new Promise((resolve) => pending.push(resolve)),
    })
    const { stomp, hook } = setup(readers)
    act(() => stomp.emit('/topic/events', { module: 'election' }))
    await act(async () => vi.advanceTimersByTime(SETTLE_MS))
    await flushPromises()
    expect(pending).toHaveLength(2)
    await act(async () => pending[1]({ leaderId: 4 }))
    await act(async () => pending[0]({ leaderId: 5 }))
    expect(hook.result.current.overviews.election.data).toEqual({ leaderId: 4 })
  })

  it('reads everything again after a reconnect, but not on the first connect', async () => {
    const calls = []
    const { stomp } = setup(readersCalling(calls))
    await flushPromises()
    calls.length = 0
    act(() => stomp.connect())
    await flushPromises()
    expect(calls).toEqual([])
    act(() => stomp.connect())
    await flushPromises()
    expect(calls).toHaveLength(9)
  })
})
