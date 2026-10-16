import { describe, expect, it } from 'vitest'
import { ApiError } from '@/services/api'
import overviewBefore from '@/test/fixtures/matrix/overview-before-start.json'
import overviewAfter from '@/test/fixtures/matrix/overview-after-start.json'
import overviewCrashed from '@/test/fixtures/matrix/overview-node-crashed.json'
import startFixture from '@/test/fixtures/matrix/start.json'
import startWithFailure from '@/test/fixtures/matrix/start-with-failure.json'
import complete50 from '@/test/fixtures/matrix/run-complete-50.json'
import complete100 from '@/test/fixtures/matrix/run-complete-100.json'
import complete200 from '@/test/fixtures/matrix/run-complete-200.json'
import complete316 from '@/test/fixtures/matrix/run-complete-316.json'
import partial from '@/test/fixtures/matrix/run-partial.json'
import failed from '@/test/fixtures/matrix/run-failed.json'
import skipped from '@/test/fixtures/matrix/run-node-crashed-skipped.json'
import requestedRoot from '@/test/fixtures/matrix/run-requested-root.json'
import refused from '@/test/fixtures/matrix/run-refused.json'
import latest from '@/test/fixtures/matrix/run-latest.json'
import byId from '@/test/fixtures/matrix/run-by-id.json'
import runsHistory from '@/test/fixtures/matrix/runs-history.json'
import series from '@/test/fixtures/matrix/series.json'
import seriesBefore from '@/test/fixtures/matrix/series-before-runs.json'
import seriesMore from '@/test/fixtures/matrix/series-after-more-runs.json'
import missingFields from '@/test/fixtures/matrix/error-400-missing-fields.json'
import aboveMax from '@/test/fixtures/matrix/error-400-size-above-max.json'
import belowMin from '@/test/fixtures/matrix/error-400-size-below-min.json'
import seedError from '@/test/fixtures/matrix/error-400-seed.json'
import unreadable from '@/test/fixtures/matrix/error-400-unreadable.json'
import badId from '@/test/fixtures/matrix/error-400-bad-id.json'
import noRunYet from '@/test/fixtures/matrix/error-404-no-run-yet.json'
import unknownNode from '@/test/fixtures/matrix/error-404-unknown-node.json'
import unknownRun from '@/test/fixtures/matrix/error-404-unknown-run.json'
import busy from '@/test/fixtures/matrix/error-409-busy.json'
import rootDown from '@/test/fixtures/matrix/error-409-root-down.json'
import noLiveNode from '@/test/fixtures/matrix/error-409-no-live-node.json'
import synthMismatch from '@/test/fixtures/matrix/synthetic/run-checksum-mismatch.json'
import synthEvicted from '@/test/fixtures/matrix/synthetic/run-evicted-block.json'
import synthTimeouts from '@/test/fixtures/matrix/synthetic/run-timeouts.json'
import synthLate from '@/test/fixtures/matrix/synthetic/run-not-attempted-deadline.json'
import synthLeader from '@/test/fixtures/matrix/synthetic/overview-leader.json'
import { describeProblem } from './matrixApi'
import { figureRows } from './matrixModel'

const OVERVIEWS = { overviewBefore, overviewAfter, overviewCrashed, startFixture, startWithFailure }
const RUNS = { complete50, complete100, complete200, complete316, partial, failed, skipped, requestedRoot, refused, latest, byId }
const SERIES = { series, seriesBefore, seriesMore }

/** The texts the page shows from the overview as served, never retyped. */
const OVERVIEW_TEXTS = ['algorithmNote', 'phaseTimeLabel', 'payloadGenerationNote', 'firstRunNote', 'experimentTwoSideEffect',
  'collectiveIdsNote', 'computeRoundTripBasis', 'gatherLatencyBasis', 'seriesRule']

const OVERVIEW_KEYS = ['actionInProgress', 'algorithmNote', 'boundName', 'coHosting', 'collectiveIdsNote', 'collectiveLatencyBasis',
  'computeRoundTripBasis', 'defaultRootNodeId', 'defaultRootSource', 'experimentTwoSideEffect', 'fairnessNote', 'firstRunNote',
  'gatherLatencyBasis', 'glossary', 'latest', 'limits', 'minimumSizeNow', 'otherActivityNote', 'participants', 'payloadGenerationNote',
  'payloadSizeBasis', 'phaseTimeLabel', 'processors', 'seriesRule', 'speedupBasis', 'speedupCeiling', 'status']
const LIMIT_KEYS = ['callTimeoutMillis', 'computeTimeoutMillis', 'computeWaitMillis', 'connectTimeoutMillis', 'historySize', 'maxSeed',
  'maxSize', 'minSize', 'productAllowanceMillis', 'recommendedMinClientTimeoutMillis', 'resultStoreSize', 'runDeadlineMillis',
  'seriesSizes', 'transferWorstMillis', 'warmupProducts', 'worstCaseRunMillis']
const NODE_KEYS = ['keptRunIds', 'leader', 'matrixEndpointExported', 'nodeId', 'nodeStatus', 'registryPort', 'requestListenerRunning',
  'requestsPort', 'rmiState', 'startFailure', 'storedCollectiveIds']
const RUN_KEYS = ['broadcast', 'computeRoundTripBasis', 'computes', 'deadlineMillis', 'gather', 'gatherLatencyBasis', 'inputsDelivered',
  'outcome', 'payloadSizeBasis', 'rankNodeIds', 'refusal', 'rootNodeId', 'rootRank', 'rootSource', 'runId', 'scatter', 'seed',
  'size', 'skippedNodeIds', 'speedupBasis', 'startedAt', 'timing', 'verification']
const COMPUTE_KEYS = ['checksum', 'computeNanos', 'failureCauseClass', 'failureClass', 'failureDescription', 'failureKind',
  'failureMessage', 'failureStage', 'lamportAfterReply', 'local', 'nodeId', 'nodeMessage', 'nodeReason', 'notAttemptedReason', 'rank',
  'replyLamport', 'roundTripNanos', 'rowCount', 'rowStart', 'senderLamportAtSend', 'status', 'timeToFailureNanos']
const PHASE_KEYS = ['collectiveId', 'deadlineMillis', 'inputChecksum', 'latencyBasis', 'operation', 'outcome', 'payloadSizeBasis',
  'refusal', 'totalNanos', 'transfers', 'verification']
const MEASURED_KEYS = ['broadcastNanos', 'computePhaseNanos', 'gatherNanos', 'parallelTotalNanos', 'payloadGenerationNanos',
  'scatterNanos', 'sequentialNanos']
const DERIVED_KEYS = ['communicationNanos', 'communicationShare', 'computeBoundSpeedup', 'multiplyAdds', 'otherNanos', 'speedup',
  'speedupCeiling', 'speedupWithheldReason']
const VERIFICATION_KEYS = ['blocksMissing', 'blocksWithBadChecksum', 'checksumsMatch', 'elementsCompared', 'mismatchedElements',
  'parallelChecksum', 'passed', 'rowsExpected', 'rowsReceived', 'sequentialChecksum']
const SERIES_KEYS = ['basis', 'done', 'gaps', 'points', 'ranksDiffer', 'ranksNote', 'rule', 'seriesSizes', 'sizesRequired', 'sizesWithSpeedup']
const POINT_KEYS = ['communicationNanos', 'computePhaseNanos', 'parallelTotalNanos', 'ranks', 'sequentialNanos', 'size', 'sourceOutcome',
  'sourceRunId', 'speedup']

const keys = (object) => Object.keys(object).sort()

/** Every key path of a JSON value; an array contributes the paths of each element under "[]". */
function keyPaths(value, prefix = '') {
  if (Array.isArray(value)) return [...new Set(value.flatMap((item) => keyPaths(item, `${prefix}[]`)))].sort()
  if (value && typeof value === 'object') {
    return Object.entries(value).flatMap(([key, child]) => [`${prefix}.${key}`, ...keyPaths(child, `${prefix}.${key}`)]).sort()
  }
  return []
}

const problemFor = (body) => describeProblem(new ApiError({ status: body.status, title: body.title, detail: body.detail,
  nodeId: body.nodeId ?? null, errors: body.errors ?? null, originalError: { response: { status: body.status, data: body } } }))

describe('contract: the overview', () => {
  it.each(Object.entries(OVERVIEWS))('%s has exactly the fields the page reads', (_, overview) => {
    expect(keys(overview)).toEqual(OVERVIEW_KEYS)
    expect(keys(overview.limits)).toEqual(LIMIT_KEYS)
    overview.participants.forEach((node) => expect(keys(node)).toEqual(NODE_KEYS))
  })

  it('serves every text the page shows as it is, and the same text on every overview', () => {
    for (const name of OVERVIEW_TEXTS) {
      expect(typeof overviewAfter[name]).toBe('string')
      expect(overviewAfter[name].length).toBeGreaterThan(0)
      Object.values(OVERVIEWS).forEach((overview) => expect(overview[name]).toBe(overviewAfter[name]))
    }
    expect(overviewAfter.coHosting.simulated).toBe(true)
    expect(overviewAfter.coHosting.reason.length).toBeGreaterThan(0)
  })

  it('has a glossary entry, with a known kind, for every figure the page labels', () => {
    const ids = [...figureRows(complete316, overviewAfter.glossary).map((row) => row.id), 'speedup', 'computeBoundSpeedup',
      'roundTripNanos', 'computeNanos', 'latencyNanos', 'timeToFailureNanos']
    const glossary = Object.fromEntries(overviewAfter.glossary.map((entry) => [entry.id, entry]))
    ids.forEach((id) => expect(glossary[id], id).toBeTruthy())
    overviewAfter.glossary.forEach((entry) => {
      expect(['MEASURED_ON_ROOT', 'MEASURED_ON_NODE', 'DERIVED']).toContain(entry.kind)
      expect(keys(entry)).toEqual(['definition', 'id', 'kind', 'label'])
    })
  })

  it('serves the client bound as the derived worst case (the page adds only its margin)', () => {
    Object.values(OVERVIEWS).forEach((overview) => {
      expect(overview.limits.recommendedMinClientTimeoutMillis).toBe(overview.limits.worstCaseRunMillis)
      expect(overview.limits.seriesSizes.length).toBeGreaterThan(0)
    })
  })

  it('has no speedup basis or ceiling only when nothing is live; minimumSizeNow follows the live nodes', () => {
    expect(overviewCrashed.minimumSizeNow).toBe(overviewCrashed.participants.filter((n) => n.nodeStatus === 'UP').length)
    expect(typeof overviewAfter.speedupBasis).toBe('string')
  })
})

describe('contract: runs', () => {
  it.each(Object.entries(RUNS))('%s has exactly the run fields the page reads', (_, run) => {
    expect(keys(run)).toEqual(RUN_KEYS)
    run.computes.forEach((compute) => expect(keys(compute)).toEqual(COMPUTE_KEYS))
    if (run.outcome === 'REFUSED') return
    expect(keys(run.scatter)).toEqual(PHASE_KEYS)
    expect(keys(run.broadcast)).toEqual(PHASE_KEYS)
    expect(keys(run.timing.measured)).toEqual(MEASURED_KEYS)
    expect(keys(run.timing.derived)).toEqual(DERIVED_KEYS)
    expect(keys(run.verification)).toEqual(VERIFICATION_KEYS)
  })

  it('has a speedup exactly when the run completed and matched, and a withheld reason otherwise', () => {
    Object.values(RUNS).filter((run) => run.timing).forEach((run) => {
      const counted = run.outcome === 'COMPLETE' && run.verification.passed === true
      expect(typeof run.timing.derived.speedup === 'number').toBe(counted)
      expect(typeof run.timing.derived.speedupWithheldReason === 'string').toBe(!counted)
    })
  })

  it('has nothing measured for a refused run, only its reason and message', () => {
    expect(refused).toMatchObject({ scatter: null, broadcast: null, computes: [], gather: [], timing: null, verification: null })
    expect(refused.refusal.message.length).toBeGreaterThan(0)
  })

  it('lists the history newest first, with whole runs', () => {
    expect(runsHistory.map((r) => r.runId)).toEqual([...runsHistory.map((r) => r.runId)].sort((a, b) => b - a))
    runsHistory.forEach((run) => expect(keys(run)).toEqual(RUN_KEYS))
  })
})

describe('contract: the series', () => {
  it.each(Object.entries(SERIES))('%s has exactly the fields the chart reads, and the served rule', (_, value) => {
    expect(keys(value)).toEqual(SERIES_KEYS)
    value.points.forEach((point) => expect(keys(point)).toEqual(POINT_KEYS))
    value.gaps.forEach((gap) => expect(keys(gap)).toEqual(['newestOutcome', 'newestRunId', 'size']))
    expect(value.rule).toBe(overviewAfter.seriesRule)
    expect(value.seriesSizes).toEqual(overviewAfter.limits.seriesSizes)
  })

  it('serves the ranks note only when the points differ in node count', () => {
    expect(seriesMore.ranksDiffer).toBe(true)
    expect(typeof seriesMore.ranksNote).toBe('string')
    expect(series.ranksNote).toBeNull()
  })
})

describe('contract: errors', () => {
  it('maps every error fixture to the kind the page handles', () => {
    expect([missingFields, aboveMax, belowMin, seedError].map((e) => problemFor(e).kind)).toEqual(Array(4).fill('validation'))
    expect([unreadable, badId].map((e) => problemFor(e).kind)).toEqual(['bad-request', 'bad-request'])
    expect([noRunYet, unknownNode, unknownRun].map((e) => problemFor(e).kind)).toEqual(['not-found', 'not-found', 'not-found'])
    expect([busy, rootDown, noLiveNode].map((e) => problemFor(e).kind)).toEqual(['busy', 'node-down', 'state-conflict'])
    expect(noLiveNode.nodeId).toBe(0)
  })
})

describe('contract: the synthetic fixtures keep the real shape', () => {
  it.each([
    ['run-checksum-mismatch', synthMismatch, complete100],
    ['run-evicted-block', synthEvicted, complete100],
    ['run-timeouts', synthTimeouts, complete100],
    ['run-not-attempted-deadline', synthLate, complete100],
    ['overview-leader', synthLeader, overviewAfter],
  ])('synthetic %s has exactly the key paths of its real source', (_, synthetic, source) => {
    expect(keyPaths(synthetic)).toEqual(keyPaths(source))
  })
})
