import { describe, expect, it } from 'vitest'
import overviewBefore from '@/test/fixtures/multithreading/overview-before.json'
import overviewAfter from '@/test/fixtures/multithreading/overview-after-batch.json'
import requestsFixture from '@/test/fixtures/multithreading/requests.json'
import batchFixture from '@/test/fixtures/multithreading/batch.json'
import backpressureFixture from '@/test/fixtures/multithreading/backpressure.json'
import busyFixture from '@/test/fixtures/multithreading/error-409-module-busy.json'
import nodeDownFixture from '@/test/fixtures/multithreading/error-409-node-down.json'
import validationFixture from '@/test/fixtures/multithreading/error-400-validation.json'
import notFoundFixture from '@/test/fixtures/multithreading/error-404-unknown-node.json'
import eventsFixture from '@/test/fixtures/multithreading/events.json'

/*
 * The fields the Multithreading page reads, checked against the real JSON captured from the
 * backend in E2c. A backend rename or type change breaks this test as well as the backend's
 * own MultithreadingControllerTest.
 */

const isString = (value) => typeof value === 'string'
const isNumber = (value) => typeof value === 'number' && Number.isFinite(value)
const isBoolean = (value) => typeof value === 'boolean'
const isStringOrNull = (value) => value === null || isString(value)

function expectShape(object, shape, where) {
  for (const [field, check] of Object.entries(shape)) {
    expect(object, `${where} has ${field}`).toHaveProperty(field)
    expect(check(object[field]), `${where}.${field} = ${JSON.stringify(object[field])}`).toBe(true)
  }
}

const OVERVIEW = {
  status: isString,
  actionInProgress: isStringOrNull,
  capacityNote: isString,
  workloads: Array.isArray,
  nodes: Array.isArray,
}
const WORKLOAD = { type: isString, description: isString, simulated: isBoolean, simulatedReason: isStringOrNull }
const NODE = {
  nodeId: isNumber,
  nodeStatus: isString,
  capacity: isString,
  capacityConfigured: isBoolean,
  threads: isNumber,
  workMultiplier: isNumber,
  port: isNumber,
  serviceRunning: isBoolean,
}
const STATS = {
  activeThreads: isNumber,
  maxPoolSize: isNumber,
  queuedRequests: isNumber,
  queueCapacity: isNumber,
  completedTasks: isNumber,
  requestsPerSecond: isNumber,
  averageResponseTimeMillis: isNumber,
  p95ResponseTimeMillis: isNumber,
  sampleCount: isNumber,
  statusCounts: (value) => ['COMPLETED', 'FAILED', 'REJECTED'].every((key) => isNumber(value?.[key])),
}
const BATCH = {
  batchId: isString,
  nodeId: isNumber,
  kind: isString,
  requested: isNumber,
  accepted: isNumber,
  rejected: isNumber,
  requestIds: Array.isArray,
}
const REQUEST = {
  id: isString,
  type: isString,
  status: isString,
  threadName: isStringOrNull,
  queueWaitMillis: isNumber,
  processingMillis: isNumber,
  totalMillis: isNumber,
  resultSummary: isStringOrNull,
  errorMessage: isStringOrNull,
}
const PROBLEM = { title: isString, status: isNumber, detail: isString }
const EVENT = {
  sequence: isNumber,
  module: isString,
  nodeId: isNumber,
  type: isString,
  lamportTime: isNumber,
  wallTime: isString,
  message: isString,
}

describe('E2c contract fixtures, as the Multithreading page reads them', () => {
  it('overview before first use: every node, stats null rather than NaN', () => {
    expectShape(overviewBefore, OVERVIEW, 'overview')
    overviewBefore.workloads.forEach((workload, i) => expectShape(workload, WORKLOAD, `workloads[${i}]`))
    overviewBefore.nodes.forEach((node, i) => {
      expectShape(node, NODE, `nodes[${i}]`)
      expect(node.stats).toBeNull()
    })
  })

  it('overview after a batch: the stats object the executor view and measurements read', () => {
    expectShape(overviewAfter, OVERVIEW, 'overview')
    const node = overviewAfter.nodes.find((candidate) => candidate.nodeId === 1)
    expectShape(node, NODE, 'node 1')
    expectShape(node.stats, STATS, 'node 1 stats')
  })

  it('the workload labels: sleeps simulated with a reason, hashing not', () => {
    const byType = Object.fromEntries(overviewBefore.workloads.map((workload) => [workload.type, workload]))
    expect(byType.CPU_HASH.simulated).toBe(false)
    expect(byType.IO_SIMULATED.simulated).toBe(true)
    expect(byType.MIXED.simulated).toBe(true)
    expect(byType.IO_SIMULATED.simulatedReason).toMatch(/\.$/)
  })

  it('batch and backpressure responses', () => {
    expectShape(batchFixture, BATCH, 'batch')
    expectShape(backpressureFixture, BATCH, 'backpressure')
    expect(backpressureFixture.kind).toBe('BACKPRESSURE')
    expect(backpressureFixture.rejected).toBeGreaterThan(0)
  })

  it('requests list', () => {
    expect(requestsFixture.length).toBeGreaterThan(0)
    requestsFixture.forEach((request, i) => expectShape(request, REQUEST, `requests[${i}]`))
  })

  it('error bodies: title, status, detail, and the 400 field errors', () => {
    for (const [name, body] of Object.entries({ busyFixture, nodeDownFixture, validationFixture, notFoundFixture })) {
      expectShape(body, PROBLEM, name)
    }
    expect(Object.keys(validationFixture.errors).sort()).toEqual(['count', 'payloadSize'])
  })

  it('module events', () => {
    eventsFixture.forEach((event, i) => expectShape(event, EVENT, `events[${i}]`))
    expect(eventsFixture.every((event) => event.module === 'multithreading')).toBe(true)
  })

  it('never contains NaN or Infinity in any body', () => {
    for (const body of [overviewBefore, overviewAfter, requestsFixture, batchFixture, backpressureFixture, eventsFixture]) {
      expect(JSON.stringify(body)).not.toMatch(/NaN|Infinity/)
    }
  })
})
