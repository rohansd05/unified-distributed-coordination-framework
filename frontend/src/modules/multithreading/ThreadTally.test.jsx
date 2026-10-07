import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import requestsFixture from '@/test/fixtures/multithreading/requests.json'
import { ThreadTally } from './ThreadTally'

afterEach(cleanup)

describe('ThreadTally', () => {
  it('counts the real requests per worker thread, most first, and they add up', () => {
    render(<ThreadTally requests={requestsFixture} />)

    const rows = [...screen.getByTestId('thread-tally').querySelectorAll('li')]
    const threads = new Set(requestsFixture.map((request) => request.threadName))
    expect(rows).toHaveLength(threads.size)
    const counts = rows.map((row) => Number(row.lastElementChild.textContent.match(/^(\d+) requests?$/)[1]))
    expect(counts).toEqual([...counts].sort((a, b) => b - a))
    expect(counts.reduce((sum, count) => sum + count, 0)).toBe(requestsFixture.length)
    expect(rows[0].querySelector('.font-mono').textContent).toMatch(/^udcf-worker-n1-\d$/)
  })

  it('leaves out requests that never got a thread', () => {
    render(<ThreadTally requests={[{ id: 'a', threadName: null }, { id: 'b', threadName: 'udcf-worker-n3-1' }]} />)

    expect(screen.getByTestId('thread-tally').querySelectorAll('li')).toHaveLength(1)
  })

  it('says so when no request has run yet, and while loading', () => {
    const { rerender } = render(<ThreadTally requests={[]} />)
    expect(screen.getByText('No request has run on a worker thread yet.')).toBeTruthy()

    rerender(<ThreadTally requests={null} />)
    expect(screen.getByText(/Loading this node/)).toBeTruthy()
  })
})
