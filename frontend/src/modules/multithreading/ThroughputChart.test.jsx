import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { ThroughputChart } from './ThroughputChart'

afterEach(cleanup)

const samples = [
  { at: 1, value: 0 },
  { at: 2, value: 2.5 },
  { at: 3, value: 3.33 },
]

describe('ThroughputChart', () => {
  it('draws one point per sample in an SVG image with a text summary', () => {
    render(<ThroughputChart samples={samples} />)

    const image = screen.getByRole('img')
    expect(image.getAttribute('aria-label')).toBe(
      'Throughput over the last 3 samples; latest 3.33 requests per second, highest 3.33.',
    )
    expect(image.querySelector('polyline').getAttribute('points').split(' ')).toHaveLength(3)
    expect(screen.getByText('latest 3.33')).toBeTruthy()
  })

  it('skips samples that are not finite numbers', () => {
    render(<ThroughputChart samples={[...samples, { at: 4, value: Number.NaN }]} />)

    expect(screen.getByRole('img').querySelector('polyline').getAttribute('points').split(' ')).toHaveLength(3)
    expect(screen.getByTestId('throughput-chart').textContent).not.toContain('NaN')
  })

  it('guides the user until there are two samples', () => {
    render(<ThroughputChart samples={[{ at: 1, value: 1 }]} />)

    expect(screen.queryByRole('img')).toBeNull()
    expect(screen.getByText('Throughput is drawn here while a batch runs.')).toBeTruthy()
  })
})
