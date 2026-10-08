import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import overviewAfterComparison from '@/test/fixtures/loadbalancing/overview-after-comparison.json'
import { ComparisonTable } from './ComparisonTable'
import { findingSentences } from './finding'
import { formatMillis, strategyLabel, strategyPhrase } from './labels'

afterEach(cleanup)

/** A deep copy of the real comparison, so a test can change one flag without touching the fixture file. */
const real = () => JSON.parse(JSON.stringify(overviewAfterComparison.latestComparison))
const phase = (comparison, strategy) => comparison.phases.find((p) => p.strategy === strategy)
const ms = (value) => `${formatMillis(value)} ms`

describe('ComparisonTable', () => {
  it('without a comparison: guidance, no table', () => {
    render(<ComparisonTable comparison={null} />)

    expect(screen.getByTestId('comparison-table').textContent).toMatch(/^No comparison yet\./)
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('a finished comparison: four rows in order, fastest and slowest marked with words', () => {
    const comparison = real()
    render(<ComparisonTable comparison={comparison} note="Warm-up note." />)

    const rows = within(screen.getByRole('table')).getAllByRole('row').slice(1)
    expect(rows.map((row) => within(row).getByRole('rowheader').textContent.replace(/(Fastest|Slowest)/g, '')))
      .toEqual(['Round robin', 'Weighted round robin', 'Least connections', 'Least response time'])
    expect(screen.getByTestId(`row-${comparison.finding.fastest}`).textContent).toContain('Fastest')
    expect(screen.getByTestId(`row-${comparison.finding.slowest}`).textContent).toContain('Slowest')
    const rr = phase(comparison, 'ROUND_ROBIN')
    expect(screen.getByTestId('row-ROUND_ROBIN').textContent).toContain(ms(rr.makespanMillis))
    const split = within(screen.getByTestId('row-ROUND_ROBIN')).getByRole('list', { name: 'Requests per node' })
    expect(within(split).getAllByRole('listitem').map((li) => li.textContent))
      .toEqual(rr.nodes.map((n) => `Node ${n.nodeId}: ${n.requests}${n.nodeId}`))
    expect(screen.getByText('Warm-up note.')).toBeTruthy()
  })

  it('roundRobinFinishedLast true (the real capture): says so, with the measured numbers beside it', () => {
    const comparison = real()
    expect(comparison.finding.roundRobinFinishedLast).toBe(true)
    const fastest = phase(comparison, comparison.finding.fastest)

    const sentences = findingSentences(comparison)

    expect(sentences[0]).toBe(`Round robin finished last: ${ms(phase(comparison, 'ROUND_ROBIN').makespanMillis)}, against `
      + `${ms(fastest.makespanMillis)} for ${strategyPhrase(comparison.finding.fastest)}.`)
    render(<ComparisonTable comparison={comparison} />)
    expect(screen.getByTestId('finding').textContent).toMatch(/Round robin finished last: \d+ ms/)
  })

  it('roundRobinFinishedLast false (only that flag changed): never claims it, states what was measured', () => {
    const comparison = real()
    comparison.finding.roundRobinFinishedLast = false

    const sentences = findingSentences(comparison)
    render(<ComparisonTable comparison={comparison} />)

    expect(screen.getByTestId('comparison-table').textContent).not.toMatch(/round robin finished last/i)
    expect(sentences[0]).toMatch(/^In this comparison round robin did not finish last: .+ was fastest \(\d+ ms\), .+ was slowest \(\d+ ms\), and round robin took \d+ ms\.$/)
  })

  it('roundRobinMostEven and the gain are each stated only when the flag is set', () => {
    const comparison = real()
    expect(findingSentences(comparison).join(' ')).toContain('the most even of the four (spread 0)')
    expect(findingSentences(comparison).join(' '))
      .toContain(`${strategyLabel(comparison.finding.fastest)} finished ${comparison.finding.gainOverRoundRobinPercent}% sooner than round robin.`)

    comparison.finding.roundRobinMostEven = false
    comparison.finding.gainOverRoundRobinPercent = null

    const text = findingSentences(comparison).join(' ')
    expect(text).not.toContain('most even')
    expect(text).not.toContain('sooner')
  })

  it('a running comparison with two phases: progress, waiting rows, and no finding', () => {
    const comparison = real()
    comparison.state = 'RUNNING'
    comparison.phases = comparison.phases.slice(0, 2)
    comparison.finding = null

    render(<ComparisonTable comparison={comparison} />)

    expect(screen.getByRole('status').textContent).toBe('Comparison running: 2 of 4 strategies measured.')
    expect(screen.getByTestId('row-LEAST_CONNECTIONS').textContent).toContain('Waiting to run')
    expect(screen.queryByTestId('finding')).toBeNull()
    expect(findingSentences(comparison)).toEqual([])
  })

  it('a failed comparison: its error, and no finding', () => {
    const comparison = real()
    comparison.state = 'FAILED'
    comparison.error = 'gateway broke'

    render(<ComparisonTable comparison={comparison} />)

    expect(screen.getByRole('alert').textContent).toBe('The comparison failed. gateway broke')
    expect(screen.queryByTestId('finding')).toBeNull()
  })

  it('null latencies show "—", never 0 or NaN', () => {
    const comparison = real()
    for (const p of comparison.phases) {
      p.averageLatencyMillis = null
      p.p95LatencyMillis = null
      p.maxLatencyMillis = null
    }

    render(<ComparisonTable comparison={comparison} />)

    expect(screen.getByTestId('row-ROUND_ROBIN').textContent).toContain('—')
    expect(screen.getByTestId('comparison-table').textContent).not.toMatch(/NaN|undefined|Infinity/)
  })
})
