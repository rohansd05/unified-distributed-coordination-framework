import { describe, expect, it } from 'vitest'
import overviewInitial from '@/test/fixtures/election/overview-initial.json'
import overviewAfterBully from '@/test/fixtures/election/overview-after-bully.json'
import overviewLeaderCrashed from '@/test/fixtures/election/overview-leader-crashed.json'
import overviewAfterReelection from '@/test/fixtures/election/overview-after-reelection.json'
import overviewAfterRing from '@/test/fixtures/election/overview-after-ring.json'
import overviewAfterRecovery from '@/test/fixtures/election/overview-after-recovery.json'
import overviewAfterReset from '@/test/fixtures/election/overview-after-reset.json'
import overviewTimedOut from '@/test/fixtures/election/overview-ring-timed-out.json'
import startBully from '@/test/fixtures/election/start-bully.json'
import startRing from '@/test/fixtures/election/start-ring.json'
import busy from '@/test/fixtures/election/error-409-module-busy.json'
import nodeDown from '@/test/fixtures/election/error-409-node-down.json'
import unknownNode from '@/test/fixtures/election/error-404-unknown-node.json'
import validation from '@/test/fixtures/election/error-400-validation.json'
import unreadable from '@/test/fixtures/election/error-400-unreadable.json'
import events from '@/test/fixtures/election/events.json'
import eventsCluster from '@/test/fixtures/election/events-cluster.json'
import clusterWithLeader from '@/test/fixtures/election/cluster-with-leader.json'
import clusterLeaderCrashed from '@/test/fixtures/election/cluster-leader-crashed.json'

/*
 * Every field the Election page reads, checked against the real JSON captured from the running
 * backend (frontend/src/test/fixtures/election, never edited). A backend rename or type change
 * breaks this test as well as the backend's ElectionControllerTest.
 */

const isString = (v) => typeof v === 'string'
const isNumber = (v) => typeof v === 'number' && Number.isFinite(v)
const isBoolean = (v) => typeof v === 'boolean'
const orNull = (check) => (v) => v === null || check(v)
const oneOf = (...values) => (v) => values.includes(v)
const isArrayOf = (check) => (v) => Array.isArray(v) && v.every(check)

function expectShape(object, shape, where) {
  expect(object, `${where} is an object`).toBeTypeOf('object')
  for (const [field, check] of Object.entries(shape)) {
    expect(object, `${where} has ${field}`).toHaveProperty(field)
    expect(check(object[field]), `${where}.${field} = ${JSON.stringify(object[field])}`).toBe(true)
  }
}

const ROUND = {
  roundId: isNumber,
  algorithm: oneOf('BULLY', 'RING'),
  trigger: oneOf('MANUAL', 'LEADER_FAILURE', 'RECOVERY'),
  initiatorNodeId: isNumber,
  startedAt: isString,
  outcome: oneOf('IN_PROGRESS', 'ELECTED', 'TIMED_OUT'),
  leaderId: orNull(isNumber),
  durationMillis: orNull(isNumber),
}
const NODE = {
  nodeId: isNumber,
  status: oneOf('UP', 'CRASHED'),
  serviceRunning: isBoolean,
  coordinatorId: orNull(isNumber),
  suspectedPeers: isArrayOf(isNumber),
  port: isNumber,
  electionsWon: isNumber,
  roundsTimed: isNumber,
  meanDurationMillis: orNull(isNumber),
}
const CONSENSUS = {
  reached: isBoolean, coordinatorId: orNull(isNumber), coordinatorAlive: isBoolean, passed: isBoolean,
  disagreeingNodes: isArrayOf(isNumber),
}
const SETTINGS = {
  okTimeoutMillis: isNumber, coordinatorTimeoutMillis: isNumber, probeTimeoutMillis: isNumber,
  ringCompletionTimeoutMillis: isNumber, heartbeatIntervalMillis: isNumber, heartbeatTimeoutMillis: isNumber,
  roundTimeoutMillis: isNumber,
}
const EVENT = {
  sequence: isNumber, module: isString, nodeId: isNumber, type: isString, lamportTime: isNumber,
  wallTime: isString, peerId: orNull(isNumber), message: orNull(isString), data: (v) => typeof v === 'object' && v !== null,
}

const OVERVIEWS = {
  overviewInitial, overviewAfterBully, overviewLeaderCrashed, overviewAfterReelection, overviewAfterRing,
  overviewAfterRecovery, overviewAfterReset, overviewTimedOut,
}

describe('election contract (real captures)', () => {
  it.each(Object.entries(OVERVIEWS))('%s has every overview field the page reads', (name, overview) => {
    expectShape(overview, {
      status: oneOf('IDLE', 'RUNNING', 'BUSY', 'ERROR'),
      leaderId: orNull(isNumber),
      servicesStarted: isBoolean,
      nodes: Array.isArray,
      consensus: (v) => typeof v === 'object' && v !== null,
      currentRound: (v) => v === null || typeof v === 'object',
      lastRound: (v) => v === null || typeof v === 'object',
      settings: (v) => typeof v === 'object' && v !== null,
    }, name)
    overview.nodes.forEach((node, i) => expectShape(node, NODE, `${name}.nodes[${i}]`))
    expectShape(overview.consensus, CONSENSUS, `${name}.consensus`)
    expectShape(overview.settings, SETTINGS, `${name}.settings`)
    if (overview.lastRound) expectShape(overview.lastRound, ROUND, `${name}.lastRound`)
    if (overview.currentRound) expectShape(overview.currentRound, ROUND, `${name}.currentRound`)
  })

  it('the 202 start answers are open rounds with nothing measured yet', () => {
    for (const [name, round] of Object.entries({ startBully, startRing })) {
      expectShape(round, ROUND, name)
      expect(round.outcome).toBe('IN_PROGRESS')
      expect(round.leaderId).toBeNull()
      expect(round.durationMillis).toBeNull()
    }
  })

  it('the states the page shows honestly are in the captures', () => {
    expect(overviewInitial.servicesStarted).toBe(false)   // "Detector idle, starts on the first election"
    expect(overviewTimedOut.lastRound.outcome).toBe('TIMED_OUT')
    expect(overviewTimedOut.lastRound.durationMillis).toBeNull()
    expect(overviewAfterReelection.lastRound.trigger).toBe('LEADER_FAILURE')
    expect(overviewAfterRecovery.lastRound.trigger).toBe('RECOVERY')
    expect(overviewLeaderCrashed.consensus.coordinatorAlive).toBe(false)
  })

  it('per-node figures: wins are counts (0 is real), the mean is null exactly when nothing was timed', () => {
    for (const overview of Object.values(OVERVIEWS)) {
      for (const node of overview.nodes) {
        expect(Number.isInteger(node.electionsWon)).toBe(true)
        expect(node.meanDurationMillis === null).toBe(node.roundsTimed === 0)
      }
    }
  })

  it('the ProblemDetail errors carry title, status and detail; 409s say why', () => {
    for (const [name, error] of Object.entries({ busy, nodeDown, unknownNode, validation, unreadable })) {
      expectShape(error, { title: isString, status: isNumber, detail: isString }, name)
    }
    expect(busy.status).toBe(409)
    expect(busy.moduleId).toBe('election')
    expect(nodeDown.status).toBe(409)
    expect(nodeDown.nodeId).toBe(2)
    expect(unknownNode.status).toBe(404)
  })

  it('election events have the fields the arrows read, and no message has an all-capitals word', () => {
    events.forEach((event, i) => expectShape(event, EVENT, `events[${i}]`))
    const sent = events.filter((e) => e.type === 'MESSAGE_SENT')
    expect(sent.length).toBeGreaterThan(0)
    sent.forEach((e) => expect(isString(e.data.messageType) && isNumber(e.peerId)).toBe(true))
    const rounds = events.filter((e) => e.type === 'ELECTION_ROUND_STARTED' || e.type === 'ELECTION_ROUND_FINISHED')
    rounds.forEach((e) => expect(isNumber(e.data.roundId)).toBe(true))
    events.forEach((e) => expect(e.message, e.message).not.toMatch(/\b[A-Z]{2,}\b/))
  })

  it('the cluster log has LEADER_CHANGED events with real Lamport values, node 0 and the leader ids', () => {
    const changes = eventsCluster.filter((e) => e.type === 'LEADER_CHANGED')
    expect(changes.length).toBeGreaterThan(0)
    for (const event of changes) {
      expectShape(event, EVENT, `LEADER_CHANGED ${event.sequence}`)
      expect(event.module).toBe('cluster')
      expect(event.nodeId).toBe(0)
      expect(event.data).toHaveProperty('leaderId')
      expect(event.data).toHaveProperty('previousLeaderId')
    }
  })

  it('cluster roles carry the leader the page shows', () => {
    expect(clusterWithLeader.nodes.find((n) => n.roles.includes('LEADER')).id).toBe(5)
    expect(clusterLeaderCrashed.nodes.every((n) => n.roles.length === 0)).toBe(true)
  })
})
