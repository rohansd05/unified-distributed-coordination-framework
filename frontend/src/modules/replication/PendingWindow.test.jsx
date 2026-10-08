import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import overviewAfterAsync from '@/test/fixtures/replication/overview-after-async.json'
import writeAsync from '@/test/fixtures/replication/write-async.json'
import { PendingWindow } from './PendingWindow'
import { pendingWindow } from './replicaModel'

const pendingOverview = { ...JSON.parse(JSON.stringify(overviewAfterAsync)), latestWrite: writeAsync }

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('PendingWindow', () => {
  it('open while the backend says PENDING: what was written, the simulated delay, and the Simulated badge with the backend\'s reason', () => {
    render(<PendingWindow window={pendingWindow(pendingOverview)} />)

    const window = screen.getByTestId('pending-window')
    expect(window.textContent).toContain('Replication window open')
    expect(window.textContent).toContain('balance = 2000 was confirmed to the client after 6.1 ms')
    expect(window.textContent).toContain('after a 450 ms wait')
    const badge = within(window).getByTestId('simulated-badge')
    expect(within(badge).getByRole('tooltip').textContent).toBe(writeAsync.simulatedReason)
  })

  it('closed once the backend says COMPLETE (the real after-async overview): nothing is shown', () => {
    render(<PendingWindow window={pendingWindow(overviewAfterAsync)} />)
    expect(screen.queryByTestId('pending-window')).toBeNull()
  })

  it('closes from the backend\'s state, never from the timer: still open long after the delay has passed', () => {
    vi.useFakeTimers()
    const { rerender } = render(<PendingWindow window={pendingWindow(pendingOverview)} />)
    vi.advanceTimersByTime(10_000)
    rerender(<PendingWindow window={pendingWindow(pendingOverview)} />)
    expect(screen.getByTestId('pending-window')).toBeTruthy()

    rerender(<PendingWindow window={pendingWindow(overviewAfterAsync)} />)
    expect(screen.queryByTestId('pending-window')).toBeNull()
    vi.useRealTimers()
  })

  it('the bar is decoration: hidden from assistive technology, animated only when motion is allowed', () => {
    const animate = vi.fn(() => ({ cancel: vi.fn() }))
    const original = HTMLElement.prototype.animate
    HTMLElement.prototype.animate = animate
    window.matchMedia = vi.fn(() => ({ matches: true }))   // jsdom has none; prefers-reduced-motion
    try {
      render(<PendingWindow window={pendingWindow(pendingOverview)} />)
      expect(animate).not.toHaveBeenCalled()
      cleanup()

      window.matchMedia = vi.fn(() => ({ matches: false }))
      render(<PendingWindow window={pendingWindow(pendingOverview)} />)
      expect(animate).toHaveBeenCalledWith(expect.any(Array), expect.objectContaining({ duration: 450 }))
      const bar = screen.getByTestId('pending-window').querySelector('[aria-hidden="true"]')
      expect(bar).not.toBeNull()
    } finally {
      HTMLElement.prototype.animate = original
      delete window.matchMedia
    }
  })
})
