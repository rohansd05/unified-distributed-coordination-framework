import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import runAccepted from '@/test/fixtures/mapreduce/run-accepted.json'
import sampleRun from '@/test/fixtures/mapreduce/run-sample-word-count.json'
import latencyRun from '@/test/fixtures/mapreduce/run-upload-avg-latency.json'
import truncatedRun from '@/test/fixtures/mapreduce/run-truncated-result.json'
import emptyRun from '@/test/fixtures/mapreduce/run-event-log-empty-result.json'
import { ResultsPanel } from './ResultsPanel'

afterEach(cleanup)

describe('ResultsPanel', () => {
  it('lists every row the backend sent, sorted by key, and says the list is complete', () => {
    render(<ResultsPanel run={sampleRun} rowsMax={200} />)
    const rows = within(screen.getByRole('table')).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(116)
    expect(rows[0].textContent).toBe('a27')
    expect(screen.getByTestId('results-count').textContent).toBe('All 116 keys.')
  })

  it('says when the backend truncated the list, with the real totals', () => {
    render(<ResultsPanel run={truncatedRun} rowsMax={200} />)
    expect(within(screen.getByRole('table')).getAllByRole('row')).toHaveLength(201)
    expect(screen.getByTestId('results-count').textContent)
      .toBe('Showing the first 200 of 300 keys, by key; the backend lists at most 200.')
  })

  it('draws the chart as one image with a summary of its data; the table is the full alternative', () => {
    render(<ResultsPanel run={latencyRun} rowsMax={200} />)
    const chart = screen.getByRole('img')
    expect(chart.getAttribute('aria-label')).toBe('Average latency for the 3 largest keys of 3 listed keys: node-3 23.6, node-2 9.32, node-1 4.05.')
    expect(chart.querySelectorAll('rect')).toHaveLength(3)
    expect(screen.getByRole('table').textContent).toContain('4.05 ms average over 151 events')
  })

  it('an empty result shows the backend\'s own notice, word for word', () => {
    render(<ResultsPanel run={emptyRun} rowsMax={200} />)
    expect(screen.getByTestId('results-empty').textContent)
      .toBe('No result keys.No line in this input has a node and a measured latency, so no average could be computed.')
    expect(screen.queryByRole('img')).toBeNull()
  })

  it('while running or after a failure, no result is invented', () => {
    const { rerender } = render(<ResultsPanel run={runAccepted} />)
    expect(screen.getByText('Results appear here when a run completes.')).toBeTruthy()
    rerender(<ResultsPanel run={{ ...runAccepted, state: 'FAILED' }} />)
    expect(screen.getByText('This run failed, so it has no results.')).toBeTruthy()
    expect(screen.queryByRole('table')).toBeNull()
  })
})
