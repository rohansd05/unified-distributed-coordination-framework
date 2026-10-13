import { describe, expect, it } from 'vitest'
import overviewBefore from '@/test/fixtures/rmi/overview-before-start.json'
import overviewAfter from '@/test/fixtures/rmi/overview-after-start.json'
import overviewCrashed from '@/test/fixtures/rmi/overview-node-crashed.json'
import callsHistory from '@/test/fixtures/rmi/calls-history.json'
import callPing from '@/test/fixtures/rmi/call-ping.json'
import callSelf from '@/test/fixtures/rmi/call-self.json'
import callTimedOut from '@/test/fixtures/rmi/call-timed-out.json'
import callCrashedTarget from '@/test/fixtures/rmi/call-crashed-target.json'
import {
  callBody,
  callSentence,
  chartModel,
  historyRows,
  latestCall,
  mapModel,
  needsStart,
  noticeModel,
  validateCall,
} from './rmiModel'

const entry = (call) => ({ id: call.id, kind: 'CALL', call, comparison: null })

describe('needsStart', () => {
  it('is true only when an up node is not started', () => {
    expect(needsStart(overviewBefore)).toBe(true)
    expect(needsStart(overviewAfter)).toBe(false)
    expect(needsStart(overviewCrashed)).toBe(false)
    expect(needsStart(null)).toBe(false)
  })
})

describe('callBody and validateCall', () => {
  it('sends text only for ECHO and rounds only for COMPUTE_HASH', () => {
    const form = { fromNode: '1', targetNode: '2', text: 'hi', rounds: '5' }
    expect(callBody({ ...form, method: 'PING' })).toEqual({ fromNode: 1, targetNode: 2, method: 'PING' })
    expect(callBody({ ...form, method: 'ECHO' })).toEqual({ fromNode: 1, targetNode: 2, method: 'ECHO', text: 'hi' })
    expect(callBody({ ...form, method: 'COMPUTE_HASH' })).toEqual({ fromNode: 1, targetNode: 2, method: 'COMPUTE_HASH', rounds: 5 })
  })

  it('takes the echo and rounds limits from the overview', () => {
    const base = { fromNode: '1', targetNode: '2' }
    expect(validateCall({ ...base, method: 'ECHO', text: 'x'.repeat(1024) }, overviewAfter)).toEqual({})
    expect(validateCall({ ...base, method: 'ECHO', text: 'x'.repeat(1025) }, overviewAfter).text).toContain('at most 1024')
    expect(validateCall({ ...base, method: 'COMPUTE_HASH', rounds: '1000000' }, overviewAfter)).toEqual({})
    expect(validateCall({ ...base, method: 'COMPUTE_HASH', rounds: '1000001' }, overviewAfter).rounds).toBe('Rounds must be a whole number from 1 to 1000000.')
    expect(validateCall({ ...base, method: 'COMPUTE_HASH', rounds: '0' }, overviewAfter).rounds).toBeDefined()
    expect(validateCall({ ...base, method: 'COMPUTE_HASH', rounds: '2.5' }, overviewAfter).rounds).toBeDefined()
  })

  it('refuses a crashed caller but allows a crashed target', () => {
    expect(validateCall({ fromNode: '3', targetNode: '1', method: 'PING' }, overviewCrashed).fromNode).toContain('Node 3 is down')
    expect(validateCall({ fromNode: '1', targetNode: '3', method: 'PING' }, overviewCrashed)).toEqual({})
  })
})

describe('mapModel and callSentence', () => {
  it('places every node from the overview and marks a crashed one down and an unstarted one not started', () => {
    const { nodes } = mapModel(overviewCrashed, null)
    expect(nodes.map((n) => [n.id, n.status])).toEqual([[1, 'up'], [2, 'up'], [3, 'down'], [4, 'up'], [5, 'up']])
    expect(mapModel(overviewBefore, null).nodes.every((n) => n.status === 'not-started')).toBe(true)
    expect(mapModel(null, null)).toEqual({ nodes: [], arrow: null })
  })

  it('draws the last call as a success, a failure with its kind, or a self loop', () => {
    expect(mapModel(overviewAfter, callPing).arrow).toMatchObject({ from: 1, to: 2, ok: true, self: false })
    expect(mapModel(overviewCrashed, callCrashedTarget).arrow).toMatchObject({ from: 1, to: 3, ok: false, kind: 'CONNECTION_REFUSED' })
    expect(mapModel(overviewAfter, callSelf).arrow).toMatchObject({ from: 2, to: 2, self: true })
  })

  it('describes the last call in one sentence', () => {
    expect(callSentence(null)).toBe('No call yet: start with a ping.')
    expect(callSentence(callPing)).toBe('Last call: ping from node 1 to node 2 succeeded, round trip 7.237 ms.')
    expect(callSentence(callTimedOut)).toBe('Last call: computeHash from node 1 to node 2 failed at the method call with timed out after 51.310 ms.')
  })

  it('takes a comparison\'s remote half as its call', () => {
    const comparison = overviewCrashed.latest
    expect(latestCall(comparison)).toBe(comparison.comparison.remote)
    expect(latestCall(entry(callPing))).toBe(callPing)
    expect(latestCall(null)).toBeNull()
  })
})

describe('chartModel', () => {
  it('gives completed calls a bar and failed calls a marker, never a zero bar', () => {
    const { bars, max } = chartModel(callsHistory)
    expect(bars).toHaveLength(8)   // 6 calls plus the remote halves of 2 comparisons
    expect(bars[0]).toMatchObject({ id: 1, ok: true, nanos: 7237200 })
    const failed = bars.find((bar) => bar.id === 8)
    expect(failed).toMatchObject({ ok: false, nanos: null, failureKind: 'CONNECTION_REFUSED' })
    expect(bars.filter((bar) => bar.ok).every((bar) => bar.nanos > 0)).toBe(true)
    expect(max).toBe(124766100)
  })

  it('keeps only the last calls and has no scale when nothing completed', () => {
    expect(chartModel(callsHistory, 3).bars.map((bar) => bar.id)).toEqual([6, 7, 8])
    expect(chartModel([entry(callTimedOut)])).toMatchObject({ max: null })
    expect(chartModel(null)).toEqual({ bars: [], max: null })
  })
})

describe('historyRows', () => {
  it('keeps the backend order and keys rows by kind, id and time, never by id alone', () => {
    const rows = historyRows(callsHistory)
    expect(rows.map((row) => row.id)).toEqual([8, 7, 6, 5, 4, 3, 2, 1])
    expect(rows[0].key).toBe(`CALL-8-${callsHistory[0].call.calledAt}`)
    expect(rows[1]).toMatchObject({ kind: 'COMPARISON', method: 'PING', targetNode: 3, ok: true })
    expect(rows[1].localNanos).toBe(callsHistory[1].comparison.localNanos)
    expect(rows[0]).toMatchObject({ ok: false, nanos: null, failureKind: 'CONNECTION_REFUSED' })
  })
})

describe('noticeModel', () => {
  it('appends a first-versus-later figure only for two calls to the same node with the same method', () => {
    const [first] = noticeModel(callsHistory)
    expect(first).toContain('is usually slower')
    expect(first).toContain('the earliest kept ping to node 2 took 1.735 ms to look up and 7.237 ms for the call')
    expect(first).toContain(`the latest took 1.161 ms and 560.6 ${String.fromCharCode(0xb5)}s`)
  })

  it('never compares from one call: one call per node and method gives only the hedged sentence', () => {
    const oneEach = callsHistory.filter((e) => e.kind === 'CALL' && e.id !== 1)   // node 2 ping only once now
    const [first] = noticeModel(oneEach)
    expect(first).toContain('usually')
    expect(first).not.toContain('Measured here')
    expect(noticeModel([])[0]).not.toContain('Measured here')
  })

  it('adds the measured failure and the latest comparison as measured, or the general sentences without data', () => {
    const [, crash, overhead] = noticeModel(callsHistory)
    expect(crash).toContain('the ping to node 3 failed at the registry lookup with connection refused after 10.577 ms')
    expect(overhead).toContain('Latest comparison: ping on node 3, overhead 608.8')
    const [, crashEmpty, overheadEmpty] = noticeModel([])
    expect(crashEmpty).not.toContain('Measured here')
    expect(overheadEmpty).not.toContain('Latest comparison')
  })
})
