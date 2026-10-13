import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import callsHistory from '@/test/fixtures/rmi/calls-history.json'
import { HistoryTable } from './HistoryTable'
import { historyRows } from './rmiModel'

afterEach(cleanup)

describe('HistoryTable', () => {
  it('lists calls and comparisons newest first with outcome words and the kept count', () => {
    render(<HistoryTable rows={historyRows(callsHistory)} historySize={50} onSelect={() => {}} />)
    const rows = screen.getAllByTestId('history-row')
    expect(rows).toHaveLength(8)
    expect(rows[0].textContent).toContain('Failed: connection refused')
    expect(rows[1].textContent).toContain('comparison')
    expect(rows[7].textContent).toContain('Succeeded')
    expect(rows[7].textContent).toContain('7.237 ms')
    expect(screen.getByText(/the backend keeps the latest 50/)).toBeTruthy()
  })

  it('shows a failed call\'s round trip as n/a, never 0', () => {
    render(<HistoryTable rows={historyRows(callsHistory)} onSelect={() => {}} />)
    const failed = screen.getAllByTestId('history-row')[0]
    expect(within(failed).getByTestId('not-available')).toBeTruthy()
    expect(failed.lastElementChild.textContent).toBe('n/a (No round trip: the call did not complete)')
  })

  it('hands the clicked row to onSelect and marks the selected one', () => {
    const onSelect = vi.fn()
    const rows = historyRows(callsHistory)
    render(<HistoryTable rows={rows} selectedKey={rows[3].key} onSelect={onSelect} />)
    fireEvent.click(screen.getByRole('button', { name: 'Show call 5' }))
    expect(onSelect).toHaveBeenCalledWith(rows[3])
    expect(screen.getByRole('button', { name: 'Show call 5' }).getAttribute('aria-current')).toBe('true')
    expect(screen.getByRole('button', { name: 'Show comparison 7' })).toBeTruthy()
  })

  it('guides the first step when there is no call', () => {
    render(<HistoryTable rows={[]} onSelect={() => {}} />)
    expect(screen.getByTestId('history-empty').textContent).toBe('No calls yet: start with a ping to any node.')
  })
})
