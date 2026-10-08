import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import overviewAfter from '@/test/fixtures/multithreading/overview-after-batch.json'
import overviewBefore from '@/test/fixtures/multithreading/overview-before.json'
import { ExecutorView } from './ExecutorView'
import { executorSummary } from './executorSummary'

afterEach(cleanup)

const fastAfter = overviewAfter.nodes[0]
const busyFast = {
  ...fastAfter,
  stats: { ...fastAfter.stats, activeThreads: 3, queuedRequests: 12 },
}

describe('ExecutorView', () => {
  it('draws one lane per worker thread and lights as many as the executor says are busy', () => {
    render(<ExecutorView node={busyFast} />)

    const lanes = within(screen.getByRole('list', { name: 'Worker threads' })).getAllByRole('listitem')
    expect(lanes).toHaveLength(4)
    expect(lanes.map((lane) => lane.getAttribute('aria-label'))).toEqual([
      'Thread lane 1 of 4: busy',
      'Thread lane 2 of 4: busy',
      'Thread lane 3 of 4: busy',
      'Thread lane 4 of 4: idle',
    ])
    expect(lanes[0].textContent).toContain('busy')   // a word, not only a colour
  })

  it('names the queue and gives its value as text', () => {
    render(<ExecutorView node={busyFast} />)

    const queue = screen.getByRole('meter', { name: 'Queue' })
    expect(queue.getAttribute('aria-valuenow')).toBe('12')
    expect(queue.getAttribute('aria-valuemax')).toBe('200')
    expect(queue.getAttribute('aria-valuetext')).toBe('12 of 200 queued')
    expect(screen.getByText('12 of 200 waiting')).toBeTruthy()
  })

  it('summarises the state for screen readers in a polite live region', () => {
    render(<ExecutorView node={busyFast} />)

    const live = document.querySelector('[aria-live="polite"]')
    expect(live.textContent).toBe('3 of 4 threads busy, 12 of 200 queued.')
  })

  it('shows the outcomes from the real stats', () => {
    render(<ExecutorView node={fastAfter} />)

    const outcomes = screen.getByText('Completed').closest('dl')
    expect(outcomes.textContent).toContain('Completed100')
    expect(outcomes.textContent).toContain('Rejected0')
  })

  it('shows "Not started yet" and no numbers for a node without stats', () => {
    render(<ExecutorView node={overviewBefore.nodes[2]} />)

    const view = screen.getByTestId('executor-view')
    expect(view.textContent).toContain('Not started yet')
    expect(screen.getByRole('meter', { name: 'Queue' }).getAttribute('aria-valuetext')).toBe('Not started yet')
    expect(screen.getByRole('meter', { name: 'Queue' }).hasAttribute('aria-valuenow')).toBe(false)
    expect(within(screen.getByRole('list', { name: 'Worker threads' })).getAllByRole('listitem')).toHaveLength(1)
    expect(view.textContent).not.toMatch(/NaN|undefined|\b0 of\b/)
  })

  it('shows a crashed node as crashed, with no executor numbers', () => {
    const crashed = { ...fastAfter, nodeStatus: 'CRASHED', serviceRunning: false, stats: null }
    render(<ExecutorView node={crashed} />)

    const view = screen.getByTestId('executor-view')
    expect(view.textContent).toContain('Crashed.')
    expect(screen.queryByRole('meter')).toBeNull()
    expect(view.textContent).not.toMatch(/NaN|undefined/)
  })

  it('renders nothing without a node', () => {
    const { container } = render(<ExecutorView node={undefined} />)

    expect(container.textContent).toBe('')
  })
})

describe('executorSummary', () => {
  it('reads busy threads and queue depth from the stats', () => {
    expect(executorSummary(busyFast)).toBe('3 of 4 threads busy, 12 of 200 queued.')
  })

  it('says when there is no executor to read', () => {
    expect(executorSummary(overviewBefore.nodes[0])).toBe('Node 1 has not started its executor yet.')
    expect(executorSummary({ ...fastAfter, nodeStatus: 'CRASHED' })).toBe('Node 1 is crashed; its executor is shut down.')
    expect(executorSummary(undefined)).toBe('No node selected.')
  })
})
