import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import overviewBefore from '@/test/fixtures/loadbalancing/overview-before.json'
import overviewAfterRun from '@/test/fixtures/loadbalancing/overview-after-run.json'
import overviewAfterComparison from '@/test/fixtures/loadbalancing/overview-after-comparison.json'
import overviewAfterCrashRun from '@/test/fixtures/loadbalancing/overview-after-crash-run.json'
import { BalancerFlow } from './BalancerFlow'
import { flowModel } from './flowModel'
import { formatMillis } from './labels'

afterEach(cleanup)

const text = () => screen.getByTestId('balancer-flow').textContent

describe('BalancerFlow', () => {
  it('before any run: five labelled lanes, guidance, and no numbers', () => {
    render(<BalancerFlow model={flowModel(overviewBefore)} />)

    const lanes = within(screen.getByRole('list', { name: 'Workers' })).getAllByRole('listitem')
    expect(lanes).toHaveLength(5)
    expect(lanes[2].getAttribute('aria-label')).toBe('Node 3, Slow, 1 thread, 4× work; Up; No requests yet')
    expect(text()).toContain('Nothing has run yet. Run a strategy to see where the requests go.')
    expect(text()).toContain('Average —')
    expect(text()).not.toMatch(/NaN|undefined|Infinity/)
  })

  it('after a run: every bar has its number and share as text, and the latency in ms', () => {
    render(<BalancerFlow model={flowModel(overviewAfterRun)} />)

    for (let id = 1; id <= 5; id++) {
      expect(screen.getByTestId(`lane-${id}`).textContent).toContain('12 requests, 20%')
    }
    const average = overviewAfterRun.latestRun.report.nodes[0].averageLatencyMillis
    expect(screen.getByTestId('lane-1').textContent).toContain(`Average ${formatMillis(average)} ms`)
    expect(text()).toContain('Round robin: 60 requests served, 0 failed.')
  })

  it('after a crash run: the crashed node says so in words, with its refused attempts', () => {
    render(<BalancerFlow model={flowModel(overviewAfterCrashRun)} />)

    const node3 = screen.getByTestId('lane-3')
    expect(node3.textContent).toContain('Crashed')
    expect(node3.textContent).toContain('6 attempts refused, rerouted')
    expect(node3.getAttribute('aria-label')).toContain('Crashed; 1 request, 2%; 6 attempts refused, rerouted')
  })

  it('after a comparison: a named radio group picks the phase to draw', () => {
    const onPhaseChange = vi.fn()
    const { rerender } = render(<BalancerFlow model={flowModel(overviewAfterComparison)} onPhaseChange={onPhaseChange} />)

    const picker = screen.getByRole('group', { name: 'Show the split for' })
    const radios = within(picker).getAllByRole('radio')
    expect(radios.map((radio) => radio.closest('label').textContent))
      .toEqual(['Round robin', 'Weighted round robin', 'Least connections', 'Least response time'])
    expect(within(picker).getByLabelText('Round robin').checked).toBe(true)

    fireEvent.click(within(picker).getByLabelText('Least connections'))
    expect(onPhaseChange).toHaveBeenCalledWith('LEAST_CONNECTIONS')

    rerender(<BalancerFlow model={flowModel(overviewAfterComparison, 'LEAST_CONNECTIONS')} onPhaseChange={onPhaseChange} />)
    const lc = overviewAfterComparison.latestComparison.phases.find((p) => p.strategy === 'LEAST_CONNECTIONS')
    expect(screen.getByTestId('lane-1').textContent).toContain(`${lc.nodes[0].requests} requests`)
  })

  it('a failed run: shows its error text', () => {
    const failed = JSON.parse(JSON.stringify(overviewAfterRun))
    failed.latestRun.state = 'FAILED'
    failed.latestRun.report = null
    failed.latestRun.error = 'broke after ten'

    render(<BalancerFlow model={flowModel(failed)} />)

    expect(screen.getByRole('alert').textContent).toBe('Failed. broke after ten')
  })

  it('an empty worker list: says so instead of drawing nothing', () => {
    render(<BalancerFlow model={flowModel({ ...overviewBefore, workers: [] })} />)

    expect(text()).toContain('No workers are listed yet.')
  })

  it('announces its summary politely, from inside the positioned figure', () => {
    render(<BalancerFlow model={flowModel(overviewAfterRun)} />)

    const live = document.querySelector('[aria-live="polite"]')
    expect(live.textContent).toBe('Round robin: 60 requests served, 0 failed.')
    expect(live.closest('figure').className).toContain('relative')
  })
})
