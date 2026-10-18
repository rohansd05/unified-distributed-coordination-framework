import { describe, expect, it } from 'vitest'
import clockSyncAfterSync from '@/test/fixtures/clocksync/overview-after-sync.json'
import electionReelection from '@/test/fixtures/election/overview-after-reelection.json'
import ftAfterRuns from '@/test/fixtures/faulttolerance/overview-after-runs.json'
import ftBeforeStart from '@/test/fixtures/faulttolerance/overview-before-start.json'
import lbAfterRun from '@/test/fixtures/loadbalancing/overview-after-run.json'
import mapReduceAfterRuns from '@/test/fixtures/mapreduce/overview-after-runs.json'
import mapReduceRuns from '@/test/fixtures/mapreduce/runs-history.json'
import matrixLeader from '@/test/fixtures/matrix/overview-leader.json'
import mpiLeader from '@/test/fixtures/mpi/overview-leader.json'
import replicationAfterTakeover from '@/test/fixtures/replication/overview-after-takeover.json'
import {
  evidenceHeadline,
  joinIds,
  leaderIdOf,
  linkEvidence,
  roleRows,
  roleSummary,
} from './evidenceModel'

function node(id, { status = 'UP', roles = [] } = {}) {
  return { id, status, roles }
}

function clusterWith(leaderId, extra = {}) {
  return {
    nodes: [1, 2, 3, 4, 5].map((id) => node(id, {
      status: extra.crashed?.includes(id) ? 'CRASHED' : 'UP',
      roles: [...(id === leaderId ? ['LEADER'] : []), ...(extra.roles?.[id] || [])],
    })),
  }
}

const ok = (data) => ({ status: 'ok', data })

/** Real captures (9A.3, 9A.4 and 9A.1 fixtures): every role sits on node 5, the leader. */
const ALL_ON_FIVE = {
  clocksync: ok(clockSyncAfterSync),
  replication: ok(replicationAfterTakeover),
  mapreduce: ok(mapReduceAfterRuns),
  faulttolerance: ok(ftAfterRuns),
  mpi: ok(mpiLeader),
  matrix: ok(matrixLeader),
}

const stateOf = (rows, lab) => rows.find((r) => r.lab === lab).state

describe('leaderIdOf', () => {
  it('is the node holding LEADER, and null without one', () => {
    expect(leaderIdOf(clusterWith(5))).toBe(5)
    expect(leaderIdOf(clusterWith(null))).toBeNull()
    expect(leaderIdOf(null)).toBeNull()
  })

  it('never names a crashed node', () => {
    const cluster = { nodes: [node(5, { status: 'CRASHED', roles: ['LEADER'] })] }
    expect(leaderIdOf(cluster)).toBeNull()
  })
})

describe('roleRows (link L1)', () => {
  it('on the real captures every role matches the leader, node 5', () => {
    const rows = roleRows(clusterWith(5), ALL_ON_FIVE)
    expect(rows.map((r) => r.lab)).toEqual([3, 5, 7, 8, 9, 10])
    expect(rows.every((r) => r.state === 'match' && r.holder === 5)).toBe(true)
    expect(roleSummary(clusterWith(5), rows)).toBe('Leader: node 5. 6 of 6 active roles match it.')
  })

  it('shows a mismatch honestly: the old primary keeps the role until the next accepted write', () => {
    const rows = roleRows(clusterWith(4, { crashed: [5] }), ALL_ON_FIVE)
    const replication = rows.find((r) => r.lab === 5)
    expect(replication.state).toBe('mismatch')
    expect(replication.holder).toBe(5)
    expect(replication.note).toBe(
      'Node 5 (crashed) was made primary before the leader changed; the primary moves to node 4 at the next accepted write.',
    )
    expect(roleSummary(clusterWith(4, { crashed: [5] }), rows)).toBe('Leader: node 4. 0 of 6 active roles match it.')
  })

  it('prefers the shared PRIMARY role over the node Experiment 5 last made primary', () => {
    const cluster = clusterWith(4, { roles: { 4: ['PRIMARY'] } })
    const row = roleRows(cluster, ALL_ON_FIVE).find((r) => r.lab === 5)
    expect(row).toMatchObject({ state: 'match', holder: 4, basis: 'shared PRIMARY role' })
  })

  it('Experiment 8 not started is "not taken yet", not a mismatch', () => {
    const rows = roleRows(clusterWith(5), { ...ALL_ON_FIVE, faulttolerance: ok(ftBeforeStart) })
    const row = rows.find((r) => r.lab === 8)
    expect(row.state).toBe('idle')
    expect(row.note).toMatch(/^Not started: Start on the Experiment 8 page/)
    expect(roleSummary(clusterWith(5), rows)).toBe('Leader: node 5. 5 of 5 active roles match it. 1 not taken yet.')
  })

  it('a started Experiment 8 with a primary that is not the leader says a failover is under way, with its bound', () => {
    const ft = { ...ftAfterRuns, current: { ...ftAfterRuns.current, currentPrimaryId: 5 } }
    const row = roleRows(clusterWith(4, { crashed: [5] }), { ...ALL_ON_FIVE, faulttolerance: ok(ft) }).find((r) => r.lab === 8)
    expect(row.note).toBe('Failover under way: node 5 (crashed) is primary until Experiment 8 promotes node 4 (at most about 10.3 s).')
  })

  it('with no leader and no holders every row says the next action elects one', () => {
    const empty = {
      ...ALL_ON_FIVE,
      clocksync: ok({ ...clockSyncAfterSync, timeDaemonNodeId: null, latestRound: null }),
      replication: ok({ ...replicationAfterTakeover, currentPrimaryNodeId: null }),
      mapreduce: ok({ ...mapReduceAfterRuns, coordinatorId: null, latestRun: null }),
      faulttolerance: ok(ftBeforeStart),
      mpi: ok({ ...mpiLeader, defaultRootNodeId: null, defaultRootSource: null, latest: null }),
      matrix: ok({ ...matrixLeader, defaultRootNodeId: null, defaultRootSource: null, latest: null }),
    }
    const rows = roleRows(clusterWith(null), empty)
    expect(rows.every((r) => r.state === 'no-leader')).toBe(true)
    expect(roleSummary(clusterWith(null), rows)).toBe('No leader yet: the first action that needs one asks Experiment 4 to elect it.')
  })

  it('a holder while there is no leader is a mismatch that names the re-election', () => {
    const rows = roleRows(clusterWith(null), ALL_ON_FIVE)
    expect(rows.every((r) => r.state === 'mismatch')).toBe(true)
    expect(rows[0].note).toMatch(/^Experiment 4 has no leader right now/)
  })

  it('a failed read is "unavailable" and an unread source is "loading"', () => {
    const rows = roleRows(clusterWith(5), { ...ALL_ON_FIVE, mpi: { status: 'error', data: null }, matrix: undefined })
    expect(stateOf(rows, 9)).toBe('unavailable')
    expect(rows.find((r) => r.lab === 9).note).toBe('Couldn’t read Experiment 9’s overview.')
    expect(stateOf(rows, 10)).toBe('loading')
  })

  it('a running job keeps its coordinator, and that is said beside the next job\'s coordinator', () => {
    const mapreduce = { ...mapReduceAfterRuns, coordinatorId: 4, latestRun: { ...mapReduceAfterRuns.latestRun, state: 'RUNNING' } }
    const row = roleRows(clusterWith(4), { ...ALL_ON_FIVE, mapreduce: ok(mapreduce) }).find((r) => r.lab === 7)
    expect(row.state).toBe('match')
    expect(row.extra).toBe('The running job keeps node 5 as its coordinator.')
  })
})

describe('linkEvidence', () => {
  const sources = {
    cluster: clusterWith(5),
    overviews: {
      ...ALL_ON_FIVE,
      election: ok(electionReelection),
      loadbalancing: ok(lbAfterRun),
      mapreduceRuns: ok(mapReduceRuns),
    },
    events: [
      { sequence: 7, lamportTime: 40, nodeId: 3, module: 'election', type: 'MESSAGE_RECEIVED' },
      { sequence: 9, lamportTime: 12, nodeId: 0, module: 'cluster', type: 'LEADER_CHANGED' },
      { sequence: 8, lamportTime: 99, nodeId: 2, module: 'rmi', type: 'CALL_SERVED' },
    ],
  }

  it('L1 is live with the role rows', () => {
    const evidence = linkEvidence('L1', sources)
    expect(evidence.kind).toBe('live')
    expect(evidence.rows).toHaveLength(6)
    expect(evidence.lines[0]).toBe('Leader: node 5. 6 of 6 active roles match it.')
  })

  it('L2 reads the shared detector\'s suspicions and the last Experiment 8 failover', () => {
    const evidence = linkEvidence('L2', sources)
    expect(evidence.kind).toBe('live')
    expect(evidence.lines).toEqual([
      'Nodes 1, 2, 3 and 4 suspect node 5.',
      'Heartbeats every 700 ms; a peer silent for 2500 ms is suspected.',
      'Last Experiment 8 failover (run 1): node 1’s detector suspected primary node 5 after 2571 ms; ' +
        'Experiment 4 elected node 4 at 3480 ms; Experiment 8 promoted node 4 at 3498 ms.',
    ])
  })

  it('L3 gives the last balancer run\'s requests per node', () => {
    const evidence = linkEvidence('L3', sources)
    expect(evidence.lines[0]).toBe('Last run (round robin): 60 of 60 requests ran on Experiment 2 executors.')
    expect(evidence.lines[1]).toMatch(/^Requests per node: node 1 12, node 2 /)
  })

  it('L4 takes the latest event by bus sequence, not by Lamport time, and a stamped RMI transfer', () => {
    const evidence = linkEvidence('L4', sources)
    expect(evidence.lines[0]).toBe('Latest event: Lamport 12 on the cluster clock (leader changed, cluster).')
    expect(evidence.lines[1]).toBe(
      'An RMI transfer from node 5 to node 1: sent at Lamport 228, reply stamped 230, sender after the reply 231.',
    )
  })

  it('L5 finds the newest run over the live event log', () => {
    const evidence = linkEvidence('L5', sources)
    expect(evidence.kind).toBe('live')
    expect(evidence.lines[0]).toBe('Run 19647e5a: Events per category over the live cluster event log, completed.')
  })

  it('L6 counts the remote collective transfers and RMI fetches of the last matrix run', () => {
    const evidence = linkEvidence('L6', sources)
    expect(evidence.lines).toEqual([
      'Run 1: size 50 from root node 5, complete.',
      'Experiment 9 scatter complete (4 remote transfers) and broadcast complete (4), over Experiment 1 RMI; 4 blocks of C fetched by RMI.',
      'The result matches the sequential product.',
    ])
  })

  it.each([
    ['L2', 'LeaderFailoverIntegrationTest'],
    ['L3', 'L3LoadBalancingIntoExecutorsIntegrationTest'],
    ['L4', 'L4LamportOnMessagesIntegrationTest'],
    ['L5', 'L5EventExportToMapReduceIntegrationTest'],
    ['L6', 'L6MatrixOverCollectivesOverRmiIntegrationTest'],
  ])('%s falls back to the test that proves it when nothing live exists', (id, test) => {
    const evidence = linkEvidence(id, { cluster: clusterWith(null), overviews: {}, events: [] })
    expect(evidence.kind).toBe('test')
    expect(evidence.test).toBe(test)
    expect(evidenceHeadline(evidence)).toBe(`Wired, verified by ${test}`)
    expect(evidence.lines[0]).not.toMatch(/planned|coming soon/i)
  })

  it('L1 with no leader and nothing read falls back to its test', () => {
    const evidence = linkEvidence('L1', { cluster: clusterWith(null), overviews: {}, events: [] })
    expect(evidence.kind).toBe('test')
    expect(evidenceHeadline(evidence)).toBe('Wired, verified by LeaderRolesIntegrationTest')
  })

  it('an unknown link has no evidence and never throws', () => {
    expect(linkEvidence('L9', sources)).toEqual({ kind: 'test', lines: [], test: null })
  })
})

describe('joinIds', () => {
  it('joins with commas and "and"', () => {
    expect(joinIds([])).toBe('')
    expect(joinIds([1])).toBe('1')
    expect(joinIds([1, 2])).toBe('1 and 2')
    expect(joinIds([1, 2, 3])).toBe('1, 2 and 3')
  })
})
