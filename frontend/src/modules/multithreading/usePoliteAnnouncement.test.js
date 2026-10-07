import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ANNOUNCE_INTERVAL_MS, usePoliteAnnouncement } from './usePoliteAnnouncement'

describe('usePoliteAnnouncement', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('announces the first text at once', () => {
    const { result } = renderHook(({ text }) => usePoliteAnnouncement(text), { initialProps: { text: 'A' } })

    expect(result.current).toBe('A')
  })

  it('announces at most once every two seconds, then the latest text', () => {
    const { result, rerender } = renderHook(({ text }) => usePoliteAnnouncement(text), { initialProps: { text: 'A' } })

    rerender({ text: 'B' })
    act(() => vi.advanceTimersByTime(0))
    expect(result.current).toBe('B')   // the first change: nothing announced recently

    rerender({ text: 'C' })
    act(() => vi.advanceTimersByTime(500))
    rerender({ text: 'D' })
    act(() => vi.advanceTimersByTime(500))
    expect(result.current).toBe('B')   // still inside the two-second window

    act(() => vi.advanceTimersByTime(ANNOUNCE_INTERVAL_MS))
    expect(result.current).toBe('D')   // skipped C: only the current state is read out
  })

  it('leaves no timer behind after unmount', () => {
    const { rerender, unmount } = renderHook(({ text }) => usePoliteAnnouncement(text), { initialProps: { text: 'A' } })
    rerender({ text: 'B' })
    act(() => vi.advanceTimersByTime(0))
    rerender({ text: 'C' })

    unmount()

    expect(vi.getTimerCount()).toBe(0)
  })
})
