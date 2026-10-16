import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import overviewAfter from '@/test/fixtures/matrix/overview-after-start.json'
import overviewBefore from '@/test/fixtures/matrix/overview-before-start.json'
import series from '@/test/fixtures/matrix/series.json'
import seriesBefore from '@/test/fixtures/matrix/series-before-runs.json'
import seriesMore from '@/test/fixtures/matrix/series-after-more-runs.json'
import { formatSpeedup } from './matrixModel'
import { MIN_BAR, SpeedupChart } from './SpeedupChart'

afterEach(cleanup)

const copy = (value) => JSON.parse(JSON.stringify(value))

describe('SpeedupChart', () => {
  it('prints the served speedup on every bar of the real series, in the served size order', () => {
    render(<SpeedupChart series={series} overview={overviewAfter} />)
    expect(screen.getAllByTestId('speedup-value').map((t) => t.textContent)).toEqual(series.points.map((p) => formatSpeedup(p.speedup)))
    expect(screen.getByTestId('speedup-list').textContent).toContain(String(series.points[0].speedup))
  })

  it('draws the smallest real speedup at least the minimum height and says so in the legend', () => {
    render(<SpeedupChart series={series} overview={overviewAfter} />)
    const heights = screen.getAllByTestId('speedup-bar').map((bar) => Number(bar.querySelector('rect').getAttribute('height')))
    expect(Math.min(...heights)).toBeGreaterThanOrEqual(MIN_BAR)
    expect(screen.getByText(/drawn at 2 px so it stays visible; the number above it is the measured value/)).toBeTruthy()
  })

  it('marks 1.0 and the served ceiling', () => {
    render(<SpeedupChart series={series} overview={overviewAfter} />)
    expect(screen.getByTestId('line-one').textContent).toContain('1.0: as fast as sequential')
    expect(screen.getByTestId('line-ceiling').textContent).toContain(String(overviewAfter.speedupCeiling))
  })

  it('shows every size as "not run" before any run, never as 0, with the served progress and rule', () => {
    render(<SpeedupChart series={seriesBefore} overview={overviewBefore} />)
    expect(screen.getAllByTestId('speedup-gap')).toHaveLength(seriesBefore.seriesSizes.length)
    expect(screen.queryAllByTestId('speedup-bar')).toHaveLength(0)
    expect(screen.getAllByText('not run')).toHaveLength(seriesBefore.seriesSizes.length)
    expect(screen.getByTestId('series-progress').textContent).toBe(`0 of ${seriesBefore.sizesRequired} sizes needed have a speedup`)
    expect(screen.getByText(seriesBefore.rule)).toBeTruthy()
  })

  it('names the newest outcome of a gap (synthetic gap)', () => {
    const withGap = copy(series)
    withGap.points = withGap.points.slice(1)
    withGap.gaps = [{ size: series.seriesSizes[0], newestRunId: 7, newestOutcome: 'PARTIAL' }]
    render(<SpeedupChart series={withGap} overview={overviewAfter} />)
    expect(screen.getByText('run 7: partial')).toBeTruthy()
  })

  it('shows the ranks note when the points used different node counts, and the series basis', () => {
    render(<SpeedupChart series={seriesMore} overview={overviewAfter} />)
    expect(screen.getByTestId('ranks-note').textContent).toBe(seriesMore.ranksNote)
    expect(screen.getByText(seriesMore.basis)).toBeTruthy()
    expect(screen.getByTestId('series-progress').textContent).toContain('enough for the experiment')
  })
})
