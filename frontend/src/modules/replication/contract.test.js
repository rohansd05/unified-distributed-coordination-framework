import { describe, expect, it } from 'vitest'
import overviewBefore from '@/test/fixtures/replication/overview-before.json'
import overviewAfterAsync from '@/test/fixtures/replication/overview-after-async.json'
import overviewTakeoverPending from '@/test/fixtures/replication/overview-takeover-pending.json'
import overviewAfterTakeover from '@/test/fixtures/replication/overview-after-takeover.json'
import writeSync from '@/test/fixtures/replication/write-sync.json'
import writeAsync from '@/test/fixtures/replication/write-async.json'
import writeWhileBackupDown from '@/test/fixtures/replication/write-while-backup-down.json'
import writeAfterFailover from '@/test/fixtures/replication/write-after-failover.json'
import writeAfterReclaim from '@/test/fixtures/replication/write-after-reclaim.json'
import readStale from '@/test/fixtures/replication/read-stale.json'
import readCurrent from '@/test/fixtures/replication/read-current.json'
import readUnreachable from '@/test/fixtures/replication/read-unreachable.json'
import replicasDiverged from '@/test/fixtures/replication/replicas-diverged.json'
import replicasConverged from '@/test/fixtures/replication/replicas-converged.json'
import crashBackup from '@/test/fixtures/replication/crash-backup.json'
import recoverBackup from '@/test/fixtures/replication/recover-backup.json'
import antiEntropy from '@/test/fixtures/replication/anti-entropy.json'
import staleInjection from '@/test/fixtures/replication/stale-injection.json'
import validation from '@/test/fixtures/replication/error-400-validation.json'
import crashPrimary from '@/test/fixtures/replication/error-400-crash-primary.json'
import unknownNode from '@/test/fixtures/replication/error-404-unknown-node.json'
import nodeDown from '@/test/fixtures/replication/error-409-node-down.json'
import nodeState from '@/test/fixtures/replication/error-409-node-state.json'
import eventsFixture from '@/test/fixtures/replication/events.json'
import { CELL_STATES } from './labels'

/*
 * Every field the Replication page reads, checked against the real JSON captured from the
 * running backend in E5c (frontend/src/test/fixtures/replication, never edited). A backend
 * rename or type change breaks this test as well as the backend's ReplicationControllerTest.
 */

const isString = (v) => typeof v === 'string'
const isNumber = (v) => typeof v === 'number' && Number.isFinite(v)
const isBoolean = (v) => typeof v === 'boolean'
const orNull = (check) => (v) => v === null || check(v)
const oneOf = (...values) => (v) => values.includes(v)
const isArray = Array.isArray

function expectShape(object, shape, where) {
  expect(object, `${where} is an object`).toBeTypeOf('object')
  for (const [field, check] of Object.entries(shape)) {
    expect(object, `${where} has ${field}`).toHaveProperty(field)
    expect(check(object[field]), `${where}.${field} = ${JSON.stringify(object[field])}`).toBe(true)
  }
}

const ITEM = { key: isString, value: isString, lamportTime: isNumber, originNode: isNumber, epoch: isNumber }
const PUSH = {
  backupNodeId: isNumber,
  status: oneOf('ACKED', 'FAILED', 'NOT_SENT', 'ABANDONED'),
  result: orNull(oneOf('APPLIED', 'DUPLICATE', 'STALE', 'STALE_EPOCH')),
  backupEpoch: orNull(isNumber),
  latencyMillis: orNull(isNumber),
  detail: orNull(isString),
}
const CATCH_UP = {
  sourceNodeId: isNumber, completed: isBoolean, pulled: orNull(isNumber), applied: orNull(isNumber),
  alreadyCurrent: orNull(isNumber), stale: orNull(isNumber), staleEpoch: orNull(isNumber),
  sourceEpoch: orNull(isNumber), latencyMillis: isNumber, failure: orNull(isString),
}
const ANTI_ENTROPY = {
  sourceNodeId: isNumber, targetNodeId: isNumber, completed: isBoolean, pushed: isNumber, applied: isNumber,
  alreadyCurrent: isNumber, stale: isNumber, staleEpoch: isNumber, chunksPlanned: isNumber,
  chunksAcknowledged: isNumber, latencyMillis: isNumber, failure: orNull(isString),
}
const TAKEOVER = {
  previousPrimaryNodeId: orNull(isNumber), newPrimaryNodeId: isNumber, appliedFromCatchUp: isNumber,
  catchUps: isArray, pushes: isArray, lamportTime: isNumber,
}
const WRITE = {
  writeId: isString, model: oneOf('SYNCHRONOUS', 'ASYNCHRONOUS'), primaryNodeId: isNumber, item: (v) => typeof v === 'object',
  localResult: isString, confirmMillis: isNumber, simulated: isBoolean, simulatedDelayMillis: isNumber,
  simulatedReason: orNull(isString), replicationState: oneOf('PENDING', 'COMPLETE'), backupNodeIds: isArray,
  pushes: isArray, takeover: (v) => v === null || typeof v === 'object',
}
const NODE = {
  nodeId: isNumber, nodeStatus: oneOf('UP', 'CRASHED'), role: orNull(oneOf('PRIMARY', 'BACKUP')),
  actingPrimary: isBoolean, serviceRunning: isBoolean, port: isNumber, epoch: orNull(isNumber), itemCount: orNull(isNumber),
}
const HEALTH = {
  backupNodeId: isNumber, acks: isNumber, applied: isNumber, duplicates: isNumber, staleRejections: isNumber,
  staleEpochRejections: isNumber, failures: isNumber, averageLatencyMillis: orNull(isNumber),
  maxLatencyMillis: orNull(isNumber), lastSync: orNull(isString),
}
const MODEL = { model: oneOf('SYNCHRONOUS', 'ASYNCHRONOUS'), description: isString, guarantee: isString, simulated: isBoolean, simulatedReason: orNull(isString) }
const OVERVIEW = {
  status: oneOf('IDLE', 'RUNNING', 'BUSY', 'ERROR'), actionInProgress: orNull(isString), primaryNodeId: orNull(isNumber),
  currentPrimaryNodeId: orNull(isNumber), takeoverPending: isBoolean, lastTakeover: (v) => v === null || typeof v === 'object',
  asyncDelayMillis: isNumber, asyncDelayReason: isString, timeoutMillis: isNumber, batchSize: isNumber,
  conflictRuleNote: isString, models: isArray, nodes: isArray, healthMeasuredByNodeId: orNull(isNumber), health: isArray,
}
const READ = {
  nodeId: isNumber, key: isString, referenceNodeId: orNull(isNumber), reachable: isBoolean,
  item: (v) => v === null || typeof v === 'object', referenceItem: (v) => v === null || typeof v === 'object',
  state: orNull(oneOf(...CELL_STATES)), error: orNull(isString),
}
const COLUMN = {
  nodeId: isNumber, nodeStatus: oneOf('UP', 'CRASHED'), reference: isBoolean, reachable: isBoolean,
  epoch: orNull(isNumber), itemCount: orNull(isNumber), error: orNull(isString),
}

function expectWrite(write, where) {
  expectShape(write, WRITE, where)
  expectShape(write.item, ITEM, `${where}.item`)
  write.pushes.forEach((push, i) => expectShape(push, PUSH, `${where}.pushes[${i}]`))
  if (write.takeover) {
    expectShape(write.takeover, TAKEOVER, `${where}.takeover`)
    write.takeover.catchUps.forEach((c, i) => expectShape(c, CATCH_UP, `${where}.takeover.catchUps[${i}]`))
    write.takeover.pushes.forEach((p, i) => expectShape(p, ANTI_ENTROPY, `${where}.takeover.pushes[${i}]`))
  }
}

describe('replication contract (real backend JSON)', () => {
  it.each([
    ['overview-before', overviewBefore],
    ['overview-after-async', overviewAfterAsync],
    ['overview-takeover-pending', overviewTakeoverPending],
    ['overview-after-takeover', overviewAfterTakeover],
  ])('%s: the overview, its nodes, models, health and latest results', (name, overview) => {
    expectShape(overview, OVERVIEW, name)
    overview.nodes.forEach((n, i) => expectShape(n, NODE, `${name}.nodes[${i}]`))
    overview.models.forEach((m, i) => expectShape(m, MODEL, `${name}.models[${i}]`))
    overview.health.forEach((h, i) => expectShape(h, HEALTH, `${name}.health[${i}]`))
    if (overview.latestWrite) expectWrite(overview.latestWrite, `${name}.latestWrite`)
    if (overview.latestAntiEntropy) expectShape(overview.latestAntiEntropy, ANTI_ENTROPY, `${name}.latestAntiEntropy`)
    if (overview.lastTakeover) expectShape(overview.lastTakeover, TAKEOVER, `${name}.lastTakeover`)
  })

  it('a fresh overview has nulls, never zeros, for what is not known', () => {
    expect(overviewBefore.currentPrimaryNodeId).toBeNull()
    expect(overviewBefore.healthMeasuredByNodeId).toBeNull()
    expect(overviewBefore.nodes.every((n) => n.epoch === null && n.itemCount === null)).toBe(true)
    expect(overviewBefore.latestWrite).toBeNull()
  })

  it.each([
    ['write-sync', writeSync], ['write-async', writeAsync], ['write-while-backup-down', writeWhileBackupDown],
    ['write-after-failover', writeAfterFailover], ['write-after-reclaim', writeAfterReclaim],
  ])('%s: a write result', (name, write) => expectWrite(write, name))

  it('the asynchronous write is PENDING, simulated, with the reason; a synchronous one is not simulated', () => {
    expect(writeAsync).toMatchObject({ replicationState: 'PENDING', simulated: true, simulatedDelayMillis: 450, pushes: [] })
    expect(writeAsync.simulatedReason).toBeTruthy()
    expect(writeSync).toMatchObject({ replicationState: 'COMPLETE', simulated: false, simulatedReason: null })
  })

  it.each([['read-stale', readStale], ['read-current', readCurrent], ['read-unreachable', readUnreachable]])(
    '%s: a read', (name, read) => expectShape(read, READ, name))

  it('reads carry STALE, CURRENT and UNREACHABLE; an unreachable read has a null item and an error', () => {
    expect([readStale.state, readCurrent.state, readUnreachable.state]).toEqual(['STALE', 'CURRENT', 'UNREACHABLE'])
    expect(readUnreachable.item).toBeNull()
    expect(readUnreachable.error).toBeTruthy()
  })

  it.each([['replicas-diverged', replicasDiverged], ['replicas-converged', replicasConverged]])('%s: the side-by-side view', (name, view) => {
    expectShape(view, { referenceNodeId: orNull(isNumber), consistent: orNull(isBoolean), divergences: orNull(isNumber), replicas: isArray, rows: isArray }, name)
    view.replicas.forEach((c, i) => expectShape(c, COLUMN, `${name}.replicas[${i}]`))
    for (const row of view.rows) {
      expect(isString(row.key)).toBe(true)
      for (const cell of row.cells) {
        expectShape(cell, { nodeId: isNumber, state: orNull(oneOf(...CELL_STATES)) }, `${name}.${row.key}`)
        if (cell.item) expectShape(cell.item, ITEM, `${name}.${row.key}.item`)
      }
    }
  })

  it('crash, recover, anti-entropy and stale injection results', () => {
    expectShape(crashBackup, NODE, 'crash-backup')
    expectShape(recoverBackup, NODE, 'recover-backup')
    expect(crashBackup.nodeStatus).toBe('CRASHED')
    expect(crashBackup.itemCount).toBeNull()
    expectShape(antiEntropy, ANTI_ENTROPY, 'anti-entropy')
    expectShape(staleInjection, {
      primaryNodeId: isNumber, backupNodeId: isNumber, key: isString, currentItem: (v) => typeof v === 'object',
      staleItem: (v) => typeof v === 'object', push: (v) => typeof v === 'object', rejected: isBoolean,
    }, 'stale-injection')
    expectShape(staleInjection.push, PUSH, 'stale-injection.push')
  })

  it('the error bodies are ProblemDetails the page can show', () => {
    expect(validation).toMatchObject({ status: 400, title: 'Invalid request parameters' })
    expect(Object.keys(validation.errors)).toContain('key')
    expect(crashPrimary).toMatchObject({ status: 400 })
    expect(crashPrimary.errors.nodeId).toContain('Cluster page')
    expect(unknownNode).toMatchObject({ status: 404, title: 'Unknown node', nodeId: 9 })
    expect(nodeDown).toMatchObject({ status: 409, title: 'Node down', nodeId: 4 })
    expect(nodeState).toMatchObject({ status: 409, title: 'Node state conflict', nodeId: 2 })
  })

  it('PRIMARY_SELECTED events carry what the timeline reads', () => {
    const selections = eventsFixture.filter((e) => e.type === 'PRIMARY_SELECTED')
    expect(selections.length).toBe(3)
    for (const event of selections) {
      expectShape(event, { sequence: isNumber, nodeId: isNumber, lamportTime: isNumber }, 'PRIMARY_SELECTED')
      expectShape(event.data, { appliedFromCatchUp: isNumber, catchUpSources: isArray, pushedTo: isArray }, 'PRIMARY_SELECTED.data')
    }
    expect(selections[0].data).not.toHaveProperty('previousPrimaryId')
    expect(selections[1].data.previousPrimaryId).toBe(1)
  })
})
