import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import overviewBefore from '@/test/fixtures/mpi/overview-before-start.json'
import overviewAfter from '@/test/fixtures/mpi/overview-after-start.json'
import overviewCrashed from '@/test/fixtures/mpi/overview-node-crashed.json'
import startWithFailure from '@/test/fixtures/mpi/start-with-failure.json'
import { NodeStrip } from './NodeStrip'

afterEach(cleanup)

const copy = (value) => JSON.parse(JSON.stringify(value))

describe('NodeStrip', () => {
  it('shows n/a, never "none" or 0, for the stored ids of a node whose endpoint is not exported', () => {
    render(<NodeStrip participants={overviewBefore.participants} />)
    const stored = screen.getByTestId('stored-2')
    expect(within(stored).getByTestId('not-available').getAttribute('title')).toBe('The endpoint is not exported, so it keeps nothing')
    expect(screen.getByTestId('node-row-2').textContent).toContain('Not started')
    expect(screen.getByTestId('node-row-2').textContent).toContain('Not exported')
    expect(screen.getByTestId('node-row-2').textContent).toContain('1102')
  })

  it('shows "none" for an exported, empty endpoint and the ids it keeps otherwise', () => {
    render(<NodeStrip participants={overviewAfter.participants} />)
    expect(screen.getByTestId('stored-1').textContent).toBe('none')
    expect(screen.getByTestId('stored-2').textContent).toBe('1')
  })

  it('marks a crashed node in words and colour', () => {
    render(<NodeStrip participants={overviewCrashed.participants} />)
    const row = screen.getByTestId('node-row-3')
    expect(row.getAttribute('data-crashed')).toBe('true')
    expect(row.textContent).toContain('Down')
    expect(row.textContent).toContain('Crashed')
    expect(screen.getByTestId('stored-4').textContent).toBe('1, 2, 3, 5')
  })

  it('shows a start failure as text under its node', () => {
    render(<NodeStrip participants={startWithFailure.participants} />)
    expect(screen.getByTestId('start-failure-5').textContent).toContain(startWithFailure.participants[4].startFailure)
    expect(screen.queryByTestId('start-failure-1')).toBeNull()
  })

  it('synthetic: shows the leader marker (no capture has a leader)', () => {
    const participants = copy(overviewAfter.participants)
    participants[1].leader = true   // changed: was false
    render(<NodeStrip participants={participants} />)
    expect(within(screen.getByTestId('node-row-2')).getByTestId('leader-marker')).toBeTruthy()
    expect(screen.getAllByTestId('leader-marker')).toHaveLength(1)
  })
})
