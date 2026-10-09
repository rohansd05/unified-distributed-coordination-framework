import { describe, expect, it } from 'vitest'
import overviewIdle from '@/test/fixtures/mapreduce/overview-idle.json'
import runAccepted from '@/test/fixtures/mapreduce/run-accepted.json'
import sampleRun from '@/test/fixtures/mapreduce/run-sample-word-count.json'
import crashRun from '@/test/fixtures/mapreduce/run-crash-retry.json'
import latencyRun from '@/test/fixtures/mapreduce/run-upload-avg-latency.json'
import truncatedRun from '@/test/fixtures/mapreduce/run-truncated-result.json'
import eventLogRun from '@/test/fixtures/mapreduce/run-event-log-event-category.json'
import emptyRun from '@/test/fixtures/mapreduce/run-event-log-empty-result.json'
import history from '@/test/fixtures/mapreduce/runs-history.json'
import {
  baselineCandidate,
  chartModel,
  compareResults,
  crashCallout,
  crashChoices,
  crashSentence,
  dash,
  failedAttemptCount,
  historyRows,
  ms,
  noticeCallouts,
  pipelineStages,
  resultRows,
  retryArrows,
  runStatusText,
  taskAttemptRows,
} from './runModel'

const copy = (value) => JSON.parse(JSON.stringify(value))

describe('null is "—", never 0', () => {
  it('dash and ms', () => {
    expect([dash(null), dash(undefined), dash(''), dash(Number.NaN), dash(0), dash(3, 'ms')]).toEqual(['—', '—', '—', '—', '0', '3 ms'])
    expect([ms(null), ms(12.5), ms(4.04)]).toEqual(['—', '13 ms', '4.0 ms'])
  })

  it('a RUNNING run (report null) shows no number anywhere', () => {
    expect(failedAttemptCount(runAccepted)).toBeNull()
    expect(taskAttemptRows(runAccepted)).toEqual([])
    expect(resultRows(runAccepted)).toEqual([])
    expect(runStatusText(runAccepted)).toBe('Word count on Bundled sample text: running.')
  })

  it('a failed run keeps the backend\'s nulls for stages it never finished', () => {
    const failed = copy(sampleRun)
    failed.state = 'FAILED'
    failed.error = 'All workers exhausted'
    failed.report = { ...failed.report, pairsEmitted: null, pairsAfterCombine: null, shuffleKeys: null, partitions: null,
      resultKeys: null, timings: { mapMillis: null, shuffleMillis: null, reduceMillis: null, totalMillis: null }, results: [] }
    const values = Object.fromEntries(pipelineStages(failed).map((stage) => [stage.id, stage.value]))
    expect(values).toMatchObject({ combine: '—', shuffle: '—', partition: '—', reduce: '—', result: '—', split: '5' })
    expect(runStatusText(failed)).toBe('Word count on Bundled sample text: failed. All workers exhausted')
  })
})

describe('pipeline stages from the real sample run', () => {
  it('has every stage with the measured count and time', () => {
    const stages = Object.fromEntries(pipelineStages(sampleRun).map((stage) => [stage.id, stage]))
    expect(stages.split.value).toBe('5')
    expect(stages.combine.value).toBe(String(sampleRun.report.pairsAfterCombine))
    expect(stages.combine.detail).toBe(`pairs sent, from ${sampleRun.report.pairsEmitted} emitted`)
    expect(stages.result.value).toBe('116')
    expect(stages.map.lanes.map((lane) => lane.tasks)).toEqual([1, 1, 1, 1, 1])
  })

  it('marks the crashed worker in the lanes of the crash run, and a node that ran nothing as 0 tasks (it is in the report)', () => {
    const map = pipelineStages(crashRun).find((stage) => stage.id === 'map')
    expect(map.lanes.find((lane) => lane.nodeId === 3)).toEqual({ nodeId: 3, tasks: 0, crashed: true })
    expect(map.lanes.find((lane) => lane.nodeId === 4).tasks).toBe(2)
  })
})

describe('tasks and retries (run-crash-retry.json)', () => {
  it('draws one retry per failed attempt, from the failed node to the node that re-ran it', () => {
    expect(retryArrows(crashRun)).toEqual([
      { key: 'MAP-3-1', taskType: 'MAP', taskNumber: 3, fromNode: 3, toNode: 4, reason: 'Connection refused: connect' },
      { key: 'REDUCE-3-1', taskType: 'REDUCE', taskNumber: 3, fromNode: 3, toNode: 4, reason: 'Connection refused: connect' },
    ])
    expect(failedAttemptCount(crashRun)).toBe(2)
  })

  it('lists every attempt: the failed one on node 3, then the completed one on node 4', () => {
    const rows = taskAttemptRows(crashRun).filter((row) => row.task === 'Map task 3')
    expect(rows.map((row) => [row.attempt, row.nodeId, row.outcome, row.retried])).toEqual([[1, 3, 'failed', true], [2, 4, 'completed', true]])
    expect(taskAttemptRows(crashRun)).toHaveLength(crashRun.report.tasks.length + 2)
  })

  it('states the crash and the stage that was hit', () => {
    expect(crashSentence(crashRun)).toBe('Node 3 was crashed right after its first map task was sent (the map stage).')
    const noTask = copy(crashRun)
    noTask.crash = { workerId: 5, triggered: false, nodeCrashed: null, taskType: null }
    expect(crashSentence(noTask)).toBe('Node 5 received no task in this run, so it was not crashed.')
    expect(crashSentence(sampleRun)).toBeNull()
  })
})

describe('results and chart', () => {
  it('charts counts largest first, at most 20 bars', () => {
    const model = chartModel(sampleRun)
    expect(model.metric).toBe('count')
    expect(model.bars).toHaveLength(20)
    expect(model.bars[0].value).toBeGreaterThanOrEqual(model.bars[1].value)
    expect(model.omitted).toBe(96)
  })

  it('charts the latency job by its average, computed by the backend from sum and count', () => {
    const model = chartModel(latencyRun)
    expect(model.metric).toBe('average')
    expect(model.bars).toEqual([{ key: 'node-3', value: 23.6 }, { key: 'node-2', value: 9.32 }, { key: 'node-1', value: 4.05 }])
  })

  it('leaves a row without a value out of the chart instead of drawing 0', () => {
    const run = copy(latencyRun)
    run.report.results[0].averageMillis = null
    expect(chartModel(run).bars.map((bar) => bar.key)).toEqual(['node-3', 'node-2'])
  })
})

describe('the "identical result" claim (condition A)', () => {
  it('the sample run is the crash run\'s baseline in the real history', () => {
    expect(baselineCandidate(crashRun, history)?.runId).toBe(sampleRun.runId)
  })

  it('matching baseline: same job, the bundled sample, both complete, every row equal: identical', () => {
    expect(compareResults(crashRun, sampleRun)).toEqual({ verdict: 'identical', reason: 'all-rows-match' })
  })

  it('a different row is reported as different, never as identical', () => {
    const baseline = copy(sampleRun)
    baseline.report.results[0].count += 1
    baseline.report.results[0].display = String(baseline.report.results[0].count)
    expect(compareResults(crashRun, baseline).verdict).toBe('different')
  })

  it('truncated result lists are not compared', () => {
    const run = copy(truncatedRun)
    run.crash = { workerId: 3, triggered: true, nodeCrashed: true, taskType: 'MAP' }
    expect(compareResults(run, truncatedRun)).toEqual({ verdict: 'not-comparable', reason: 'truncated' })
  })

  it('an upload with the same name but different inputBytes is not compared', () => {
    const run = copy(truncatedRun)
    run.report.resultsTruncated = false
    const baseline = copy(run)
    baseline.inputBytes = run.inputBytes + 1
    expect(compareResults(run, baseline)).toEqual({ verdict: 'not-comparable', reason: 'different-input' })
    baseline.inputBytes = run.inputBytes
    expect(compareResults(run, baseline).verdict).toBe('identical')
  })

  it('the live event log is never compared, even with an otherwise matching run', () => {
    expect(baselineCandidate(eventLogRun, history)).toBeNull()
    expect(compareResults(eventLogRun, eventLogRun)).toEqual({ verdict: 'not-comparable', reason: 'event-log' })
  })

  it('no baseline: nothing is compared', () => {
    expect(baselineCandidate(crashRun, history.filter((summary) => summary.runId !== sampleRun.runId))).toBeNull()
    expect(compareResults(crashRun, null)).toEqual({ verdict: 'not-comparable', reason: 'no-baseline' })
  })

  it('the crash callout claims "identical" only with that verdict, and otherwise states only what is known', () => {
    const identical = crashCallout(crashRun, { verdict: 'identical', baselineId: 'd4055014' })
    expect(identical).toBe('Node 3 was crashed during the map stage; 2 tasks were re-executed on other nodes and the run completed.'
      + ' All 116 result rows are identical to the earlier run d4055014 without a crash.')
    for (const reason of ['no-baseline', 'event-log', 'truncated', 'different-input']) {
      const text = crashCallout(crashRun, { verdict: 'not-comparable', reason })
      expect(text).not.toContain('identical')
      expect(text).toContain('Node 3 was crashed during the map stage; 2 tasks were re-executed')
    }
  })
})

describe('what to notice', () => {
  it('reads the combiner and crash numbers from the real crash run', () => {
    const [combiner, crash] = noticeCallouts(crashRun, { verdict: 'identical', baselineId: 'd4055014' })
    expect(combiner).toBe('In the latest run the mappers emitted 930 pairs and the combiner cut them to 416 before the shuffle: 55.3% less data crossed the network.')
    expect(crash).toContain('identical to the earlier run d4055014')
  })

  it('reads the sum;count lesson from a real latency row', () => {
    expect(noticeCallouts(latencyRun, null)[2]).toBe('node-1: 4.05 ms is the sum of 151 measured latencies divided by 151, once, at the end.'
      + ' Averaging each mapper\'s average instead would give every mapper the same weight, whatever its count.')
  })

  it('without a run, the callouts contain no number at all', () => {
    for (const callout of noticeCallouts(null, null)) {
      expect(callout).not.toMatch(/\d/)
    }
    expect(noticeCallouts(emptyRun, null)[2]).not.toMatch(/\d/)
  })
})

describe('controls and history', () => {
  it('crash choices: every live worker but the coordinator, and a reason with fewer than two workers', () => {
    expect(crashChoices(overviewIdle)).toEqual({ candidates: [2, 3, 4, 5], reason: null })
    expect(crashChoices({ ...overviewIdle, workerIds: [1] }).reason).toContain('at least two live workers')
  })

  it('history rows keep nulls', () => {
    const rows = historyRows([{ ...history[0], totalMillis: null, resultKeys: null }])
    expect(rows[0]).toMatchObject({ totalMillis: null, resultKeys: null, crashWorkerId: 3 })
  })
})
