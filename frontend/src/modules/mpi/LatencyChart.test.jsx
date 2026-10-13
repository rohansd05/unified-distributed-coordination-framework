import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import broadcast from '@/test/fixtures/mpi/collective-broadcast.json'
import partial from '@/test/fixtures/mpi/collective-partial.json'
import refused from '@/test/fixtures/mpi/collective-refused.json'
import { chartModel } from './mpiModel'
import { LatencyChart } from './LatencyChart'

afterEach(cleanup)

const copy = (value) => JSON.parse(JSON.stringify(value))

describe('LatencyChart', () => {
  it('draws a bar per delivered rank and a dot for the root\'s local part, with the latency basis', () => {
    render(<LatencyChart model={chartModel(broadcast)} latencyBasis={broadcast.latencyBasis} />)
    expect(screen.getAllByTestId('latency-bar')).toHaveLength(4)
    expect(screen.getAllByTestId('latency-local')).toHaveLength(1)
    expect(screen.getByText(broadcast.latencyBasis)).toBeTruthy()
    expect(screen.getByText('tallest bar 6.817 ms')).toBeTruthy()
  })

  it('draws a failed rank as a cross, never a bar, with its time to failure in text, not as a latency', () => {
    render(<LatencyChart model={chartModel(partial)} latencyBasis={partial.latencyBasis} />)
    expect(screen.getAllByTestId('latency-bar')).toHaveLength(3)
    expect(screen.getAllByTestId('latency-failed')).toHaveLength(1)
    const list = screen.getByTestId('latency-list').textContent
    expect(list).toContain('Rank 4, node 5: failed, connection refused, after 21.944 ms (time to failure, not a latency)')
    expect(list).not.toContain('latency 21.944')
    const heights = screen.getAllByTestId('latency-bar').map((bar) => Number(bar.getAttribute('height')))
    expect(heights.every((h) => h >= 2)).toBe(true)
  })

  it('synthetic: draws a not-attempted rank as an empty outline (no capture has one)', () => {
    const c = copy(partial)
    c.transfers[4].status = 'NOT_ATTEMPTED'   // changed: was FAILED
    c.transfers[4].timeToFailureNanos = null   // changed: only FAILED has one
    render(<LatencyChart model={chartModel(c)} latencyBasis={c.latencyBasis} />)
    expect(screen.getAllByTestId('latency-not-attempted')).toHaveLength(1)
    expect(screen.queryByTestId('latency-failed')).toBeNull()
    expect(screen.getByTestId('latency-list').textContent).toContain('Rank 4, node 5: not attempted, no latency')
  })

  it('guides the first step when there is nothing to draw (no collective, or a refusal)', () => {
    render(<LatencyChart model={chartModel(null)} latencyBasis={null} />)
    expect(screen.getByTestId('latency-chart').textContent).toContain('Start with a broadcast')
    cleanup()
    render(<LatencyChart model={chartModel(refused)} latencyBasis={refused.latencyBasis} />)
    expect(screen.queryByTestId('latency-slot')).toBeNull()
  })
})
