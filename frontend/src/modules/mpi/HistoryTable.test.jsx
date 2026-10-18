import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import history from '@/test/fixtures/mpi/collectives-history.json'
import { historyRows } from './mpiModel'
import { HistoryTable } from './HistoryTable'

afterEach(cleanup)

describe('HistoryTable', () => {
  it('lists collectives newest first with outcome, delivered count and total time (n/a when refused)', () => {
    render(<HistoryTable rows={historyRows(history)} historySize={20} selectedKey={null} onSelect={() => {}} />)
    const rows = screen.getAllByTestId('history-row')
    expect(rows).toHaveLength(7)
    expect(rows[0].textContent).toContain('Scatter of 3')
    expect(rows[0].textContent).toContain('Refused')
    expect(within(rows[0]).getByTestId('not-available').getAttribute('title')).toBe('Refused: nothing was sent')
    expect(rows[6].textContent).toContain('Partial')
    expect(rows[6].textContent).toContain('3 of 4')
    expect(rows[6].textContent).toContain('44.541 ms')
    expect(screen.getByText(/the backend keeps the latest 20/)).toBeTruthy()
  })

  it('selects a row by its key (id and start time)', () => {
    const onSelect = vi.fn()
    const rows = historyRows(history)
    render(<HistoryTable rows={rows} historySize={20} selectedKey={rows[5].key} onSelect={onSelect} />)
    expect(screen.getByRole('button', { name: 'Show collective 2' }).getAttribute('aria-current')).toBe('true')
    fireEvent.click(screen.getByRole('button', { name: 'Show collective 4' }))
    expect(onSelect).toHaveBeenCalledWith(rows[3])
  })

  it('guides the first step when nothing has run', () => {
    render(<HistoryTable rows={[]} historySize={20} selectedKey={null} onSelect={() => {}} />)
    expect(screen.getByTestId('history-empty').textContent).toContain('start with a broadcast')
  })
})
