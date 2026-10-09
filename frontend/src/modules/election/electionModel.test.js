import { describe, expect, it } from 'vitest'
import overviewInitial from '@/test/fixtures/election/overview-initial.json'
import overviewAfterBully from '@/test/fixtures/election/overview-after-bully.json'
import overviewAfterReelection from '@/test/fixtures/election/overview-after-reelection.json'
import overviewTimedOut from '@/test/fixtures/election/overview-ring-timed-out.json'
import clusterWithLeader from '@/test/fixtures/election/cluster-with-leader.json'
import clusterLeaderCrashed from '@/test/fixtures/election/cluster-leader-crashed.json'
import events from '@/test/fixtures/election/events.json'
import eventsCluster from '@/test/fixtures/election/events-cluster.json'
import startRing from '@/test/fixtures/election/start-ring.json'
import {
  MAX_ARCS, describeLeader, describeRoundEnd, describeRoundStart, detectorState, isRingOfOne, leaderChangeText,
  leaderIdFrom, liveNodeCount, ringLayout, roundMessages,
} from './electionModel'

describe('electionModel', () => {
  it('reports the detector idle before the first election and running after it', () => {
    expect(detectorState(null)).toBe('unknown')
    expect(detectorState(overviewInitial)).toBe('idle')
    expect(detectorState(overviewAfterBully)).toBe('running')
  })

  it('counts live nodes and spots a ring of one', () => {
    expect(liveNodeCount(null)).toBeNull()
    expect(liveNodeCount(overviewAfterBully)).toBe(5)
    expect(isRingOfOne(overviewAfterBully)).toBe(false)
    expect(liveNodeCount(overviewTimedOut)).toBe(1)
    expect(isRingOfOne(overviewTimedOut)).toBe(true)
  })

  it('takes the leader from cluster roles only', () => {
    expect(leaderIdFrom(clusterWithLeader)).toBe(5)
    expect(leaderIdFrom(clusterLeaderCrashed)).toBeNull()
    expect(leaderIdFrom(null)).toBeNull()
  })

  it('places nodes on a circle, the first at the top', () => {
    const placed = ringLayout(overviewAfterBully.nodes, { center: 180, radius: 130 })
    expect(placed).toHaveLength(5)
    expect(placed[0].x).toBeCloseTo(180)
    expect(placed[0].y).toBeCloseTo(50)
    expect(ringLayout([])).toEqual([])
  })

  it('picks the round\'s own MESSAGE_SENT events, with the backend\'s Lamport values, newest last', () => {
    const finished = events.filter((e) => e.type === 'ELECTION_ROUND_FINISHED')
    const round = finished[finished.length - 1].data
    const arrows = roundMessages(events, round)
    const started = events.find((e) => e.type === 'ELECTION_ROUND_STARTED' && e.data.roundId === round.roundId)
    const end = finished[finished.length - 1]
    expect(arrows.length).toBeGreaterThan(0)
    expect(arrows.length).toBeLessThanOrEqual(MAX_ARCS)
    for (const arrow of arrows) {
      const source = events.find((e) => e.sequence === arrow.sequence)
      expect(source.type).toBe('MESSAGE_SENT')
      expect(source.sequence).toBeGreaterThan(started.sequence)
      expect(source.sequence).toBeLessThanOrEqual(end.sequence)
      expect(arrow).toEqual({ sequence: source.sequence, from: source.nodeId, to: source.peerId,
        type: source.data.messageType, lamportTime: source.lamportTime })
    }
    expect(roundMessages(events, null)).toEqual([])
    expect(roundMessages(events, { ...startRing, roundId: 999 })).toEqual([])
  })

  it('writes the announcements in plain words', () => {
    expect(describeRoundStart(startRing)).toBe('Ring election started from node 2.')
    expect(describeRoundStart({ ...startRing, algorithm: 'BULLY', trigger: 'LEADER_FAILURE', initiatorNodeId: 3 }))
      .toBe('The leader failed. A Bully election started from node 3.')
    expect(describeRoundEnd(overviewAfterReelection.lastRound, 10000))
      .toMatch(/^Election finished: node 4 was elected in [\d.]+ ms\.$/)
    expect(describeRoundEnd(overviewTimedOut.lastRound, overviewTimedOut.settings.roundTimeoutMillis))
      .toBe('The election timed out after 10 s: no leader was agreed.')
    expect(describeLeader(4)).toBe('Node 4 is now the leader.')
    expect(describeLeader(null)).toBe('There is no leader now.')
  })

  it('describes each real LEADER_CHANGED of the cluster log', () => {
    const texts = eventsCluster.filter((e) => e.type === 'LEADER_CHANGED').map(leaderChangeText)
    expect(texts).toEqual([
      'Node 5 became the leader',
      'No leader: node 5 is no longer the leader',
      'Node 4 became the leader',
      'Node 5 became the leader, after node 4',
    ])
  })
})
