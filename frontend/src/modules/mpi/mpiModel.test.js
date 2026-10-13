import { describe, expect, it } from 'vitest'
import overviewBefore from '@/test/fixtures/mpi/overview-before-start.json'
import overviewAfter from '@/test/fixtures/mpi/overview-after-start.json'
import overviewCrashed from '@/test/fixtures/mpi/overview-node-crashed.json'
import startFixture from '@/test/fixtures/mpi/start.json'
import startWithFailure from '@/test/fixtures/mpi/start-with-failure.json'
import broadcast from '@/test/fixtures/mpi/collective-broadcast.json'
import scatter from '@/test/fixtures/mpi/collective-scatter.json'
import gather from '@/test/fixtures/mpi/collective-gather.json'
import partial from '@/test/fixtures/mpi/collective-partial.json'
import refused from '@/test/fixtures/mpi/collective-refused.json'
import skipped from '@/test/fixtures/mpi/collective-node-crashed-skipped.json'
import requestedRoot from '@/test/fixtures/mpi/collective-requested-root.json'
import history from '@/test/fixtures/mpi/collectives-history.json'
import {
  chartModel,
  collectiveBody,
  fanModel,
  historyRows,
  needsStart,
  noticeModel,
  operationEntry,
  rankRows,
  transferCounts,
  validateCollective,
} from './mpiModel'

const copy = (value) => JSON.parse(JSON.stringify(value))

/** Synthetic: collective-partial.json with rank 4 NOT_ATTEMPTED instead of FAILED (no capture has one). */
function notAttemptedCollective() {
  const c = copy(partial)
  const t = c.transfers[4]
  t.status = 'NOT_ATTEMPTED'   // changed: was FAILED
  t.timeToFailureNanos = null   // changed: only FAILED has a time to failure
  t.failureStage = null   // changed: failure fields belong to FAILED only
  t.failureKind = null   // changed
  t.failureDescription = null   // changed
  t.failureClass = null   // changed
  t.failureMessage = null   // changed
  t.failureCauseClass = null   // changed
  t.notAttemptedReason = 'the collective deadline of 10000 ms had passed'   // changed: was null
  return c
}

const form = (overrides = {}) => ({ operation: 'BROADCAST', count: '1000', seed: '', rootNode: '', ...overrides })

describe('needsStart', () => {
  it('is true exactly when an up node does not export CollectiveEndpoint', () => {
    expect(needsStart(overviewBefore)).toBe(true)
    expect(needsStart(startWithFailure)).toBe(true)   // node 5 stayed unexported; the hook does not loop on it
    expect(needsStart(overviewAfter)).toBe(false)
    expect(needsStart(startFixture)).toBe(false)
    expect(needsStart(overviewCrashed)).toBe(false)   // node 3 is crashed, not "up and unexported"
    expect(needsStart(null)).toBe(false)
  })
})

describe('collectiveBody and validateCollective', () => {
  it('sends seed and rootNode only when filled in', () => {
    expect(collectiveBody(form())).toEqual({ operation: 'BROADCAST', count: 1000 })
    expect(collectiveBody(form({ seed: '42', rootNode: '3' }))).toEqual({ operation: 'BROADCAST', count: 1000, seed: 42, rootNode: 3 })
    expect(collectiveBody(form({ seed: '  ', rootNode: '' }))).toEqual({ operation: 'BROADCAST', count: 1000 })
  })

  it('accepts a valid form, and a count below an operation\'s minimum (the backend refuses it, recorded)', () => {
    expect(validateCollective(form(), overviewAfter)).toEqual({})
    expect(operationEntry(overviewAfter, 'SCATTER').minimumCount).toBe(5)
    expect(validateCollective(form({ operation: 'SCATTER', count: '3' }), overviewAfter)).toEqual({})
  })

  it('uses the backend\'s words for a missing operation and count', () => {
    expect(validateCollective(form({ operation: '', count: '' }), overviewAfter))
      .toEqual({ operation: 'operation is required', count: 'count is required' })
  })

  it('checks the count as a safe whole number from 1 to the overview\'s maximum', () => {
    for (const bad of ['0', '100001', '1.5', 'abc', '-3', '9007199254740993']) {
      expect(validateCollective(form({ count: bad }), overviewAfter).count).toBe(`count must be 1-100000, was ${bad}`)
    }
    expect(validateCollective(form({ count: '100000' }), overviewAfter)).toEqual({})
  })

  it('checks the seed as a safe whole number from 0 to limits.maxSeed (and a synthetic smaller maxSeed)', () => {
    expect(validateCollective(form({ seed: '0' }), overviewAfter)).toEqual({})
    expect(validateCollective(form({ seed: '9007199254740991' }), overviewAfter)).toEqual({})
    for (const bad of ['-1', '1.5', 'x', '9007199254740992']) {
      expect(validateCollective(form({ seed: bad }), overviewAfter).seed).toBe(`seed must be 0-9007199254740991, was ${bad}`)
    }
    const smaller = { ...overviewAfter, limits: { ...overviewAfter.limits, maxSeed: 10 } }   // changed: maxSeed
    expect(validateCollective(form({ seed: '11' }), smaller).seed).toBe('seed must be 0-10, was 11')
  })
})

describe('transferCounts and rankRows', () => {
  it('counts remote transfers by status, never the root\'s local part', () => {
    expect(transferCounts(broadcast)).toEqual({ remote: 4, delivered: 4, failed: 0, notAttempted: 0 })
    expect(transferCounts(partial)).toEqual({ remote: 4, delivered: 3, failed: 1, notAttempted: 0 })
    expect(transferCounts(refused)).toEqual({ remote: 0, delivered: 0, failed: 0, notAttempted: 0 })
  })

  it('synthetic: counts a not-attempted transfer apart from a failed one', () => {
    expect(transferCounts(notAttemptedCollective())).toEqual({ remote: 4, delivered: 3, failed: 0, notAttempted: 1 })
  })

  it('lists ranks in rank order with the root, then the skipped nodes without a rank', () => {
    expect(rankRows(skipped).map((r) => [r.rank, r.nodeId, r.role])).toEqual([
      [0, 1, 'root'], [1, 2, 'participant'], [2, 4, 'participant'], [3, 5, 'participant'], [null, 3, 'skipped'],
    ])
    expect(rankRows(requestedRoot).find((r) => r.role === 'root')).toMatchObject({ rank: 2, nodeId: 3 })
  })
})

describe('fanModel', () => {
  it('puts the root in the centre and draws an outward arrow per remote transfer for broadcast and scatter', () => {
    for (const collective of [broadcast, scatter]) {
      const model = fanModel(overviewAfter, collective)
      expect(model.rootId).toBe(1)
      expect(model.nodes.find((n) => n.root)).toMatchObject({ id: 1, x: 180, y: 140 })
      expect(model.arrows.map((a) => [a.peer, a.inward, a.status])).toEqual([[2, false, 'DELIVERED'], [3, false, 'DELIVERED'],
        [4, false, 'DELIVERED'], [5, false, 'DELIVERED']])
    }
  })

  it('draws gather inward, and a failed transfer with its kind', () => {
    expect(fanModel(overviewAfter, gather).arrows.every((a) => a.inward)).toBe(true)
    expect(fanModel(overviewAfter, partial).arrows[3]).toMatchObject({ peer: 5, status: 'FAILED', failureKind: 'CONNECTION_REFUSED' })
  })

  it('marks a skipped node, gives it no arrow, and shows the crashed state', () => {
    const model = fanModel(overviewCrashed, skipped)
    expect(model.nodes.find((n) => n.id === 3)).toMatchObject({ state: 'down', skipped: true })
    expect(model.arrows.map((a) => a.peer)).toEqual([2, 4, 5])
  })

  it('centres a requested root, and the default root when nothing has run', () => {
    expect(fanModel(overviewAfter, requestedRoot).rootId).toBe(3)
    const idle = fanModel(overviewBefore, null)
    expect(idle).toMatchObject({ rootId: 1, arrows: [], hasCollective: false })
    expect(idle.nodes.every((n) => n.state === 'not-started')).toBe(true)
  })
})

describe('chartModel', () => {
  it('gives a latency only to delivered transfers and scales to the slowest', () => {
    const model = chartModel(partial)
    expect(model.slots.map((s) => [s.rank, s.status, s.nanos])).toEqual([[0, 'LOCAL', null], [1, 'DELIVERED', 25739700],
      [2, 'DELIVERED', 5649700], [3, 'DELIVERED', 3891800], [4, 'FAILED', null]])
    expect(model.slots[4].timeToFailureNanos).toBe(21944300)   // kept apart, never as a latency
    expect(model.max).toBe(25739700)
  })

  it('has no slots for a refused collective', () => {
    expect(chartModel(refused)).toEqual({ slots: [], max: null })
  })

  it('synthetic: has no figure for a not-attempted rank', () => {
    const slot = chartModel(notAttemptedCollective()).slots[4]
    expect(slot).toMatchObject({ status: 'NOT_ATTEMPTED', nanos: null, timeToFailureNanos: null })
  })
})

describe('historyRows and noticeModel', () => {
  it('keeps the backend order and keys rows by id and start time', () => {
    const rows = historyRows(history)
    expect(rows.map((r) => r.id)).toEqual([7, 6, 5, 4, 3, 2, 1])
    expect(rows[0].key).toBe('7-2026-10-13T14:41:46.056522900Z')
    expect(rows[6]).toMatchObject({ outcome: 'PARTIAL', totalNanos: 62695100, counts: { remote: 4, delivered: 3 } })
    expect(rows[0]).toMatchObject({ outcome: 'REFUSED', totalNanos: null })
    expect(historyRows(null)).toEqual([])
  })

  it('adds measured figures only when the history holds them', () => {
    const empty = noticeModel([])
    expect(empty).toHaveLength(3)
    expect(empty.join(' ')).not.toContain('Measured here')
    const full = noticeModel(history)
    expect(full[0]).toContain('Measured here: broadcast 6 took 6.772 ms in total for 3 remote transfers.')
    expect(full[1]).toContain('in collective 1 the transfer with node 5 failed at the registry lookup with connection refused after 21.944 ms')
    expect(full[2]).toContain(`Latest refusal: ${refused.refusal.message}`)
    expect(noticeModel([skipped])[1]).toContain('collective 6 skipped node 3.')
  })
})
