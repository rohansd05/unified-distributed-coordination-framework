import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import callsHistory from '@/test/fixtures/rmi/calls-history.json'
import callTimedOut from '@/test/fixtures/rmi/call-timed-out.json'
import { chartModel } from './rmiModel'
import { RoundTripChart } from './RoundTripChart'

afterEach(cleanup)

describe('RoundTripChart', () => {
  it('draws a bar for each completed call and a failure marker, not a zero bar, for each failed one', () => {
    render(<RoundTripChart model={chartModel(callsHistory)} />)
    const bars = screen.getAllByTestId('rt-bar')
    expect(bars).toHaveLength(7)
    expect(bars.every((bar) => Number(bar.getAttribute('height')) > 0)).toBe(true)
    const failures = screen.getAllByTestId('rt-failure')
    expect(failures).toHaveLength(1)
    expect(failures[0].querySelector('rect')).toBeNull()
    expect(failures[0].textContent).toContain('failed, connection refused')
  })

  it('lists every value in words and names the scale', () => {
    render(<RoundTripChart model={chartModel(callsHistory)} />)
    expect(screen.getByText('Call 1, ping to node 2: 7.237 ms', { selector: 'li' })).toBeTruthy()
    expect(screen.getByText('tallest bar 124.766 ms')).toBeTruthy()
  })

  it('shows only markers and no scale when every call failed, and guidance when there is no call', () => {
    const { unmount } = render(<RoundTripChart model={chartModel([{ id: 1, kind: 'CALL', call: callTimedOut, comparison: null }])} />)
    expect(screen.queryAllByTestId('rt-bar')).toHaveLength(0)
    expect(screen.getAllByTestId('rt-failure')).toHaveLength(1)
    expect(screen.queryByText(/tallest bar/)).toBeNull()
    unmount()
    render(<RoundTripChart model={chartModel([])} />)
    expect(screen.getByTestId('round-trip-chart').textContent).toContain('Start with a ping')
  })
})
