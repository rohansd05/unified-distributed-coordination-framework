import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import eventsFixture from '@/test/fixtures/replication/events.json'
import overviewBefore from '@/test/fixtures/replication/overview-before.json'
import overviewAfterTakeover from '@/test/fixtures/replication/overview-after-takeover.json'
import overviewTakeoverPending from '@/test/fixtures/replication/overview-takeover-pending.json'
import { TakeoverTimeline } from './TakeoverTimeline'
import { timelineModel } from './replicaModel'

afterEach(() => {
  cleanup()
})

describe('TakeoverTimeline', () => {
  it('shows each selection with its Lamport time, and the latest takeover\'s catch-up from each peer (real events)', () => {
    render(<TakeoverTimeline model={timelineModel(eventsFixture, overviewAfterTakeover)} />)

    const entries = screen.getAllByTestId('timeline-entry')
    expect(entries).toHaveLength(3)
    expect(entries[0].textContent).toContain('Node 5 selected as primary')
    expect(entries[0].textContent).toContain('First selection: the leader elected by Experiment 4. Nothing to catch up.')
    expect(entries[1].textContent).toContain('Node 4 took over from node 5')
    expect(entries[1].textContent).toContain('Caught up 0 items from nodes 1, 2 and 3, then pushed its store to nodes 1, 2 and 3.')
    expect(entries[2].textContent).toContain('Node 5 took over from node 4')
    expect(entries[2].textContent).toContain('Caught up 1 item from nodes 1, 2, 3 and 4')
    const perPeer = within(entries[2]).getByRole('list', { name: 'Catch-up from each peer' })
    expect(within(perPeer).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'From node 1: 1 of 2 applied', 'From node 2: 0 of 2 applied', 'From node 3: 0 of 2 applied', 'From node 4: 0 of 2 applied',
    ])
    expect(entries[2].textContent).toContain('L:208')
  })

  it('a pending takeover is a dashed marker, not an entry, and says reading changes nothing (real pending overview)', () => {
    render(<TakeoverTimeline model={timelineModel([], overviewTakeoverPending)} />)

    expect(screen.getAllByTestId('timeline-entry')).toHaveLength(1)
    const pending = screen.getByTestId('timeline-pending')
    expect(pending.textContent).toContain('Pending: node 4 takes over next')
    expect(pending.textContent).toContain('Node 5 was the primary. Experiment 4 has elected node 4 as leader')
    expect(pending.textContent).toContain('Reading this page changes nothing.')
  })

  it('a catch-up that could not read its peer says so (derived: one catch-up failed)', () => {
    const derived = JSON.parse(JSON.stringify(overviewAfterTakeover))
    derived.lastTakeover.catchUps[1] = { ...derived.lastTakeover.catchUps[1], completed: false, pulled: null, applied: null,
      alreadyCurrent: null, stale: null, staleEpoch: null, sourceEpoch: null, failure: 'ConnectException: refused' }
    render(<TakeoverTimeline model={timelineModel(eventsFixture, derived)} />)

    expect(screen.getByText('From node 2: could not read it (ConnectException: refused)')).toBeTruthy()
  })

  it('empty: no selection yet, with guidance', () => {
    render(<TakeoverTimeline model={timelineModel([], overviewBefore)} />)
    expect(screen.getByTestId('takeover-timeline').textContent)
      .toBe('No primary selected yet. The first write makes the leader elected by Experiment 4 the primary.')
  })
})
