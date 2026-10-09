import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import history from '@/test/fixtures/mapreduce/runs-history.json'
import overviewIdle from '@/test/fixtures/mapreduce/overview-idle.json'
import { RunHistory } from './RunHistory'

afterEach(cleanup)

describe('RunHistory', () => {
  it('lists the runs newest first with titles, times, keys, retries and the crash node', () => {
    render(<RunHistory runs={history} jobs={overviewIdle.jobs} selectedRunId="d74d11b8" pinnedRunId={null} onSelect={vi.fn()} />)
    const items = within(screen.getByTestId('run-history')).getAllByRole('button')
    expect(items).toHaveLength(5)
    expect(items[0].textContent).toContain('Word count')
    expect(items[0].textContent).toContain('2 retried')
    expect(items[0].textContent).toContain('crash on node 3')
    expect(items[0].getAttribute('aria-current')).toBe('true')
    expect(items[2].textContent).toContain('Events per category')
  })

  it('shows "—" for values the backend left null, never 0', () => {
    const running = { ...history[0], state: 'RUNNING', totalMillis: null, resultKeys: null, retriedTasks: null, crashWorkerId: null }
    render(<RunHistory runs={[running]} jobs={overviewIdle.jobs} selectedRunId={null} pinnedRunId={null} onSelect={vi.fn()} />)
    const text = within(screen.getByTestId('run-history')).getByRole('button').textContent
    expect(text).toContain('Running')
    expect(text).toContain('— keys')
    expect(text).toContain('— retried')
    expect(text).not.toContain('crash on node')
  })

  it('picking a row selects it; "Follow the latest run" selects null', () => {
    const onSelect = vi.fn()
    render(<RunHistory runs={history} jobs={overviewIdle.jobs} selectedRunId={null} pinnedRunId="cb187a2c" onSelect={onSelect} />)
    fireEvent.click(within(screen.getByTestId('run-history')).getAllByRole('button')[1])
    fireEvent.click(screen.getByRole('button', { name: 'Follow the latest run' }))
    expect(onSelect.mock.calls).toEqual([['cb187a2c'], [null]])
  })

  it('with no runs: guidance instead of a list', () => {
    render(<RunHistory runs={[]} selectedRunId={null} pinnedRunId={null} onSelect={vi.fn()} />)
    expect(screen.getByText('No runs yet. Each run you start is listed here.')).toBeTruthy()
  })
})
