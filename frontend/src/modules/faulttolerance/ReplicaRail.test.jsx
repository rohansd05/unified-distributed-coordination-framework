import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import overviewBefore from '@/test/fixtures/faulttolerance/overview-before-start.json'
import overviewAfterRuns from '@/test/fixtures/faulttolerance/overview-after-runs.json'
import statusRunning from '@/test/fixtures/faulttolerance/status-stream-running.json'
import statusDuringFailover from '@/test/fixtures/faulttolerance/status-during-failover.json'
import statusAfterDemotion from '@/test/fixtures/faulttolerance/status-after-demotion.json'
import runSyncRecovered from '@/test/fixtures/faulttolerance/run-sync-recovered.json'
import statusRejoinStalled from '@/test/fixtures/faulttolerance/synthetic/status-rejoin-stalled.json'
import { ReplicaRail } from './ReplicaRail'

const { settings } = overviewBefore

afterEach(cleanup)

const tile = (id) => screen.getByTestId(`node-${id}`)

describe('ReplicaRail', () => {
  it('shows a null role and epoch as unknown, never 0, before the start', () => {
    render(<ReplicaRail status={overviewBefore.current} run={null} settings={settings} />)
    for (const node of overviewBefore.current.nodes) {
      const box = tile(node.nodeId)
      expect(within(box).getByTestId('epoch').textContent).toMatch(/^Unknown/)
      expect(within(box).getByText('No role yet')).toBeTruthy()
      expect(box.textContent).not.toMatch(/(^|[^\d])0([^\d]|$)/)
    }
    expect(screen.getByText('No primary serves right now.')).toBeTruthy()
  })

  it('shows the epoch large on every live node, and the crashed primary as down with nothing made up', () => {
    render(<ReplicaRail status={statusDuringFailover} run={null} settings={settings} />)
    const down = tile(1)
    expect(within(down).getByText('Down')).toBeTruthy()
    expect(within(down).getByTestId('epoch').textContent).toBe('Unknown (Unknown while the node is down)')
    expect(within(down).getByText('No, down')).toBeTruthy()
    for (const id of [2, 3, 4, 5]) {
      expect(within(tile(id)).getByTestId('epoch').textContent).toBe('2')
      expect(within(tile(id)).getByText('Backup')).toBeTruthy()
    }
  })

  it('names the primary in words with an icon, not only colour', () => {
    render(<ReplicaRail status={statusRunning} run={null} settings={settings} />)
    expect(within(tile(1)).getByText('Primary')).toBeTruthy()
    expect(tile(1).querySelector('svg')).toBeTruthy()
    expect(within(tile(1)).getByText('Yes')).toBeTruthy()
  })

  it('marks the old primary that demoted itself, with both epochs', () => {
    render(<ReplicaRail status={statusAfterDemotion} run={runSyncRecovered} settings={settings} />)
    expect(within(tile(1)).getByTestId('demoted').textContent).toBe('Demoted itself: primary at epoch 2, now backup at epoch 3')
    expect(within(tile(2)).getByText('Primary')).toBeTruthy()
    expect(screen.queryAllByTestId('demoted')).toHaveLength(1)
  })

  it('after the planned handover (9A.1 capture) the old primary is primary again: no demotion mark, one primary', () => {
    render(<ReplicaRail status={overviewAfterRuns.current} run={overviewAfterRuns.latestRun} settings={settings} />)
    expect(within(tile(5)).getByText('Primary')).toBeTruthy()
    expect(within(tile(5)).getByTestId('epoch').textContent).toBe('4')
    expect(screen.queryAllByTestId('demoted')).toHaveLength(0)
    expect(screen.getAllByText('Primary')).toHaveLength(1)
  })

  it('shows a stalled rejoin (synthetic status-rejoin-stalled) as rejoining at its old epoch, believing no one', () => {
    render(<ReplicaRail status={statusRejoinStalled} run={null} settings={settings} />)
    const box = tile(1)
    expect(within(box).getByText('Rejoining: asking who the primary is')).toBeTruthy()
    expect(within(box).getByTestId('epoch').textContent).toBe('2')
    expect(within(box).getByText('Unknown')).toBeTruthy()
  })

  it('carries the co-hosting Simulated badge with its served reason, and no Experiment 5 note (obsolete since 9A.3)', () => {
    render(<ReplicaRail status={statusRunning} run={null} settings={settings} />)
    expect(screen.getAllByTestId('simulated-badge')).toHaveLength(1)
    expect(screen.getByRole('tooltip').textContent).toBe(settings.coHostingReason)
    expect(screen.queryByTestId('exp5-note')).toBeNull()
    expect(screen.queryByText(/Until Phase 9A/)).toBeNull()
  })

  it('animates a promotion once, never on first load, and not under reduced motion', () => {
    const { rerender } = render(<ReplicaRail status={statusRunning} run={null} settings={settings} />)
    expect(document.querySelectorAll('.animate-in')).toHaveLength(0)
    rerender(<ReplicaRail status={statusAfterDemotion} run={runSyncRecovered} settings={settings} />)
    expect(tile(2).className).toContain('animate-in')
    expect(tile(2).className).toContain('zoom-in-90')
    expect(tile(2).className).toContain('motion-reduce:animate-none')
    expect(tile(3).className).not.toContain('animate-in')
  })

  it('animates the returning old primary settling as a backup', () => {
    const { rerender } = render(<ReplicaRail status={statusDuringFailover} run={runSyncRecovered} settings={settings} />)
    rerender(<ReplicaRail status={statusAfterDemotion} run={runSyncRecovered} settings={settings} />)
    expect(tile(1).className).toContain('slide-in-from-top-3')
  })
})
