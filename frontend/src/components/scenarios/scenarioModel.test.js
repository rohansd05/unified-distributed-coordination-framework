import { describe, expect, it } from 'vitest'
import {
  applyEvent, currentStep, factLabel, formatDuration, formatFact, moduleLabel, preferRun, progress, runDuration,
  skeletonRun, stepMessage, timelineHref,
} from './scenarioModel'

// Hand-built events in the 9A.6 shapes (docs/tracks/track-b-swanand.md, "9A.6 APIs"); not a capture.
const SCENARIOS = [{
  id: 'matrix-leader-crash',
  title: 'The leader dies during a matrix multiply',
  modules: ['election', 'rmi', 'mpi', 'matrix', 'not-a-module'],
  steps: [
    { index: 1, narration: 'We start the multiply.' },
    { index: 2, narration: 'We crash the leader.' },
    { index: 3, narration: 'We run it again.' },
  ],
}]

const ev = (sequence, type, data, message = null) =>
  ({ sequence, lamportTime: sequence, nodeId: 0, module: 'scenario', type, data, message, wallTime: '2026-10-19T10:00:00Z' })

const started = ev(10, 'SCENARIO_STARTED', { runId: 3, scenarioId: 'matrix-leader-crash', title: 'The leader dies during a matrix multiply', steps: 3 })

describe('applyEvent', () => {
  it('builds a skeleton on SCENARIO_STARTED and folds the step events of that run', () => {
    let run = applyEvent(null, started, SCENARIOS)
    expect(run).toMatchObject({ runId: 3, state: 'RUNNING' })
    expect(run.steps.map((s) => s.state)).toEqual(['PENDING', 'PENDING', 'PENDING'])

    run = applyEvent(run, ev(11, 'STEP_STARTED', { runId: 3, scenarioId: 'matrix-leader-crash', stepIndex: 1, narration: 'x' }), SCENARIOS)
    expect(run.steps[0].state).toBe('RUNNING')
    run = applyEvent(run, ev(12, 'STEP_PASSED', { runId: 3, stepIndex: 1, durationMs: 993, facts: { rootNodeId: 5 } },
      'Step 1 passed: Node 5 started run 1.'), SCENARIOS)
    expect(run.steps[0]).toMatchObject({ state: 'PASSED', durationMs: 993, facts: { rootNodeId: 5 }, message: 'Node 5 started run 1.' })
    run = applyEvent(run, ev(13, 'STEP_FAILED', { runId: 3, stepIndex: 2, durationMs: 30000, facts: {} }, 'Step 2 failed: Waited 30.0 s.'), SCENARIOS)
    run = applyEvent(run, ev(14, 'STEP_SKIPPED', { runId: 3, stepIndex: 3 }, 'Step 3 skipped: step 2 failed'), SCENARIOS)
    expect(run.steps.map((s) => s.state)).toEqual(['PASSED', 'FAILED', 'SKIPPED'])
    expect(run.steps[2].message).toBe('step 2 failed')
    run = applyEvent(run, ev(15, 'SCENARIO_FINISHED', { runId: 3, outcome: 'FAILED', verdict: 'Failed at step 2: Waited 30.0 s.' }), SCENARIOS)
    expect(run).toMatchObject({ state: 'FAILED', verdict: 'Failed at step 2: Waited 30.0 s.' })
  })

  it('ignores other modules, other runs, unknown types and unknown steps', () => {
    const run = applyEvent(null, started, SCENARIOS)
    expect(applyEvent(run, { ...ev(20, 'STEP_STARTED', { runId: 3, stepIndex: 1 }), module: 'matrix' }, SCENARIOS)).toBe(run)
    expect(applyEvent(run, ev(21, 'STEP_STARTED', { runId: 2, stepIndex: 1 }), SCENARIOS)).toBe(run)
    expect(applyEvent(run, ev(22, 'WIDGET_WOBBLED', { runId: 3 }), SCENARIOS)).toBe(run)
    expect(applyEvent(run, ev(23, 'STEP_STARTED', { runId: 3, stepIndex: 9 }), SCENARIOS)).toBe(run)
    expect(applyEvent(run, started, SCENARIOS)).toBe(run)
  })

  it('shows a run that failed before its first step from SCENARIO_FINISHED alone', () => {
    const run = applyEvent(null, ev(30, 'SCENARIO_FINISHED', { runId: 4, scenarioId: 'matrix-leader-crash', outcome: 'FAILED', verdict: 'Failed before the first step: busy.' }), SCENARIOS)
    expect(run).toMatchObject({ runId: 4, state: 'FAILED', verdict: 'Failed before the first step: busy.' })
  })
})

describe('preferRun and progress', () => {
  const skeleton = skeletonRun({ runId: 3, scenarioId: 'matrix-leader-crash' }, SCENARIOS)
  const ahead = { ...skeleton, steps: skeleton.steps.map((s, i) => (i === 0 ? { ...s, state: 'PASSED' } : s)) }

  it('keeps the run the events took further, takes a newer or more advanced poll', () => {
    expect(preferRun(ahead, skeleton)).toBe(ahead)
    expect(preferRun(skeleton, ahead)).toBe(ahead)
    expect(preferRun(ahead, { ...skeleton, runId: 4 }).runId).toBe(4)
    expect(preferRun(ahead, { ...skeleton, runId: 2 })).toBe(ahead)
    expect(preferRun(ahead, null)).toBe(ahead)
    expect(preferRun(null, skeleton)).toBe(skeleton)
    expect(progress({ ...ahead, state: 'PASSED', endState: {} })).toBeGreaterThan(progress({ ...ahead, state: 'PASSED' }))
  })
})

describe('texts and links', () => {
  it('strips the step prefix from event messages', () => {
    expect(stepMessage('Step 12 passed: done.')).toBe('done.')
    expect(stepMessage('Other text')).toBe('Other text')
    expect(stepMessage(null)).toBeNull()
  })

  it('formats durations and run totals', () => {
    expect(formatDuration(null)).toBeNull()
    expect(formatDuration(6)).toBe('6 ms')
    expect(formatDuration(3169)).toBe('3.2 s')
    expect(formatDuration(64000)).toBe('1 min 04 s')
    expect(runDuration({ startedAt: '2026-10-19T10:00:00Z', finishedAt: '2026-10-19T10:00:14Z' })).toBe(14000)
    expect(runDuration({ startedAt: '2026-10-19T10:00:00Z', finishedAt: null })).toBeNull()
  })

  it('labels modules from the catalog and facts in sentence case', () => {
    expect(moduleLabel('mapreduce')).toBe('Exp 7: MapReduce')
    expect(moduleLabel('somethingElse')).toBe('somethingElse')
    expect(factLabel('crashedNodeId')).toBe('Crashed node id')
    expect(formatFact(true)).toBe('yes')
    expect(formatFact(false)).toBe('no')
    expect(formatFact([1, 2])).toBe('1, 2')
    expect(formatFact({ 'node 1': 36, 'node 5': 34 })).toBe('node 1 36, node 5 34')
    expect(formatFact(null)).toBe('—')
  })

  it('builds the Timeline link from catalog ids and literals only, in bus order', () => {
    const href = timelineHref(SCENARIOS[0])
    const params = new URLSearchParams(href.split('?')[1])
    expect(href.startsWith('/timeline?')).toBe(true)
    expect(params.get('modules')).toBe('scenario,cluster,election,rmi,mpi,matrix')
    expect(params.get('order')).toBe('arrival')
  })

  it('finds the step the audience is watching', () => {
    const run = skeletonRun({ runId: 3, scenarioId: 'matrix-leader-crash' }, SCENARIOS)
    expect(currentStep(run).index).toBe(1)
    const states = ['PASSED', 'RUNNING', 'PENDING']
    expect(currentStep({ ...run, steps: run.steps.map((s, i) => ({ ...s, state: states[i] })) }).index).toBe(2)
    expect(currentStep({ ...run, steps: run.steps.map((s, i) => ({ ...s, state: i < 2 ? 'PASSED' : 'PENDING' })) }).index).toBe(2)
    expect(currentStep(null)).toBeNull()
  })
})
