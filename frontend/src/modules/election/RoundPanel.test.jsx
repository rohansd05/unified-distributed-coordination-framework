import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import overviewInitial from '@/test/fixtures/election/overview-initial.json'
import overviewAfterBully from '@/test/fixtures/election/overview-after-bully.json'
import overviewLeaderCrashed from '@/test/fixtures/election/overview-leader-crashed.json'
import overviewAfterReelection from '@/test/fixtures/election/overview-after-reelection.json'
import overviewTimedOut from '@/test/fixtures/election/overview-ring-timed-out.json'
import startRing from '@/test/fixtures/election/start-ring.json'
import { RoundPanel } from './RoundPanel'

afterEach(cleanup)

const panelText = () => screen.getByTestId('round-panel').textContent

describe('RoundPanel', () => {
  it('says the detector is idle until the first election, and never that all nodes are healthy', () => {
    render(<RoundPanel overview={overviewInitial} leaderId={null} ringOfOne={false} />)
    expect(screen.getByTestId('detector-state').textContent).toBe('Detector idle, starts on the first election.')
    expect(panelText()).not.toMatch(/healthy/i)
    expect(panelText()).toContain('Leader: none')
  })

  it('reports the running detector with the configured heartbeat timings', () => {
    render(<RoundPanel overview={overviewAfterBully} leaderId={5} ringOfOne={false} />)
    expect(screen.getByTestId('detector-state').textContent)
      .toBe('Failure detector running: a heartbeat every 700 ms; a node is suspected after 2500 ms of silence.')
    expect(panelText()).toContain('Leader: node 5')
    expect(screen.getByTestId('consensus').textContent).toBe('Consensus check passed: every live node agrees on node 5.')
  })

  it('labels a leader-failure duration as measured from detection', () => {
    render(<RoundPanel overview={overviewAfterReelection} leaderId={4} ringOfOne={false} />)
    expect(screen.getByTestId('last-round').textContent).toContain('After the leader failed')
    expect(screen.getByTestId('last-round').textContent).toContain('(measured from detection, not from the crash)')
  })

  it('shows a timed-out round with "—" for the leader and the duration, and the timeout from the settings', () => {
    render(<RoundPanel overview={overviewTimedOut} leaderId={null} ringOfOne />)
    const last = screen.getByTestId('last-round')
    expect(last.textContent).toContain('Timed out')
    expect(last.querySelectorAll('[aria-label="Not available"]')).toHaveLength(2)
    expect(last.textContent).toContain('Timed out after 10 s: no leader was agreed, so nothing was measured.')
  })

  it('takes the timeout text from the overview, not a fixed 10', () => {
    const overview = { ...overviewTimedOut, settings: { ...overviewTimedOut.settings, roundTimeoutMillis: 4000 } }
    render(<RoundPanel overview={overview} leaderId={null} ringOfOne />)
    expect(screen.getByTestId('last-round').textContent).toContain('Timed out after 4 s')
  })

  it('warns while a Ring election runs with one live node, instead of an endless spinner', () => {
    const overview = { ...overviewTimedOut, status: 'BUSY', currentRound: { ...startRing, initiatorNodeId: 1 }, lastRound: null }
    render(<RoundPanel overview={overview} leaderId={null} ringOfOne />)
    expect(screen.getByRole('status').textContent).toContain('Only one node is up, so a Ring election cannot elect anyone')
    expect(screen.getByRole('status').textContent).toContain('timed out after 10 s')
  })

  it('reports a consensus on a dead leader as not passed', () => {
    render(<RoundPanel overview={overviewLeaderCrashed} leaderId={null} ringOfOne={false} />)
    expect(screen.getByTestId('consensus').textContent)
      .toBe('Consensus check not passed: every live node still names node 5, but that node is down.')
  })

  it('has a focusable heading for the focus move after a start', () => {
    render(<RoundPanel overview={overviewAfterBully} leaderId={5} ringOfOne={false} />)
    const heading = screen.getByRole('heading', { name: 'Election result' })
    expect(heading.getAttribute('tabindex')).toBe('-1')
  })
})
