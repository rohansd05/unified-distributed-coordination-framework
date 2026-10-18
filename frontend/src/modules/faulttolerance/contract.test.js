import { describe, expect, it } from 'vitest'
import { MILESTONE_EVENTS, MILESTONE_KINDS, NOTE } from './faultToleranceModel'

// Real captures (E8c), never modified, and the labelled synthetic copies (E8d), checked against one contract.
const REAL = import.meta.glob('/src/test/fixtures/faulttolerance/*.json', { eager: true, import: 'default' })
const SYNTHETIC = import.meta.glob('/src/test/fixtures/faulttolerance/synthetic/*.json', { eager: true, import: 'default' })
const name = (path) => path.split('/').pop()

const STATUS_FIELDS = ['status', 'actionInProgress', 'started', 'phase', 'currentPrimaryId', 'primaryEpoch', 'highestEpoch', 'nodes',
  'stream', 'splitBrain', 'dataLossNow', 'acknowledgedUpdates', 'rejectedEventCount']
const NODE_FIELDS = ['nodeId', 'nodeStatus', 'role', 'serving', 'epoch', 'believedPrimaryId', 'rejoin']
const STREAM_FIELDS = ['state', 'mode', 'ratePerSecond', 'sent', 'acknowledged', 'gaveUp', 'inFlightSequence', 'maxUpdates',
  'remainingUpdates', 'startedAt', 'stoppedAt', 'stopReason']
const SPLIT_BRAIN_FIELDS = ['passed', 'highestEpoch', 'livePrimaries', 'violations', 'violationsSeen']
const DATA_LOSS_FIELDS = ['acknowledged', 'lost', 'lostKeys', 'lostSynchronous', 'lostAsynchronous', 'simulated', 'simulatedDelayMillis',
  'simulatedReason', 'notAssessed']
const RUN_FIELDS = ['runId', 'oldPrimaryId', 'oldEpoch', 'crashSource', 'detectedByNodeId', 'newPrimaryId', 'newEpoch', 'outcome',
  'milestones', 'measurements', 'dataLoss']
const MEASUREMENT_FIELDS = ['detectionMillis', 'failoverMillis', 'serviceRestoredMillis', 'outageMillis', 'recoveryMillis']
const SETTINGS_FIELDS = ['defaultRatePerSecond', 'maxRatePerSecond', 'maxUpdates', 'historyLimit', 'heartbeatIntervalMillis',
  'heartbeatTimeoutMillis', 'retryWindowMillis', 'worstCaseFailoverMillis', 'asyncDelayMillis', 'asyncDelaySimulated',
  'asyncDelayReason', 'coHostingSimulated', 'coHostingReason']
const GLOSSARY_IDS = ['detection', 'failover', 'serviceRestored', 'outage', 'recovery', 'dataLoss', 'expectedDetection', 'worstCaseFailover']
const REASONS = ['NO_SERVING_PRIMARY', 'NOTHING_TO_RECOVER', 'STREAM_RUNNING', 'STREAM_NOT_RUNNING', 'UPDATE_LIMIT_REACHED']

/** Every status, run and data loss object inside a fixture, wherever it sits. */
function collect(fixtures) {
  const statuses = []
  const runs = []
  const overviews = []
  for (const [path, body] of Object.entries(fixtures)) {
    const file = name(path)
    if (Array.isArray(body)) {
      if (body[0]?.runId !== undefined) runs.push(...body.map((run) => [file, run]))
      continue
    }
    if (body.current) {
      overviews.push([file, body])
      statuses.push([file, body.current])
      if (body.latestRun) runs.push([file, body.latestRun])
    } else if (body.nodes) {
      statuses.push([file, body])
    } else if (body.milestones) {
      runs.push([file, body])
    } else if (body.status && typeof body.status === 'object') {
      statuses.push([file, body.status])
    }
  }
  return { statuses, runs, overviews }
}

const keys = (value) => Object.keys(value).sort()

describe('faulttolerance contract fixtures', () => {
  const real = collect(REAL)
  const synthetic = collect(SYNTHETIC)

  it('has the 38 real JSON captures of the E8c README (plus events-export.txt)', () => {
    expect(Object.keys(REAL)).toHaveLength(38)
  })

  it.each([...real.statuses, ...synthetic.statuses])('%s: status has every field the page reads', (_, status) => {
    expect(keys(status)).toEqual([...STATUS_FIELDS].sort())
    status.nodes.forEach((node) => expect(keys(node)).toEqual([...NODE_FIELDS].sort()))
    expect(keys(status.stream)).toEqual([...STREAM_FIELDS].sort())
    expect(keys(status.splitBrain)).toEqual([...SPLIT_BRAIN_FIELDS].sort())
    if (status.dataLossNow) expect(keys(status.dataLossNow)).toEqual([...DATA_LOSS_FIELDS].sort())
    expect(typeof status.stream.maxUpdates).toBe('number')
    expect(typeof status.stream.remainingUpdates).toBe('number')
  })

  it.each([...real.statuses, ...synthetic.statuses])('%s: a down node has null role, epoch and rejoin, never 0', (_, status) => {
    for (const node of status.nodes.filter((n) => n.nodeStatus !== 'UP')) {
      expect([node.role, node.epoch, node.believedPrimaryId, node.rejoin]).toEqual([null, null, null, null])
    }
  })

  it.each([...real.runs, ...synthetic.runs])('%s: run has every field, known milestone kinds in time order', (_, run) => {
    expect(keys(run)).toEqual([...RUN_FIELDS].sort())
    expect(keys(run.measurements)).toEqual([...MEASUREMENT_FIELDS].sort())
    if (run.dataLoss) expect(keys(run.dataLoss)).toEqual([...DATA_LOSS_FIELDS].sort())
    const order = run.milestones.map((m) => MILESTONE_KINDS.indexOf(m.kind))
    expect(order.every((index) => index >= 0)).toBe(true)
    expect([...order].sort((a, b) => a - b)).toEqual(order)
    const offsets = run.milestones.map((m) => m.offsetMillis)
    expect([...offsets].sort((a, b) => a - b)).toEqual(offsets)
  })

  it.each(real.overviews)('%s: settings, the eight glossary ids and the seven notes', (_, overview) => {
    expect(keys(overview.settings)).toEqual([...SETTINGS_FIELDS].sort())
    expect(overview.glossary.map((g) => g.id)).toEqual(GLOSSARY_IDS)
    overview.glossary.forEach((g) => expect(['measured', 'derived']).toContain(g.kind))
    expect(overview.notes).toHaveLength(7)
    expect(overview.notes.join(' ')).not.toMatch(/Until Phase 9A/)   // note 8 is obsolete since 9A.3
    expect(overview.notes[NOTE.epochFence]).toMatch(/^Backups refuse any update stamped with an older epoch/)
    expect(overview.notes[NOTE.asyncWindow]).toMatch(/^In asynchronous mode/)
  })

  it('names only the 409 reasons the page has a message for', () => {
    const reasons = Object.values(REAL).filter((body) => body?.status === 409 && body.reason).map((body) => body.reason)
    expect(reasons.length).toBeGreaterThan(0)
    reasons.forEach((reason) => expect(REASONS).toContain(reason))
  })

  it('has, in the real event logs, every event a timeline row names', () => {
    const types = new Set([
      ...REAL['/src/test/fixtures/faulttolerance/events.json'],
      ...REAL['/src/test/fixtures/faulttolerance/events-async.json'],
    ].map((event) => event.type))
    Object.values(MILESTONE_EVENTS).forEach((type) => expect(types).toContain(type))
  })
})
