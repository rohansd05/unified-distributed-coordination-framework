import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import overviewAfter from '@/test/fixtures/matrix/overview-after-start.json'
import complete100 from '@/test/fixtures/matrix/run-complete-100.json'
import failed from '@/test/fixtures/matrix/run-failed.json'
import refused from '@/test/fixtures/matrix/run-refused.json'
import { PhaseViews } from './PhaseViews'

afterEach(cleanup)

describe('PhaseViews', () => {
  it('shows the scatter on Experiment 9\'s map and latency chart, with the served texts and no second Simulated badge', () => {
    render(<PhaseViews overview={overviewAfter} run={complete100} />)
    expect(screen.getByRole('button', { name: 'Scatter of A' }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByTestId('map-sentence').textContent)
      .toBe(`Scatter ${complete100.scatter.collectiveId} from root node ${complete100.rootNodeId}: complete.`)
    expect(screen.getAllByTestId('fan-arrow')).toHaveLength(complete100.scatter.transfers.length - 1)
    expect(screen.getAllByTestId('latency-bar')).toHaveLength(complete100.scatter.transfers.length - 1)
    expect(screen.getByText(overviewAfter.phaseTimeLabel)).toBeTruthy()
    expect(screen.getByText(overviewAfter.collectiveIdsNote)).toBeTruthy()
    expect(screen.queryAllByTestId('simulated-badge')).toHaveLength(0)
  })

  it('switches to the broadcast and to the gather, which has a chart and no map', () => {
    render(<PhaseViews overview={overviewAfter} run={complete100} />)
    fireEvent.click(screen.getByRole('button', { name: 'Broadcast of B' }))
    expect(screen.getByTestId('map-sentence').textContent).toContain(`Broadcast ${complete100.broadcast.collectiveId}`)
    fireEvent.click(screen.getByRole('button', { name: 'Gather of C' }))
    expect(screen.queryByTestId('collective-map')).toBeNull()
    expect(screen.getByText(/is not an Experiment 9 collective/)).toBeTruthy()
    expect(screen.getAllByTestId('latency-bar')).toHaveLength(complete100.gather.filter((g) => g.status === 'DELIVERED').length)
    expect(screen.getByText(complete100.gatherLatencyBasis)).toBeTruthy()
  })

  it('shows a failed scatter as a failed arrow and a cross, never a latency bar', () => {
    render(<PhaseViews overview={overviewAfter} run={failed} />)
    expect(screen.getAllByTestId('fan-arrow').map((a) => a.dataset.status)).toEqual(['FAILED'])
    expect(screen.getAllByTestId('latency-failed')).toHaveLength(1)
    expect(screen.queryAllByTestId('latency-bar')).toHaveLength(0)
  })

  it('renders nothing for a refused run or no run', () => {
    const { container, rerender } = render(<PhaseViews overview={overviewAfter} run={refused} />)
    expect(container.textContent).toBe('')
    rerender(<PhaseViews overview={overviewAfter} run={null} />)
    expect(container.textContent).toBe('')
  })
})
