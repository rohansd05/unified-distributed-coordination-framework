import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import requestsFixture from '@/test/fixtures/multithreading/requests.json'
import { RequestTable, TABLE_ROWS } from './RequestTable'

afterEach(cleanup)

describe('RequestTable', () => {
  it('lists the real requests with id and thread in monospace and the status as a word', () => {
    render(<RequestTable requests={requestsFixture} />)

    const rows = within(screen.getByRole('table')).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(Math.min(TABLE_ROWS, requestsFixture.length))
    const first = within(rows[0]).getAllByRole('cell')
    expect(first[0].textContent).toBe(requestsFixture[0].id)
    expect(first[0].className).toContain('font-mono')
    expect(first[1].textContent).toBe(requestsFixture[0].threadName)
    expect(first[2].textContent).toBe('Mixed')
    expect(first[3].textContent).toBe('Completed')
    expect(first[6].textContent).toMatch(/^\d+(\.\d)? ms$/)
  })

  it('shows "—" for the times a rejected request never had, not 0', () => {
    const rejected = {
      id: 'r1', nodeId: 3, type: 'CPU_HASH', status: 'REJECTED', threadName: null,
      submittedAt: '2026-10-07T18:05:51Z', queueWaitMillis: 0, processingMillis: 0, totalMillis: 0.02,
      resultSummary: null, errorMessage: 'Queue full - node at capacity',
    }
    render(<RequestTable requests={[rejected]} />)

    const cells = within(within(screen.getByRole('table')).getAllByRole('row')[1]).getAllByRole('cell')
    expect(cells.map((cell) => cell.textContent)).toEqual([
      'r1', '—', 'Hashing (CPU)', 'Rejected', '—', '—', '—', 'Queue full - node at capacity',
    ])
  })

  it('shows "—" for the total of a request that has not finished', () => {
    const running = { ...requestsFixture[0], status: 'PROCESSING', totalMillis: 0, processingMillis: 0 }
    render(<RequestTable requests={[running]} />)

    const cells = within(within(screen.getByRole('table')).getAllByRole('row')[1]).getAllByRole('cell')
    expect(cells[3].textContent).toBe('Running')
    expect(cells[5].textContent).toBe('—')
    expect(cells[6].textContent).toBe('—')
  })

  it('guides the user for an empty list, and says when it is loading', () => {
    const { rerender } = render(<RequestTable requests={[]} />)
    expect(screen.getByText(/No requests on this node yet/)).toBeTruthy()
    expect(screen.queryByRole('table')).toBeNull()

    rerender(<RequestTable requests={null} />)
    expect(screen.getByText(/Loading this node/)).toBeTruthy()
  })
})
