import { describe, expect, it } from 'vitest'
import overviewBefore from '@/test/fixtures/loadbalancing/overview-before.json'
import overviewAfterRun from '@/test/fixtures/loadbalancing/overview-after-run.json'
import overviewAfterComparison from '@/test/fixtures/loadbalancing/overview-after-comparison.json'
import overviewAfterCrashRun from '@/test/fixtures/loadbalancing/overview-after-crash-run.json'
import runAccepted from '@/test/fixtures/loadbalancing/run-accepted.json'
import crashRunAccepted from '@/test/fixtures/loadbalancing/crash-run-accepted.json'
import comparisonAccepted from '@/test/fixtures/loadbalancing/comparison-accepted.json'
import validationFixture from '@/test/fixtures/loadbalancing/error-400-validation.json'
import notFoundFixture from '@/test/fixtures/loadbalancing/error-404-unknown-node.json'
import nodeDownFixture from '@/test/fixtures/loadbalancing/error-409-node-down.json'
import busyFixture from '@/test/fixtures/loadbalancing/error-409-module-busy.json'
import eventsFixture from '@/test/fixtures/loadbalancing/events.json'
import { STRATEGY_ORDER } from './labels'

/*
 * Every field the Load Balancing page reads, checked against the real JSON captured from the
 * backend in E6c (frontend/src/test/fixtures/loadbalancing, never edited). A backend rename or
 * type change breaks this test as well as the backend's own LoadBalancingControllerTest.
 */

const isString = (value) => typeof value === 'string'
const isNumber = (value) => typeof value === 'number' && Number.isFinite(value)
const isBoolean = (value) => typeof value === 'boolean'
const isStringOrNull = (value) => value === null || isString(value)
const isNumberOrNull = (value) => value === null || isNumber(value)
const isStrategy = (value) => STRATEGY_ORDER.includes(value)

function expectShape(object, shape, where) {
  expect(object, `${where} is an object`).toBeTypeOf('object')
  for (const [field, check] of Object.entries(shape)) {
    expect(object, `${where} has ${field}`).toHaveProperty(field)
    expect(check(object[field]), `${where}.${field} = ${JSON.stringify(object[field])}`).toBe(true)
  }
}

const OVERVIEW = {
  status: isString,
  actionInProgress: isStringOrNull,
  defaults: (v) => isNumber(v?.requestCount) && isNumber(v?.workUnits) && isNumber(v?.concurrency),
  limits: (v) => ['maxRequestCount', 'maxWorkUnits', 'maxConcurrency', 'maxTotalWork'].every((k) => isNumber(v?.[k])),
  capacityNote: isString,
  workUnitsNote: isString,
  deliveryNote: isString,
  warmUpNote: isString,
  crashNote: isString,
  strategies: Array.isArray,
  workers: Array.isArray,
}
const STRATEGY = { strategy: isStrategy, description: isString, informationUsed: isString }
const WORKER = {
  nodeId: isNumber,
  nodeStatus: isString,
  capacity: isString,
  threads: isNumber,
  workMultiplier: isNumber,
  healthy: isBoolean,
  inFlight: isNumber,
  completed: isNumber,
  failed: isNumber,
  declined: isNumber,
  averageLatencyMillis: isNumberOrNull,
}
const RUN = {
  runId: isString,
  state: isString,
  strategy: isStrategy,
  requestCount: isNumber,
  crash: (v) => v === null || (isNumber(v.nodeId) && isNumber(v.afterServed) && isBoolean(v.crashed)),
  startedAt: isString,
  error: isStringOrNull,
}
const REPORT = {
  strategy: isStrategy,
  makespanMillis: isNumber,
  averageLatencyMillis: isNumberOrNull,
  p95LatencyMillis: isNumberOrNull,
  maxLatencyMillis: isNumberOrNull,
  reroutes: isNumber,
  failures: isNumber,
  loadSpread: isNumber,
  nodes: Array.isArray,
}
const NODE_RESULT = {
  nodeId: isNumber,
  capacity: isString,
  requests: isNumber,
  averageLatencyMillis: isNumberOrNull,
  failedAttempts: isNumber,
  declinedAttempts: isNumber,
  healthy: isBoolean,
}
const COMPARISON = {
  comparisonId: isString,
  state: isString,
  requestCount: isNumber,
  startedAt: isString,
  phases: Array.isArray,
  error: isStringOrNull,
}
const FINDING = {
  fastest: isStrategy,
  slowest: isStrategy,
  roundRobinFinishedLast: isBoolean,
  roundRobinMostEven: isBoolean,
  gainOverRoundRobinPercent: isNumberOrNull,
}

function expectReport(report, where) {
  expectShape(report, REPORT, where)
  report.nodes.forEach((node, i) => expectShape(node, NODE_RESULT, `${where}.nodes[${i}]`))
}

function expectOverview(overview, where) {
  expectShape(overview, OVERVIEW, where)
  overview.strategies.forEach((s, i) => expectShape(s, STRATEGY, `${where}.strategies[${i}]`))
  overview.workers.forEach((w, i) => expectShape(w, WORKER, `${where}.workers[${i}]`))
  if (overview.latestRun) {
    expectShape(overview.latestRun, RUN, `${where}.latestRun`)
    if (overview.latestRun.report) expectReport(overview.latestRun.report, `${where}.latestRun.report`)
  }
  if (overview.latestComparison) {
    expectShape(overview.latestComparison, COMPARISON, `${where}.latestComparison`)
    overview.latestComparison.phases.forEach((p, i) => expectReport(p, `${where}.latestComparison.phases[${i}]`))
    if (overview.latestComparison.finding) {
      expectShape(overview.latestComparison.finding, FINDING, `${where}.latestComparison.finding`)
    }
  }
}

describe('load balancing contract (real E6c fixtures)', () => {
  it('the overview before any run: settings, texts, strategies, five workers, no results', () => {
    expectOverview(overviewBefore, 'overview-before')
    expect(overviewBefore.strategies.map((s) => s.strategy)).toEqual(STRATEGY_ORDER)
    expect(overviewBefore.workers).toHaveLength(5)
    expect(overviewBefore.latestRun).toBeNull()
    expect(overviewBefore.latestComparison).toBeNull()
  })

  it('the overview after a run, after a comparison and after a crash run', () => {
    expectOverview(overviewAfterRun, 'overview-after-run')
    expect(overviewAfterRun.latestRun.state).toBe('FINISHED')
    expectOverview(overviewAfterComparison, 'overview-after-comparison')
    expect(overviewAfterComparison.latestComparison.phases.map((p) => p.strategy)).toEqual(STRATEGY_ORDER)
    expect(overviewAfterComparison.latestComparison.finding).not.toBeNull()
    expectOverview(overviewAfterCrashRun, 'overview-after-crash-run')
    expect(overviewAfterCrashRun.latestRun.crash.crashed).toBe(true)
  })

  it('the 202 bodies: a run, a crash run and a comparison, all RUNNING without results', () => {
    expectShape(runAccepted, RUN, 'run-accepted')
    expect(runAccepted.state).toBe('RUNNING')
    expect(runAccepted.report).toBeNull()
    expectShape(crashRunAccepted, RUN, 'crash-run-accepted')
    expect(crashRunAccepted.crash).toEqual({ nodeId: 3, afterServed: 20, crashed: false })
    expectShape(comparisonAccepted, COMPARISON, 'comparison-accepted')
    expect(comparisonAccepted.phases).toEqual([])
    expect(comparisonAccepted.finding).toBeNull()
  })

  it('the error bodies the page shows: title, detail, and the field errors or ids', () => {
    expectShape(validationFixture, { status: isNumber, title: isString, detail: isString,
      errors: (v) => isString(v?.requestCount) }, 'error-400')
    expectShape(notFoundFixture, { status: isNumber, title: isString, detail: isString, nodeId: isNumber }, 'error-404')
    expectShape(nodeDownFixture, { status: isNumber, title: isString, detail: isString, nodeId: isNumber }, 'error-409-node-down')
    expectShape(busyFixture, { status: isNumber, title: isString, detail: isString, moduleId: isString,
      actionInProgress: isString }, 'error-409-busy')
  })

  it('every module event has a Lamport time and a runId or comparisonId', () => {
    expect(eventsFixture.length).toBeGreaterThan(0)
    for (const event of eventsFixture) {
      expectShape(event, { sequence: isNumber, type: isString, lamportTime: isNumber, nodeId: isNumber,
        module: (v) => v === 'loadbalancing' }, `event ${event.sequence}`)
      expect(isString(event.data?.runId) || isString(event.data?.comparisonId), `event ${event.sequence} id`).toBe(true)
    }
  })
})
