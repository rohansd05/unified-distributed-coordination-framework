import { describe, expect, it } from 'vitest'
import overviewBefore from '@/test/fixtures/loadbalancing/overview-before.json'
import overviewAfterRun from '@/test/fixtures/loadbalancing/overview-after-run.json'
import overviewAfterComparison from '@/test/fixtures/loadbalancing/overview-after-comparison.json'
import overviewAfterCrashRun from '@/test/fixtures/loadbalancing/overview-after-crash-run.json'
import { flowModel, flowSummary, latestAction, num, selectedPhase } from './flowModel'

/** A deep copy, so a test can change one field without touching the imported fixture. */
const copy = (value) => JSON.parse(JSON.stringify(value))

const requests = (model) => model.lanes.map((lane) => lane.requests)

describe('flowModel', () => {
  it('num keeps finite numbers only', () => {
    expect([num(3), num(0), num(null), num(undefined), num(Number.NaN), num('5'), num(Infinity)])
      .toEqual([3, 0, null, null, null, null, null])
  })

  it('no overview: empty, no lanes, no numbers', () => {
    const model = flowModel(null)

    expect(model.mode).toBe('empty')
    expect(model.lanes).toEqual([])
    expect(model.answered).toBeNull()
    expect(flowSummary(model)).toBe('Nothing has run yet. Run a strategy to see where the requests go.')
  })

  it('before any run: one lane per worker, without counts or shares', () => {
    const model = flowModel(overviewBefore)

    expect(model.mode).toBe('empty')
    expect(model.lanes).toHaveLength(5)
    expect(model.lanes.every((lane) => lane.requests === null && lane.share === null && lane.averageLatencyMillis === null))
      .toBe(true)
  })

  it('after a run: its report, with shares and measured latencies', () => {
    const model = flowModel(overviewAfterRun)

    expect(model.mode).toBe('run')
    expect(model.strategy).toBe('ROUND_ROBIN')
    expect(requests(model)).toEqual([12, 12, 12, 12, 12])
    expect(model.lanes.map((lane) => lane.share)).toEqual([0.2, 0.2, 0.2, 0.2, 0.2])
    expect(model.lanes[0].threads).toBe(4)
    expect(model.lanes.every((lane) => typeof lane.averageLatencyMillis === 'number')).toBe(true)
    expect(flowSummary(model)).toBe('Round robin: 60 requests served, 0 failed.')
  })

  it('after a crash run: the crashed node is marked, with its refused attempts', () => {
    const model = flowModel(overviewAfterCrashRun)
    const node3 = model.lanes.find((lane) => lane.nodeId === 3)

    expect(model.mode).toBe('run')
    expect(node3.crashed).toBe(true)
    expect(node3.healthy).toBe(false)
    expect(node3.failedAttempts).toBe(6)
    expect(model.answered).toBe(60)
  })

  it('after a comparison: the first phase by default, any phase on request', () => {
    const comparison = overviewAfterComparison.latestComparison

    expect(latestAction(overviewAfterComparison).kind).toBe('comparison')
    expect(flowModel(overviewAfterComparison).strategy).toBe('ROUND_ROBIN')
    const lc = flowModel(overviewAfterComparison, 'LEAST_CONNECTIONS')
    expect(lc.mode).toBe('phase')
    expect(requests(lc)).toEqual(selectedPhase(comparison, 'LEAST_CONNECTIONS').nodes.map((n) => n.requests))
    expect(lc.phases).toHaveLength(4)
  })

  it('the most recent action wins: a run after a comparison is shown, not the comparison', () => {
    expect(latestAction(overviewAfterCrashRun).kind).toBe('run')
    expect(latestAction({ latestRun: null, latestComparison: null }).kind).toBeNull()
  })

  it('a running run: the live worker counters, with requests in flight', () => {
    const live = copy(overviewAfterRun)
    live.status = 'BUSY'
    live.latestRun.state = 'RUNNING'
    live.latestRun.report = null
    live.workers[0].inFlight = 3
    live.workers[0].completed = 5

    const model = flowModel(live)

    expect(model.mode).toBe('live')
    expect(model.lanes[0].inFlight).toBe(3)
    expect(model.lanes[0].requests).toBe(5)
    expect(flowSummary(model)).toMatch(/^Round robin run in progress: \d+ of 60 answered so far, 3 in flight\.$/)
  })

  it('a running comparison with only some phases: live counters, and no phase picker yet', () => {
    const running = copy(overviewAfterComparison)
    running.status = 'BUSY'
    running.latestComparison.state = 'RUNNING'
    running.latestComparison.phases = running.latestComparison.phases.slice(0, 2)
    running.latestComparison.finding = null

    const model = flowModel(running)

    expect(model.mode).toBe('live')
    expect(model.kind).toBe('comparison')
    expect(flowSummary(model)).toMatch(/^Comparison in progress/)
  })

  it('a failed run: the error text and the counters it reached, no report', () => {
    const failed = copy(overviewAfterRun)
    failed.latestRun.state = 'FAILED'
    failed.latestRun.report = null
    failed.latestRun.error = 'broke after ten'

    const model = flowModel(failed)

    expect(model.mode).toBe('failed')
    expect(model.report).toBeNull()
    expect(model.error).toBe('broke after ten')
    expect(flowSummary(model)).toBe('Run failed: broke after ten')
  })

  it('null latencies and an empty worker list do not throw and never become 0', () => {
    const odd = copy(overviewAfterRun)
    odd.workers = []
    odd.latestRun.report.nodes.forEach((node) => { node.averageLatencyMillis = null })

    const model = flowModel(odd)

    expect(model.lanes).toHaveLength(5)
    expect(model.lanes.every((lane) => lane.averageLatencyMillis === null)).toBe(true)
    expect(model.lanes[0].threads).toBeNull()
  })
})
