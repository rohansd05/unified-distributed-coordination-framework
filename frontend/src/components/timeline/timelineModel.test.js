import { describe, expect, it } from 'vitest'
import electionEvents from '@/test/fixtures/election/events-cluster.json'
import faultToleranceEvents from '@/test/fixtures/faulttolerance/events.json'
import {
  ORDER_ARRIVAL, ORDER_CAUSAL, applyFilters, describeEvent, eventKind, filtersToParams, leaderChain,
  mergeEvents, moduleOptions, orderEvents, parseFilters, scenarioBands, typeLabel,
} from './timelineModel'

const ev = (sequence, lamportTime, nodeId, type = 'PING', extra = {}) =>
  ({ sequence, lamportTime, nodeId, type, module: 'test', message: `${type} ${sequence}`, data: {}, ...extra })
const seqs = (events) => events.map((e) => e.sequence)

describe('mergeEvents', () => {
  it('deduplicates by sequence and keeps bus-sequence order', () => {
    const merged = mergeEvents([ev(1, 5, 1), ev(3, 1, 2)], [ev(2, 9, 3), ev(3, 1, 2), ev(1, 5, 1)])
    expect(seqs(merged)).toEqual([1, 2, 3])
  })

  it('caps at the limit, dropping the oldest by sequence', () => {
    const many = Array.from({ length: 30 }, (_, i) => ev(i + 1, 100 - i, 1))
    expect(seqs(mergeEvents([], many, 10))).toEqual([21, 22, 23, 24, 25, 26, 27, 28, 29, 30])
  })

  it('drops everything before the latest CLUSTER_RESET', () => {
    const merged = mergeEvents([ev(1, 1, 1), ev(2, 2, 1)], [ev(3, 1, 0, 'CLUSTER_RESET'), ev(4, 1, 2)])
    expect(seqs(merged)).toEqual([3, 4])
  })

  it('ignores frames that are not events', () => {
    expect(mergeEvents([], [null, 'text', { type: 'X' }])).toEqual([])
  })
})

describe('orderEvents', () => {
  const events = [ev(1, 7, 2), ev(2, 3, 4), ev(3, 7, 1), ev(4, 3, 4), ev(5, 3, 0)]

  it('orders by (lamportTime, nodeId), ties broken by bus sequence', () => {
    expect(seqs(orderEvents(events, ORDER_CAUSAL))).toEqual([5, 2, 4, 3, 1])
  })

  it('orders by arrival (bus sequence) when asked', () => {
    expect(seqs(orderEvents(events, ORDER_ARRIVAL))).toEqual([1, 2, 3, 4, 5])
  })
})

describe('filters', () => {
  it('parse and serialise round-trip, defaults left out', () => {
    const params = new URLSearchParams('modules=clocksync,mapreduce&nodes=0,4&q=leader&order=arrival')
    const filters = parseFilters(params)
    expect(filters).toEqual({ modules: ['clocksync', 'mapreduce'], nodes: [0, 4], q: 'leader', order: ORDER_ARRIVAL })
    expect(filtersToParams(filters)).toEqual({ modules: 'clocksync,mapreduce', nodes: '0,4', q: 'leader', order: 'arrival' })
    expect(filtersToParams(parseFilters(new URLSearchParams('')))).toEqual({})
  })

  it('ignore bad node ids and unknown orders', () => {
    expect(parseFilters(new URLSearchParams('nodes=x,-1,2&order=bogus'))).toMatchObject({ nodes: [2], order: ORDER_CAUSAL })
  })

  it('apply modules, nodes and text together', () => {
    const events = [
      ev(1, 1, 0, 'LEADER_CHANGED', { module: 'cluster', data: { leaderId: 4 } }),
      ev(2, 1, 4, 'TIME_DAEMON_CHANGED', { module: 'clocksync', data: { nodeId: 4 } }),
      ev(3, 1, 2, 'NODE_CRASHED', { module: 'cluster' }),
    ]
    expect(seqs(applyFilters(events, { modules: ['cluster'] }))).toEqual([1, 3])
    expect(seqs(applyFilters(events, { nodes: [4] }))).toEqual([2])
    expect(seqs(applyFilters(events, { q: 'became leader' }))).toEqual([1])
    expect(seqs(applyFilters(events, { modules: ['cluster'], q: 'crashed' }))).toEqual([3])
  })

  it('offer every catalog module, cluster and scenarios, plus any other module seen', () => {
    const ids = moduleOptions([ev(1, 1, 1, 'X', { module: 'mystery' })]).map((m) => m.id)
    expect(ids.slice(0, 10)).toEqual(['rmi', 'multithreading', 'clocksync', 'election', 'replication',
      'loadbalancing', 'mapreduce', 'faulttolerance', 'mpi', 'matrix'])
    expect(ids.slice(10)).toEqual(['cluster', 'scenario', 'mystery'])
  })
})

describe('describeEvent', () => {
  it('writes readable sentences for the role-change types', () => {
    expect(describeEvent(ev(1, 1, 0, 'LEADER_CHANGED', { data: { leaderId: 4, previousLeaderId: 2 } })))
      .toBe('Node 4 became leader (was node 2)')
    expect(describeEvent(ev(1, 1, 0, 'LEADER_CHANGED', { data: { leaderId: null, previousLeaderId: 5 } })))
      .toBe('No leader: node 5 is no longer leader')
    expect(describeEvent(ev(1, 1, 4, 'TIME_DAEMON_CHANGED', { data: { nodeId: 4, previousNodeId: 5, cause: 'LEADER_CRASHED' } })))
      .toBe('Node 4 became the Berkeley time daemon (was node 5), after node 5 crashed')
    expect(describeEvent(ev(1, 1, 4, 'COORDINATOR_CHANGED', { data: { nodeId: 4, previousNodeId: 5, cause: 'ELECTED' } })))
      .toBe('Node 4 became the MapReduce coordinator (was node 5)')
    expect(describeEvent(ev(1, 1, 4, 'MPI_ROOT_CHANGED', { data: { previousNodeId: 5 } })))
      .toBe('Node 4 became the MPI root (was node 5)')
    expect(describeEvent(ev(1, 1, 0, 'REPLICATION_ROLES_CHANGED', { data: { primaryId: 4, previousPrimaryId: 5, backupIds: [1, 2] } })))
      .toBe('Node 4 holds the replication primary role (was node 5); backups 1, 2')
    expect(describeEvent(ev(1, 1, 5, 'HANDOVER_STARTED', { data: { epoch: 3, leaderId: 4 } })))
      .toBe('Node 5 stopped serving so node 4, the elected leader, can take over at epoch 3')
    expect(describeEvent(ev(1, 1, 5, 'BERKELEY_ROUND_ABORTED', { data: { roundId: 2 } })))
      .toBe('Berkeley round 2 aborted: the time daemon, node 5, went down')
  })

  it('describes scenario events from whichever fields they carry', () => {
    expect(describeEvent(ev(1, 1, 0, 'SCENARIO_STARTED', { data: { title: 'The leader dies mid-write' } })))
      .toBe('Scenario "The leader dies mid-write" started')
    expect(describeEvent(ev(1, 1, 0, 'STEP_PASSED', { data: { stepIndex: 2, title: 'Crash the leader' } })))
      .toBe('Step 2: Crash the leader passed')
    expect(describeEvent(ev(1, 1, 0, 'SCENARIO_FINISHED', { data: { outcome: 'PASSED' } }))).toBe('Scenario finished: passed')
  })

  it('shows an unknown type by its message or raw type, and never throws', () => {
    expect(describeEvent(ev(1, 1, 1, 'SOMETHING_NEW', { message: 'Hello' }))).toBe('Hello')
    expect(describeEvent({ sequence: 1, type: 'SOMETHING_NEW' })).toBe('SOMETHING_NEW')
    expect(typeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
    expect(typeLabel('LEADER_CHANGED')).toBe('Leader changed')
    expect(eventKind({ sequence: 1 })).toBe('other')
  })

  it('handles every event in two real captured logs', () => {
    for (const e of [...electionEvents, ...faultToleranceEvents]) {
      expect(typeof describeEvent(e)).toBe('string')
      expect(describeEvent(e).length).toBeGreaterThan(0)
    }
    const leader = electionEvents.find((e) => e.type === 'LEADER_CHANGED' && e.data.leaderId === 5)
    expect(describeEvent(leader)).toBe('Node 5 became leader')
  })
})

describe('leaderChain', () => {
  // The leader-crash chain: node 5 leads, crashes, node 4 is elected and every role follows.
  const events = [
    ev(1, 2, 0, 'LEADER_CHANGED', { data: { leaderId: 5, previousLeaderId: null } }),
    ev(2, 9, 5, 'TIME_DAEMON_CHANGED', { data: { nodeId: 5 } }),
    ev(3, 12, 5, 'NODE_CRASHED'),
    ev(4, 3, 0, 'LEADER_CHANGED', { data: { leaderId: null, previousLeaderId: 5 } }),
    ev(5, 4, 0, 'LEADER_CHANGED', { data: { leaderId: 4, previousLeaderId: null } }),
    ev(6, 30, 4, 'TIME_DAEMON_CHANGED', { data: { nodeId: 4, previousNodeId: 5 } }),
    ev(7, 31, 3, 'COORDINATOR_CHANGED', { data: { nodeId: 3 } }),
    ev(8, 18, 4, 'COORDINATOR_CHANGED', { data: { nodeId: 4, previousNodeId: 5 } }),
    ev(9, 5, 0, 'REPLICATION_ROLES_CHANGED', { data: { primaryId: 4, previousPrimaryId: 5, backupIds: [] } }),
    ev(10, 40, 4, 'MPI_ROOT_CHANGED', { data: { nodeId: 4 } }),
  ]

  it('anchors on the latest LEADER_CHANGED and collects the roles that moved to the same node', () => {
    const chain = leaderChain(events)
    expect(chain.anchor.sequence).toBe(5)
    expect(chain.leaderId).toBe(4)
    expect(seqs(chain.members)).toEqual([6, 8, 9, 10])
    expect([...chain.sequences]).toEqual([5, 6, 8, 9, 10])
  })

  it('anchors on a selected LEADER_CHANGED and stops at the next one', () => {
    expect(seqs(leaderChain(events, 1).members)).toEqual([2])
  })

  it('is empty while the latest LEADER_CHANGED is a vacancy', () => {
    expect(leaderChain(events.slice(0, 4))).toBeNull()
    expect(leaderChain([])).toBeNull()
  })
})

describe('scenarioBands', () => {
  it('spans each run from SCENARIO_STARTED to SCENARIO_FINISHED, matching runId', () => {
    const bands = scenarioBands([
      ev(1, 1, 0, 'SCENARIO_STARTED', { data: { runId: 'a', title: 'One' } }),
      ev(2, 1, 0, 'STEP_PASSED', { data: { runId: 'a' } }),
      ev(3, 1, 0, 'SCENARIO_STARTED', { data: { runId: 'b', title: 'Two' } }),
      ev(4, 1, 0, 'SCENARIO_FINISHED', { data: { runId: 'a', outcome: 'PASSED' } }),
    ])
    expect(bands.map((b) => [b.title, b.startSequence, b.endSequence, b.outcome])).toEqual([
      ['Scenario: One', 1, 4, 'PASSED'],
      ['Scenario: Two', 3, null, null],
    ])
  })
})
