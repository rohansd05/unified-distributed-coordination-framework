import { describe, expect, it } from 'vitest'
import overviewBefore from '@/test/fixtures/matrix/overview-before-start.json'
import overviewAfter from '@/test/fixtures/matrix/overview-after-start.json'
import overviewCrashed from '@/test/fixtures/matrix/overview-node-crashed.json'
import startWithFailure from '@/test/fixtures/matrix/start-with-failure.json'
import complete316 from '@/test/fixtures/matrix/run-complete-316.json'
import complete100 from '@/test/fixtures/matrix/run-complete-100.json'
import partial from '@/test/fixtures/matrix/run-partial.json'
import failed from '@/test/fixtures/matrix/run-failed.json'
import skipped from '@/test/fixtures/matrix/run-node-crashed-skipped.json'
import requestedRoot from '@/test/fixtures/matrix/run-requested-root.json'
import refused from '@/test/fixtures/matrix/run-refused.json'
import runsHistory from '@/test/fixtures/matrix/runs-history.json'
import series from '@/test/fixtures/matrix/series.json'
import seriesBefore from '@/test/fixtures/matrix/series-before-runs.json'
import seriesMore from '@/test/fixtures/matrix/series-after-more-runs.json'
import evicted from '@/test/fixtures/matrix/synthetic/run-evicted-block.json'
import timeouts from '@/test/fixtures/matrix/synthetic/run-timeouts.json'
import late from '@/test/fixtures/matrix/synthetic/run-not-attempted-deadline.json'
import {
  allNodesDown,
  blockRows,
  figureRows,
  formatShare,
  formatSpeedup,
  gatherAsTransfers,
  historyRows,
  kindLabel,
  mapOverviewForMap,
  needsStart,
  noticeModel,
  phaseAsCollective,
  runBody,
  seriesModel,
  stackModel,
  validateRun,
} from './matrixModel'

const copy = (value) => JSON.parse(JSON.stringify(value))

describe('needsStart and allNodesDown', () => {
  it('needs a start before start and after a failed export, but not when every up node exports or a node is only crashed', () => {
    expect(needsStart(overviewBefore)).toBe(true)
    expect(needsStart(startWithFailure)).toBe(true)
    expect(needsStart(overviewAfter)).toBe(false)
    expect(needsStart(overviewCrashed)).toBe(false)
    expect(needsStart(null)).toBe(false)
  })

  it('says every node is down only when every listed node is crashed', () => {
    const down = copy(overviewCrashed)
    down.participants.forEach((node) => { node.nodeStatus = 'CRASHED' })
    expect(allNodesDown(down)).toBe(true)
    expect(allNodesDown(overviewCrashed)).toBe(false)
    expect(allNodesDown({ participants: [] })).toBe(false)
  })
})

describe('runBody and validateRun', () => {
  it('sends the size, and the root only when chosen; never a seed', () => {
    expect(runBody({ size: '100', rootNode: '' })).toEqual({ size: 100 })
    expect(runBody({ size: '100', rootNode: '3' })).toEqual({ size: 100, rootNode: 3 })
  })

  it('checks the size against the served minSize and maxSize, in the backend\'s words', () => {
    expect(validateRun({ size: '' }, overviewAfter)).toEqual({ size: 'size is required' })
    expect(validateRun({ size: '317' }, overviewAfter)).toEqual({ size: 'size must be 2-316, was 317' })
    expect(validateRun({ size: '1' }, overviewAfter)).toEqual({ size: 'size must be 2-316, was 1' })
    expect(validateRun({ size: '2.5' }, overviewAfter)).toEqual({ size: 'size must be 2-316, was 2.5' })
    expect(validateRun({ size: '316' }, overviewAfter)).toEqual({})
  })

  it('lets a size below minimumSizeNow through: the backend refuses it and keeps the refusal', () => {
    expect(overviewAfter.minimumSizeNow).toBeGreaterThan(3)
    expect(validateRun({ size: '3' }, overviewAfter)).toEqual({})
  })

  it('reads the bounds from the overview, not from the page (synthetic limits)', () => {
    expect(validateRun({ size: '60' }, { limits: { minSize: 4, maxSize: 50 } })).toEqual({ size: 'size must be 4-50, was 60' })
  })
})

describe('adapters to the Experiment 9 components', () => {
  it('maps MatrixEndpoint to the map\'s endpoint flag and drops coHosting (one badge per page)', () => {
    const mapped = mapOverviewForMap(startWithFailure)
    expect(mapped.coHosting).toBeNull()
    expect(mapped.participants.map((n) => n.collectiveEndpointExported)).toEqual(startWithFailure.participants.map((n) => n.matrixEndpointExported))
    expect(mapOverviewForMap(null)).toBeNull()
  })

  it('gives a phase the id, start time, root and skipped nodes the CollectiveMap reads', () => {
    const scatter = phaseAsCollective(skipped, 'scatter')
    expect(scatter.id).toBe(skipped.scatter.collectiveId)
    expect(scatter.startedAt).toBe(skipped.startedAt)
    expect(scatter.rootNodeId).toBe(skipped.rootNodeId)
    expect(scatter.skippedNodeIds).toEqual([3])
    expect(scatter.transfers).toBe(skipped.scatter.transfers)
    expect(phaseAsCollective(refused, 'broadcast')).toBeNull()
  })

  it('passes the gather as a transfer list, or null when nothing was gathered', () => {
    expect(gatherAsTransfers(complete100).transfers).toBe(complete100.gather)
    expect(gatherAsTransfers(refused)).toBeNull()
  })
})

describe('blockRows', () => {
  it('gives every rank its row allocation, and a complete run has no hole', () => {
    const rows = blockRows(complete316)
    expect(rows.map((r) => [r.rank, r.nodeId, r.rowStart, r.rowCount])).toEqual(
      complete316.computes.map((c) => [c.rank, c.nodeId, c.rowStart, c.rowCount]))
    expect(rows.every((r) => r.arrived && r.hole === null)).toBe(true)
    expect(rows.filter((r) => r.root).map((r) => r.nodeId)).toEqual([complete316.rootNodeId])
  })

  it('marks the requested root, which is not rank 0', () => {
    expect(blockRows(requestedRoot).find((r) => r.root).rank).toBe(requestedRoot.rootRank)
  })

  it('keeps the partial run\'s missing block as a hole with the served reason', () => {
    const hole = blockRows(partial).find((r) => !r.arrived)
    expect(hole.rank).toBe(4)
    expect(hole.hole).toBe(`Compute not attempted. ${partial.computes[4].notAttemptedReason}`)
  })

  it('shows a failed run\'s remote rank as a hole and the root\'s own block as arrived', () => {
    const rows = blockRows(failed)
    expect(rows.map((r) => r.arrived)).toEqual([true, false])
  })

  it('synthetic: names the node\'s own reason (input missing) and a fetch that failed (block missing)', () => {
    const rows = blockRows(evicted)
    expect(rows[3].hole).toContain('Compute failed: Input missing.')
    expect(rows[4].hole).toContain('Fetch failed: Method threw.')
    expect(rows[4].hole).toContain('keeps no block of C')
  })

  it('synthetic: keeps the node\'s compute timeout apart from the root\'s wait bound', () => {
    const rows = blockRows(timeouts)
    expect(rows[1].hole).toContain('Compute failed: Compute timed out.')
    expect(rows[2].hole).toContain('Compute failed: Timed out.')
    expect(rows[2].hole).toContain('The root stopped waiting')
  })

  it('synthetic: shows steps not attempted after the deadline with the served reason', () => {
    const rows = blockRows(late)
    expect(rows[3].hole).toBe(`Fetch not attempted. ${late.gather[3].notAttemptedReason}`)
    expect(rows[4].hole).toBe(`Compute not attempted. ${late.computes[4].notAttemptedReason}`)
  })

  it('is empty for a refused run', () => {
    expect(blockRows(refused)).toEqual([])
  })
})

describe('figureRows and stackModel', () => {
  it('labels every figure from the served glossary and keeps every value as sent', () => {
    const rows = figureRows(complete316, overviewAfter.glossary)
    const byId = Object.fromEntries(rows.map((r) => [r.id, r]))
    expect(byId.sequentialNanos).toMatchObject({ label: 'Sequential time', kind: 'MEASURED_ON_ROOT', value: complete316.timing.measured.sequentialNanos })
    expect(byId.otherNanos).toMatchObject({ kind: 'DERIVED', value: complete316.timing.derived.otherNanos })
    expect(byId.multiplyAdds.value).toBe(complete316.timing.derived.multiplyAdds)
    expect(rows.every((r) => r.definition)).toBe(true)
  })

  it('keeps null as null (the failed run has no gather time) and a negative other time as it is', () => {
    expect(figureRows(failed, overviewAfter.glossary).find((r) => r.id === 'gatherNanos').value).toBeNull()
    const negative = copy(complete316)
    negative.timing.derived.otherNanos = -1200   // synthetic: other time can be negative
    expect(figureRows(negative, overviewAfter.glossary).find((r) => r.id === 'otherNanos').value).toBe(-1200)
  })

  it('stacks the four phases on the larger of the total and their sum, and hides the bar when a phase is null', () => {
    const model = stackModel(complete316)
    const m = complete316.timing.measured
    const sum = m.scatterNanos + m.broadcastNanos + m.computePhaseNanos + m.gatherNanos
    expect(model.segments.map((s) => s.nanos)).toEqual([m.scatterNanos, m.broadcastNanos, m.computePhaseNanos, m.gatherNanos])
    expect(model.segments[0].fraction).toBeCloseTo(m.scatterNanos / Math.max(m.parallelTotalNanos, sum))
    expect(model.otherNanos).toBe(complete316.timing.derived.otherNanos)
    expect(stackModel(failed)).toBeNull()
    expect(stackModel(refused)).toBeNull()
  })

  it('never lets the stack exceed the bar when other time is negative (synthetic sum above the total)', () => {
    const over = copy(complete316)
    over.timing.measured.parallelTotalNanos = 1000   // synthetic
    const fractions = stackModel(over).segments.map((s) => s.fraction)
    expect(fractions.reduce((a, b) => a + b, 0)).toBeCloseTo(1)
  })
})

describe('seriesModel', () => {
  it('has one point per configured size from the real series, with the served speedup and ranks', () => {
    const model = seriesModel(series, overviewAfter)
    expect(model.slots.map((s) => [s.size, s.kind])).toEqual(series.seriesSizes.map((size) => [size, 'point']))
    expect(model.slots.map((s) => s.speedup)).toEqual(series.points.map((p) => p.speedup))
    expect(model.ceiling).toBe(overviewAfter.speedupCeiling)
    expect(model.top).toBe(overviewAfter.speedupCeiling)
  })

  it('makes every size a gap (never 0) before any run', () => {
    const model = seriesModel(seriesBefore, overviewBefore)
    expect(model.slots.every((s) => s.kind === 'gap' && s.runId === null && s.speedup === undefined)).toBe(true)
  })

  it('carries the newest outcome of a gap (synthetic gap) and keeps the axis above every point', () => {
    const withGap = copy(seriesMore)
    withGap.points = withGap.points.filter((p) => p.size !== withGap.seriesSizes[1])
    withGap.gaps = [{ size: withGap.seriesSizes[1], newestRunId: 7, newestOutcome: 'PARTIAL' }]
    withGap.points[0].speedup = 9.5   // synthetic: above the ceiling
    const model = seriesModel(withGap, overviewAfter)
    expect(model.slots[1]).toMatchObject({ kind: 'gap', runId: 7, outcome: 'PARTIAL' })
    expect(model.top).toBe(9.5)
    expect(seriesModel(withGap, { speedupCeiling: null }).ceiling).toBeNull()
  })
})

describe('labels, history and notice', () => {
  it('formats speedups to three significant digits without rounding a small one to 0', () => {
    expect(formatSpeedup(series.points[0].speedup)).toBe('0.00682')
    expect(formatSpeedup(null)).toBeNull()
    expect(formatShare(0.7225496143899325)).toBe('72.3 %')
    expect(kindLabel('MEASURED_ON_NODE')).toBe('Measured on the node')
  })

  it('keys history rows by run id and start time, newest first as sent', () => {
    const rows = historyRows(runsHistory)
    expect(rows.map((r) => r.runId)).toEqual(runsHistory.map((r) => r.runId))
    expect(rows[0].key).toBe(`${runsHistory[0].runId}-${runsHistory[0].startedAt}`)
    expect(rows[0].parallelTotalNanos).toBeNull()   // the refused run
  })

  it('has two static callouts without figures, and a third only for the newest complete run', () => {
    const none = noticeModel([refused])
    expect(none).toHaveLength(2)
    expect(none.join(' ')).not.toMatch(/\d/)
    const callouts = noticeModel(runsHistory)
    const latest = runsHistory.find((r) => r.outcome === 'COMPLETE')
    expect(callouts).toHaveLength(3)
    expect(callouts[2]).toContain(`In this run (run ${latest.runId}, size ${latest.size})`)
    expect(callouts[2]).toContain(formatShare(latest.timing.derived.communicationShare))
  })
})
