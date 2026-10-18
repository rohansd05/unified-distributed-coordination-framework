import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import overviewBefore from '@/test/fixtures/mpi/overview-before-start.json'
import overviewAfter from '@/test/fixtures/mpi/overview-after-start.json'
import overviewCrashed from '@/test/fixtures/mpi/overview-node-crashed.json'
import overviewLeader from '@/test/fixtures/mpi/overview-leader.json'
import broadcast from '@/test/fixtures/mpi/collective-broadcast.json'
import gather from '@/test/fixtures/mpi/collective-gather.json'
import partial from '@/test/fixtures/mpi/collective-partial.json'
import skipped from '@/test/fixtures/mpi/collective-node-crashed-skipped.json'
import { CollectiveMap } from './CollectiveMap'

afterEach(cleanup)

const copy = (value) => JSON.parse(JSON.stringify(value))
const arrows = () => screen.queryAllByTestId('fan-arrow')

describe('CollectiveMap', () => {
  it('before any collective and before any election: no default root, no arrows, and says the first collective elects one', () => {
    render(<CollectiveMap overview={overviewBefore} collective={null} />)
    expect(arrows()).toHaveLength(0)
    expect(screen.getByTestId('map-node-2').getAttribute('data-state')).toBe('not-started')
    expect(screen.getByTestId('map-sentence').textContent)
      .toBe('No collective yet, and no leader yet: the first collective asks Experiment 4 to elect one, and it becomes the root.')
  })

  it('with a leader and no collective shown, centres the leader as the default root, marked leader (real overview)', () => {
    render(<CollectiveMap overview={overviewLeader} collective={null} />)
    expect(arrows()).toHaveLength(0)
    expect(screen.getByTestId('map-node-5').getAttribute('data-root')).toBe('true')
    expect(screen.getByTestId('map-node-5').textContent).toContain('default root, leader')
    expect(screen.getByTestId('map-sentence').textContent).toBe('No collective yet. The default root is node 5, the leader elected by Experiment 4.')
  })

  it('draws a broadcast outward, one solid arrow per rank, in rank order', () => {
    render(<CollectiveMap overview={overviewAfter} collective={broadcast} />)
    expect(arrows().map((a) => [a.getAttribute('data-status'), a.getAttribute('data-inward')]))
      .toEqual(Array(4).fill(['DELIVERED', 'false']))
    expect(arrows().map((a) => a.style.animationDelay)).toEqual(['0ms', '120ms', '240ms', '360ms'])
    expect(screen.getByTestId('map-sentence').textContent).toBe('Broadcast 2 from root node 1: complete.')
  })

  it('draws a gather inward', () => {
    render(<CollectiveMap overview={overviewAfter} collective={gather} />)
    expect(arrows().every((a) => a.getAttribute('data-inward') === 'true')).toBe(true)
    expect(screen.getByText('Arrows point to the root: each rank sends its block back.', { exact: false })).toBeTruthy()
  })

  it('labels a failed arrow with its kind (text, not only colour)', () => {
    render(<CollectiveMap overview={overviewAfter} collective={partial} />)
    const failed = arrows().find((a) => a.getAttribute('data-status') === 'FAILED')
    expect(failed.querySelector('path').getAttribute('stroke-dasharray')).toBe('6 4')
    expect(failed.textContent).toBe('Connection refused')
    expect(screen.getByText('Rank 4, node 5: failed, connection refused.')).toBeTruthy()
  })

  it('marks a crashed, skipped node and gives it no arrow', () => {
    render(<CollectiveMap overview={overviewCrashed} collective={skipped} />)
    const node3 = screen.getByTestId('map-node-3')
    expect(node3.getAttribute('data-state')).toBe('down')
    expect(node3.textContent).toContain('skipped')
    expect(arrows()).toHaveLength(3)
  })

  it('synthetic: a not-attempted arrow is dotted and labelled (no capture has one)', () => {
    const c = copy(partial)
    c.transfers[4].status = 'NOT_ATTEMPTED'   // changed: was FAILED
    c.transfers[4].failureKind = null   // changed: failure fields are FAILED only
    render(<CollectiveMap overview={overviewAfter} collective={c} />)
    const arrow = arrows().find((a) => a.getAttribute('data-status') === 'NOT_ATTEMPTED')
    expect(arrow.querySelector('path').getAttribute('stroke-dasharray')).toBe('2 4')
    expect(arrow.textContent).toBe('not attempted')
  })

  it('shows the Simulated badge only for co-hosting, and (synthetic) none without it', () => {
    render(<CollectiveMap overview={overviewAfter} collective={broadcast} />)
    expect(screen.getAllByTestId('simulated-badge')).toHaveLength(1)
    expect(screen.getByRole('tooltip').textContent).toBe(overviewAfter.coHosting.reason)
    cleanup()
    render(<CollectiveMap overview={{ ...overviewAfter, coHosting: null }} collective={broadcast} />)   // changed: coHosting was set
    expect(screen.queryByTestId('simulated-badge')).toBeNull()
  })
})
