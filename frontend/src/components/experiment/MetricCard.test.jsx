import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { MetricCard } from './MetricCard'
import { isAvailable } from './isAvailable'

afterEach(cleanup)

describe('MetricCard', () => {
  it('shows the label, the value with its unit, and the hint', () => {
    render(<MetricCard label="Throughput" value={3.33} unit="per second" hint="Over a sliding window" />)

    const card = screen.getByTestId('metric-card')
    expect(card.textContent).toContain('Throughput')
    expect(card.textContent).toContain('3.33')
    expect(card.textContent).toContain('per second')
    expect(card.textContent).toContain('Over a sliding window')
  })

  it.each([null, undefined, Number.NaN, Number.POSITIVE_INFINITY, ''])(
    'shows "—" for a missing value (%s), never 0, NaN or undefined',
    (value) => {
      render(<MetricCard label="Queued" value={value} unit="of 200" />)

      const card = screen.getByTestId('metric-card')
      expect(screen.getByLabelText('Not available').textContent).toBe('—')
      expect(card.textContent).not.toMatch(/NaN|undefined|Infinity|of 200|\b0\b/)
    },
  )

  it('shows a real 0 as 0', () => {
    render(<MetricCard label="Busy threads" value={0} />)

    expect(screen.getByTestId('metric-card').textContent).toContain('0')
    expect(screen.queryByLabelText('Not available')).toBeNull()
  })

  it('adds a Simulated badge, with the reason when one is given', () => {
    render(<MetricCard label="Drift" value={12} simulated="The drift is configured." />)

    expect(screen.getByTestId('simulated-badge')).toBeTruthy()
    expect(screen.getByRole('tooltip', { hidden: true }).textContent).toBe('The drift is configured.')
  })

  it('has no badge unless simulated', () => {
    render(<MetricCard label="Completed" value={100} />)

    expect(screen.queryByTestId('simulated-badge')).toBeNull()
  })
})

describe('isAvailable', () => {
  it('accepts finite numbers, including 0, and non-empty strings', () => {
    expect(isAvailable(0)).toBe(true)
    expect(isAvailable(12.5)).toBe(true)
    expect(isAvailable('232')).toBe(true)
  })

  it('rejects null, undefined, NaN, Infinity and the empty string', () => {
    for (const value of [null, undefined, Number.NaN, Number.NEGATIVE_INFINITY, '']) {
      expect(isAvailable(value)).toBe(false)
    }
  })
})
