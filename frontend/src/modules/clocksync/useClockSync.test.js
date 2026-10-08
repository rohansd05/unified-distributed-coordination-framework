import { act, renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { useClockSync } from './useClockSync'

// Mock useStomp
const mockSubscribe = vi.fn().mockReturnValue(() => {})
const mockOnConnect = vi.fn().mockReturnValue(() => {})

vi.mock('@/hooks/useStomp', () => ({
  useStomp: () => ({
    status: 'connected',
    subscribe: mockSubscribe,
    onConnect: mockOnConnect,
  }),
}))

describe('useClockSync', () => {
  function makeApi(overviewData, timelineData, verificationData) {
    return {
      getOverview: vi.fn().mockResolvedValue(overviewData ?? { status: 'IDLE', actionInProgress: null, nodes: [] }),
      getTimeline: vi.fn().mockResolvedValue(timelineData ?? { events: [], limit: 100 }),
      getVerification: vi.fn().mockResolvedValue(verificationData ?? { passed: true, violationsCount: 0 }),
    }
  }

  it('loads overview and timeline on mount', async () => {
    const api = makeApi({ status: 'IDLE', timeDaemonNodeId: 1, nodes: [{ nodeId: 1 }] })
    const { result } = renderHook(() => useClockSync({ api }))

    await act(async () => {})

    expect(api.getOverview).toHaveBeenCalled()
    expect(api.getTimeline).toHaveBeenCalled()
    expect(result.current.loading).toBe(false)
    expect(result.current.overview?.timeDaemonNodeId).toBe(1)
  })

  it('coalesces rapid refreshes so at most one is in flight and one is queued', async () => {
    let resolveFirst
    const firstPromise = new Promise((resolve) => {
      resolveFirst = resolve
    })

    const api = {
      getOverview: vi.fn().mockImplementationOnce(() => firstPromise).mockResolvedValue({ status: 'IDLE' }),
      getTimeline: vi.fn().mockResolvedValue({ events: [] }),
      getVerification: vi.fn().mockResolvedValue({ passed: true }),
    }

    const { result } = renderHook(() => useClockSync({ api }))

    // Trigger multiple rapid refresh requests while first is still unresolved
    act(() => {
      result.current.refresh()
      result.current.refresh()
      result.current.refresh()
    })

    expect(api.getOverview).toHaveBeenCalledTimes(1)

    // Resolve the first call
    await act(async () => {
      resolveFirst({ status: 'IDLE' })
    })

    // Queued call should have run exactly once more
    expect(api.getOverview).toHaveBeenCalledTimes(2)
  })

  it('triggers verification once when a traffic session transitions to IDLE', async () => {
    const api = {
      getOverview: vi
        .fn()
        // 1. Initial idle
        .mockResolvedValueOnce({ status: 'IDLE', actionInProgress: null })
        // 2. Traffic running
        .mockResolvedValueOnce({ status: 'BUSY', actionInProgress: 'Random traffic burst session' })
        // 3. Traffic ended
        .mockResolvedValueOnce({ status: 'IDLE', actionInProgress: null }),
      getTimeline: vi.fn().mockResolvedValue({ events: [] }),
      getVerification: vi.fn().mockResolvedValue({ passed: true, violationsCount: 0 }),
    }

    const { result } = renderHook(() => useClockSync({ api }))
    await act(async () => {})
    expect(api.getVerification).not.toHaveBeenCalled()

    // 2. Traffic starts
    await act(async () => {
      result.current.refresh()
    })
    expect(api.getVerification).not.toHaveBeenCalled()

    // 3. Traffic ends
    await act(async () => {
      result.current.refresh()
    })
    // Verification should be triggered once upon traffic session completion
    expect(api.getVerification).toHaveBeenCalledTimes(1)
  })

  it('notifies onRoundCompleted when Berkeley round finishes and status leaves busy', async () => {
    const onRoundCompleted = vi.fn()
    const api = {
      getOverview: vi
        .fn()
        // 1. Initial with round 1
        .mockResolvedValueOnce({ status: 'IDLE', actionInProgress: null, latestRound: { roundId: 1 } })
        // 2. Round 2 running
        .mockResolvedValueOnce({ status: 'BUSY', actionInProgress: 'Berkeley round coordinated', latestRound: { roundId: 1 } })
        // 3. Round 2 finished
        .mockResolvedValueOnce({ status: 'IDLE', actionInProgress: null, latestRound: { roundId: 2, spreadAfterMillis: 2 } }),
      getTimeline: vi.fn().mockResolvedValue({ events: [] }),
      getVerification: vi.fn().mockResolvedValue({ passed: true }),
    }

    const { result } = renderHook(() => useClockSync({ api, onRoundCompleted }))
    await act(async () => {})

    // Round running
    await act(async () => {
      result.current.refresh()
    })

    // Round completed
    await act(async () => {
      result.current.refresh()
    })

    expect(onRoundCompleted).toHaveBeenCalledWith(expect.objectContaining({ roundId: 2, spreadAfterMillis: 2 }))
  })

  it('does not request verification on mount or on polling ticks', async () => {
    vi.useFakeTimers()
    const api = makeApi({ status: 'BUSY', actionInProgress: 'Running local event' })

    renderHook(() => useClockSync({ api }))
    await act(async () => {})

    expect(api.getVerification).not.toHaveBeenCalled()

    // Advance 3 polling ticks (3 seconds)
    await act(async () => {
      vi.advanceTimersByTime(3000)
    })

    expect(api.getVerification).not.toHaveBeenCalled()
    vi.useRealTimers()
  })

  it('requests verification exactly once when the explicit verify function is called', async () => {
    const api = makeApi()
    const { result } = renderHook(() => useClockSync({ api }))
    await act(async () => {})

    expect(api.getVerification).not.toHaveBeenCalled()

    await act(async () => {
      await result.current.verify()
    })

    expect(api.getVerification).toHaveBeenCalledTimes(1)
    expect(result.current.verification).toEqual({ passed: true, violationsCount: 0 })
  })

  it('polls only while an action is in progress and stops after it finishes', async () => {
    vi.useFakeTimers()
    const api = {
      getOverview: vi
        .fn()
        // 1. Initial IDLE - no polling
        .mockResolvedValueOnce({ status: 'IDLE', actionInProgress: null })
        // 2. Action starts -> BUSY
        .mockResolvedValueOnce({ status: 'BUSY', actionInProgress: 'Processing' })
        // 3. Polling tick 1
        .mockResolvedValueOnce({ status: 'BUSY', actionInProgress: 'Processing' })
        // 4. Polling tick 2 -> FINISHED / IDLE
        .mockResolvedValueOnce({ status: 'IDLE', actionInProgress: null }),
      getTimeline: vi.fn().mockResolvedValue({ events: [] }),
      getVerification: vi.fn().mockResolvedValue({ passed: true }),
    }

    const { result } = renderHook(() => useClockSync({ api }))
    await act(async () => {})

    // On initial IDLE, advancing timer should NOT trigger polling
    expect(api.getOverview).toHaveBeenCalledTimes(1)
    await act(async () => {
      vi.advanceTimersByTime(3000)
    })
    expect(api.getOverview).toHaveBeenCalledTimes(1)

    // Trigger refresh to transition to BUSY
    await act(async () => {
      result.current.refresh()
    })
    expect(api.getOverview).toHaveBeenCalledTimes(2)

    // Now busy: timer tick should trigger polling
    await act(async () => {
      vi.advanceTimersByTime(1000)
    })
    expect(api.getOverview).toHaveBeenCalledTimes(3)

    // Next timer tick transitions back to IDLE
    await act(async () => {
      vi.advanceTimersByTime(1000)
    })
    expect(api.getOverview).toHaveBeenCalledTimes(4)

    // After returning to IDLE, advancing timer should NOT trigger further polling
    await act(async () => {
      vi.advanceTimersByTime(5000)
    })
    expect(api.getOverview).toHaveBeenCalledTimes(4)

    vi.useRealTimers()
  })
})

