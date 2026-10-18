import { describe, expect, it } from 'vitest'
import overviewBefore from '@/test/fixtures/mpi/overview-before-start.json'
import overviewAfter from '@/test/fixtures/mpi/overview-after-start.json'
import overviewCrashed from '@/test/fixtures/mpi/overview-node-crashed.json'
import overviewLeader from '@/test/fixtures/mpi/overview-leader.json'
import startFixture from '@/test/fixtures/mpi/start.json'
import startWithFailure from '@/test/fixtures/mpi/start-with-failure.json'
import broadcast from '@/test/fixtures/mpi/collective-broadcast.json'
import scatter from '@/test/fixtures/mpi/collective-scatter.json'
import gather from '@/test/fixtures/mpi/collective-gather.json'
import partial from '@/test/fixtures/mpi/collective-partial.json'
import refused from '@/test/fixtures/mpi/collective-refused.json'
import skipped from '@/test/fixtures/mpi/collective-node-crashed-skipped.json'
import requestedRoot from '@/test/fixtures/mpi/collective-requested-root.json'
import leaderRoot from '@/test/fixtures/mpi/collective-leader-root.json'
import latest from '@/test/fixtures/mpi/collective-latest.json'
import byId from '@/test/fixtures/mpi/collective-by-id.json'
import history from '@/test/fixtures/mpi/collectives-history.json'
import events from '@/test/fixtures/mpi/events.json'
import { fanModel, needsStart } from './mpiModel'

// Real fixtures only: nothing here is modified.
const OVERVIEWS = { overviewBefore, overviewAfter, overviewCrashed, startFixture, startWithFailure, overviewLeader }
const COLLECTIVES = { broadcast, scatter, gather, partial, refused, skipped, requestedRoot, leaderRoot, latest, byId }
const ALL_COLLECTIVES = [...Object.values(COLLECTIVES), ...history, overviewAfter.latest, overviewCrashed.latest, startFixture.latest]

const COLLECTIVE_FIELDS = ['id', 'startedAt', 'operation', 'rootNodeId', 'rootSource', 'rootRank', 'rankNodeIds', 'skippedNodeIds',
  'count', 'seed', 'outcome', 'refusal', 'inputChecksum', 'totalNanos', 'deadlineMillis', 'transfers', 'gathered', 'verification',
  'payloadSizeBasis', 'latencyBasis']
const TRANSFER_FIELDS = ['rank', 'fromNodeId', 'toNodeId', 'direction', 'status', 'offset', 'elementCount', 'latencyNanos',
  'timeToFailureNanos', 'requestBytes', 'replyBytes', 'expectedChecksum', 'reportedChecksum', 'verified', 'failureStage',
  'failureKind', 'failureDescription', 'failureClass', 'failureMessage', 'failureCauseClass', 'senderLamportAtSend', 'replyLamport',
  'lamportAfterReply', 'notAttemptedReason']
const NUMBERS = ['latencyNanos', 'timeToFailureNanos', 'requestBytes', 'replyBytes', 'senderLamportAtSend', 'replyLamport', 'lamportAfterReply']

describe('mpi contract fixtures', () => {
  it.each(Object.entries(OVERVIEWS))('%s has every overview field the page reads', (_, overview) => {
    expect(Object.keys(overview).sort()).toEqual(['actionInProgress', 'algorithmNote', 'boundName', 'coHosting',
      'defaultRootNodeId', 'defaultRootSource', 'latencyBasis', 'latest', 'limits', 'operations', 'participants',
      'payloadSizeBasis', 'status'])
    expect(Object.keys(overview.limits).sort()).toEqual(['callTimeoutMillis', 'collectiveDeadlineMillis', 'connectTimeoutMillis',
      'historySize', 'maxPayloadElements', 'maxSeed', 'recommendedMinClientTimeoutMillis', 'worstCaseTransferMillis'])
    expect(overview.coHosting.simulated).toBe(true)
    expect(overview.operations.map((op) => op.operation)).toEqual(['BROADCAST', 'SCATTER', 'GATHER'])
    for (const node of overview.participants) {
      expect(Object.keys(node).sort()).toEqual(['collectiveEndpointExported', 'leader', 'nodeId', 'nodeStatus', 'registryPort',
        'rmiState', 'startFailure', 'storedCollectiveIds'])
      expect(node.storedCollectiveIds === null).toBe(!node.collectiveEndpointExported)   // null exactly while not exported
    }
    expect(fanModel(overview, overview.latest).nodes).toHaveLength(overview.participants.length)
  })

  it('serves only the root rules the backend can still produce (9A.3: no LOWEST_LIVE_ID)', () => {
    const sources = [...Object.values(COLLECTIVES).map((c) => c.rootSource),
      ...Object.values(OVERVIEWS).map((o) => o.defaultRootSource)]
    expect(new Set(sources)).toEqual(new Set(['REQUESTED', 'LEADER', null]))
    expect(leaderRoot).toMatchObject({ rootNodeId: 5, rootSource: 'LEADER' })
    expect(overviewLeader).toMatchObject({ defaultRootNodeId: 5, defaultRootSource: 'LEADER' })
  })

  it('needs a start only on a fresh backend and after a start that left a node unexported', () => {
    expect(Object.entries(OVERVIEWS).filter(([, o]) => needsStart(o)).map(([name]) => name))
      .toEqual(['overviewBefore', 'startWithFailure'])
  })

  it.each(ALL_COLLECTIVES.map((c) => [`${c.operation} ${c.id} ${c.outcome}`, c]))('%s has every field the page reads', (_, c) => {
    expect(Object.keys(c).sort()).toEqual([...COLLECTIVE_FIELDS].sort())
    for (const transfer of c.transfers) expect(Object.keys(transfer).sort()).toEqual([...TRANSFER_FIELDS].sort())
  })

  it('keeps the null rules: the root\'s local part has no number, a delivered part no failure time, a failed part no latency', () => {
    const transfers = ALL_COLLECTIVES.flatMap((c) => c.transfers)
    for (const t of transfers.filter((t) => t.status === 'LOCAL')) {
      for (const key of NUMBERS) expect(t[key]).toBeNull()
      expect(t.verified).toBeNull()
    }
    for (const t of transfers.filter((t) => t.status === 'DELIVERED')) {
      expect(typeof t.latencyNanos).toBe('number')
      expect(t.timeToFailureNanos).toBeNull()
    }
    const failed = transfers.filter((t) => t.status === 'FAILED')
    expect(failed.length).toBeGreaterThan(0)
    for (const t of failed) {
      expect(typeof t.timeToFailureNanos).toBe('number')
      expect(t.latencyNanos).toBeNull()
      if (t.failureStage === 'LOOKUP') for (const key of NUMBERS.filter((k) => k !== 'timeToFailureNanos')) expect(t[key]).toBeNull()
    }
  })

  it('keeps the refusal rules: no root rank, checksum, time, verification, transfers or basis texts', () => {
    for (const c of ALL_COLLECTIVES.filter((c) => c.outcome === 'REFUSED')) {
      expect(c).toMatchObject({ rootRank: null, inputChecksum: null, totalNanos: null, verification: null, transfers: [],
        payloadSizeBasis: null, latencyBasis: null })
      expect(c.refusal.reason).toBe('COUNT_BELOW_PARTICIPANTS')
    }
  })

  it('carries no vector values: gathered blocks have checksums only', () => {
    for (const block of gather.gathered) {
      expect(Object.keys(block).sort()).toEqual(['checksum', 'hole', 'length', 'nodeId', 'offset', 'rank'])
    }
  })

  it('has the event types the event log shows, a refusal ending with REFUSED', () => {
    expect([...new Set(events.map((e) => e.type))].sort())
      .toEqual(['COLLECTIVE_ENDPOINT_EXPORTED', 'COLLECTIVE_FINISHED', 'COLLECTIVE_REFUSED', 'COLLECTIVE_STARTED'])
    const forSeven = events.filter((e) => e.data?.collectiveId === 7).map((e) => e.type)
    expect(forSeven).toEqual(['COLLECTIVE_STARTED', 'COLLECTIVE_REFUSED'])
    expect(events.every((e) => !('latencyMillis' in (e.data ?? {})))).toBe(true)
  })
})
