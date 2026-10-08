import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import overviewBefore from '@/test/fixtures/replication/overview-before.json'
import overviewAfterAsync from '@/test/fixtures/replication/overview-after-async.json'
import overviewAfterTakeover from '@/test/fixtures/replication/overview-after-takeover.json'
import { HealthTable } from './HealthTable'

afterEach(() => {
  cleanup()
})

describe('HealthTable', () => {
  it('no primary yet: says nothing has been measured (real fresh overview)', () => {
    render(<HealthTable overview={overviewBefore} />)
    expect(screen.getByTestId('health-table').textContent).toContain('No primary yet, so nothing has been measured.')
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('one row per backup, measured by the current primary, with real counts and measured latencies', () => {
    render(<HealthTable overview={overviewAfterAsync} />)

    expect(screen.getByRole('table').querySelector('caption').textContent).toContain('Measured by node 1, the current primary')
    const rows = screen.getAllByTestId('health-row')
    expect(rows).toHaveLength(overviewAfterAsync.health.length)
    const first = overviewAfterAsync.health[0]
    expect(within(rows[0]).getByRole('rowheader').textContent).toBe(`Node ${first.backupNodeId}`)
    expect(rows[0].textContent).not.toContain('Not measured yet')
    expect(within(rows[0]).getAllByRole('cell').map((cell) => cell.getAttribute('data-label'))).toEqual([
      'Acknowledged', 'Applied', 'Already held', 'Stale rejections', 'Older epoch', 'Failures',
      'Average latency (ms)', 'Maximum latency (ms)', 'Last sync',
    ])
  })

  it('a value that was not measured is a dash read as "Not measured yet", never 0 (derived: latencies and last sync null)', () => {
    const derived = JSON.parse(JSON.stringify(overviewAfterTakeover))
    derived.health = derived.health.map((row) => ({ ...row, acks: 0, applied: 0, averageLatencyMillis: null, maxLatencyMillis: null, lastSync: null }))
    render(<HealthTable overview={derived} />)

    const row = screen.getAllByTestId('health-row')[0]
    const notMeasured = within(row).getAllByLabelText('Not measured yet')
    expect(notMeasured).toHaveLength(3)
    expect(notMeasured.every((cell) => cell.textContent === '—')).toBe(true)
    const cells = within(row).getAllByRole('cell')
    expect(cells[0].textContent).toBe('0')   // a real count of zero stays 0
    expect(cells[6].textContent).toBe('—')
  })

  it('stays inside a positioned container (the mobile header is screen-reader only)', () => {
    render(<HealthTable overview={overviewAfterAsync} />)
    expect(screen.getByTestId('health-table').className).toContain('relative')
  })
})
