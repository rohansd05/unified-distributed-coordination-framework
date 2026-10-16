import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import runsHistory from '@/test/fixtures/matrix/runs-history.json'
import { historyRows } from './matrixModel'
import { HistoryTable } from './HistoryTable'

afterEach(cleanup)

describe('HistoryTable', () => {
  it('lists the kept runs newest first with outcome, and a refused run\'s time as not measured', () => {
    render(<HistoryTable rows={historyRows(runsHistory)} historySize={20} selectedKey={null} onSelect={() => {}} />)
    const rows = screen.getAllByTestId('history-row')
    expect(rows).toHaveLength(runsHistory.length)
    expect(rows[0].textContent).toContain('Refused')
    expect(rows[0].textContent).toContain('Not measured')
    expect(screen.getByText(/keeps the latest 20 runs; ids restart at 1/)).toBeTruthy()
  })

  it('selects a row by its run id button, keyed by id and start time', () => {
    const onSelect = vi.fn()
    const rows = historyRows(runsHistory)
    render(<HistoryTable rows={rows} historySize={20} selectedKey={rows[1].key} onSelect={onSelect} />)
    expect(screen.getAllByTestId('history-row')[1].dataset.selected).toBe('true')
    fireEvent.click(screen.getByRole('button', { name: new RegExp(`show run ${rows[2].runId}$`) }))
    expect(onSelect).toHaveBeenCalledWith(rows[2])
  })

  it('says when there is no run', () => {
    render(<HistoryTable rows={[]} historySize={20} selectedKey={null} onSelect={() => {}} />)
    expect(screen.getByTestId('history-empty').textContent).toBe('No runs yet.')
  })
})
