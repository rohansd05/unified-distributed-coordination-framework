import { describe, expect, it } from 'vitest'
import replicasDiverged from '@/test/fixtures/replication/replicas-diverged.json'
import replicasConverged from '@/test/fixtures/replication/replicas-converged.json'
import overviewBefore from '@/test/fixtures/replication/overview-before.json'
import overviewAfterAsync from '@/test/fixtures/replication/overview-after-async.json'
import overviewTakeoverPending from '@/test/fixtures/replication/overview-takeover-pending.json'
import overviewAfterTakeover from '@/test/fixtures/replication/overview-after-takeover.json'
import writeAsync from '@/test/fixtures/replication/write-async.json'
import eventsFixture from '@/test/fixtures/replication/events.json'
import { cellDescription, gridModel, gridSummary, liveBackups, pendingWindow, timelineModel } from './replicaModel'

const copy = (value) => JSON.parse(JSON.stringify(value))

describe('gridModel', () => {
  it('nothing read yet: not loaded, no columns or rows', () => {
    const model = gridModel(null)
    expect(model.loaded).toBe(false)
    expect(model.rows).toEqual([])
    expect(gridSummary(model)).toBe('The replicas have not been read yet.')
  })

  it('puts the primary first and keeps every state the backend sent (diverged fixture)', () => {
    const model = gridModel(replicasDiverged)

    expect(model.referenceNodeId).toBe(1)
    expect(model.columns.map((c) => c.nodeId)).toEqual([1, 2, 3, 4, 5])
    expect(model.columns[0].isReference).toBe(true)
    const region = model.rows.find((row) => row.key === 'region')
    expect(region.cells.find((cell) => cell.nodeId === 3)).toMatchObject({ state: 'MISSING', label: 'Missing', item: null })
    expect(model.consistent).toBe(false)
    expect(gridSummary(model)).toBe('1 difference from the primary (node 1).')
  })

  it('a consistent view says so', () => {
    expect(gridSummary(gridModel(replicasConverged))).toBe('Every reachable replica matches the primary (node 1).')
  })

  it('a reference that is not node 1 still comes first (derived: referenceNodeId changed to 3)', () => {
    const derived = copy(replicasConverged)
    derived.referenceNodeId = 3
    const model = gridModel(derived)
    expect(model.columns.map((c) => c.nodeId)).toEqual([3, 1, 2, 4, 5])
    expect(model.rows[0].cells[0].nodeId).toBe(3)
  })

  it('no readable reference: every state is null, labelled "Not compared" (derived: reference and states removed)', () => {
    const derived = copy(replicasConverged)
    derived.referenceNodeId = null
    derived.consistent = null
    derived.divergences = null
    for (const row of derived.rows) for (const cell of row.cells) cell.state = null
    const model = gridModel(derived)
    expect(model.rows[0].cells.every((cell) => cell.label === 'Not compared')).toBe(true)
    expect(gridSummary(model)).toBe('Not compared: there is no readable primary yet.')
  })

  it('lists unreachable replicas (derived: node 5 made unreachable)', () => {
    const derived = copy(replicasConverged)
    derived.replicas[4] = { ...derived.replicas[4], reachable: false, epoch: null, itemCount: null, error: 'ConnectException: x' }
    const model = gridModel(derived)
    expect(model.unreachable).toEqual([5])
    expect(gridSummary(model)).toContain('1 replica is unreachable.')
  })

  it('describes a cell in words: node, state, value, Lamport time and origin', () => {
    expect(cellDescription(3, false, 'STALE', { value: '1000', lamportTime: 4, originNode: 1 }))
      .toBe('Node 3, stale: holds 1000 (Lamport 4, written by node 1)')
    expect(cellDescription(1, true, 'CURRENT', { value: '', lamportTime: 2, originNode: 1 }))
      .toBe('Node 1, the primary, current: holds (empty) (Lamport 2, written by node 1)')
    expect(cellDescription(4, false, 'UNREACHABLE', null)).toContain('Node 4, unreachable: could not be read')
    expect(cellDescription(2, false, 'MISSING', null)).toBe('Node 2, missing: holds nothing')
  })
})

describe('timelineModel', () => {
  it('turns PRIMARY_SELECTED events into entries, in causal order, ignoring every other event', () => {
    const model = timelineModel(eventsFixture, overviewAfterTakeover)

    expect(model.entries.map((e) => [e.nodeId, e.previousNodeId])).toEqual([[1, null], [2, 1], [1, 2]])
    expect(model.entries[2].appliedFromCatchUp).toBe(1)
    expect(model.entries[2].catchUpSources).toEqual([2, 3, 4, 5])
    expect(model.pending).toBeNull()
  })

  it('adds the per-source catch-up counts to the latest entry when the overview\'s lastTakeover matches it', () => {
    const model = timelineModel(eventsFixture, overviewAfterTakeover)
    const latest = model.entries[2]
    expect(latest.catchUps.map((c) => [c.sourceNodeId, c.applied, c.pulled])).toEqual([[2, 1, 2], [3, 0, 2], [4, 0, 2], [5, 0, 2]])
    expect(model.entries[1].catchUps).toEqual([])
  })

  it('marks a pending takeover from the overview, without inventing an entry', () => {
    const model = timelineModel([], overviewTakeoverPending)
    expect(model.pending).toEqual({ nodeId: 2, fromNodeId: 1 })
    expect(model.entries.map((e) => e.nodeId)).toEqual([1])   // from lastTakeover, the first selection
    expect(model.entries[0].previousNodeId).toBeNull()
  })

  it('nothing selected yet: no entries, nothing pending', () => {
    expect(timelineModel([], overviewBefore)).toEqual({ entries: [], pending: null })
    expect(timelineModel(null, null)).toEqual({ entries: [], pending: null })
  })
})

describe('pendingWindow', () => {
  it('is open exactly while the latest write is PENDING, with the simulated delay and reason from the backend', () => {
    const overview = { ...copy(overviewAfterAsync), latestWrite: writeAsync }
    const open = pendingWindow(overview)
    expect(open.open).toBe(true)
    expect(open.write.writeId).toBe(writeAsync.writeId)
    expect(open.delayMillis).toBe(450)
    expect(open.reason).toBe(writeAsync.simulatedReason)
  })

  it('is closed once the backend reports COMPLETE, and when there is no write', () => {
    expect(overviewAfterAsync.latestWrite.replicationState).toBe('COMPLETE')
    expect(pendingWindow(overviewAfterAsync).open).toBe(false)
    expect(pendingWindow(overviewBefore)).toEqual({ open: false, write: null, delayMillis: null, reason: null })
    expect(pendingWindow(null).open).toBe(false)
  })
})

describe('liveBackups', () => {
  it('offers only live nodes that are not the primary', () => {
    expect(liveBackups(overviewBefore).map((n) => n.nodeId)).toEqual([2, 3, 4, 5])
    expect(liveBackups(overviewTakeoverPending).map((n) => n.nodeId)).toEqual([3, 4, 5])   // node 1 crashed, 2 primary
    expect(liveBackups(null)).toEqual([])
  })
})
