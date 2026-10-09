import { formatMillis, plural, runStateLabel, taskTypeLabel, taskTypePhrase } from './labels'

/**
 * The page's mapping layer: every MapReduce DTO (docs/tracks/track-d-rohan.md, "E7c interfaces")
 * becomes a small view model here, so the components only lay things out. Pure functions, no
 * React. A value the backend sent as null stays null (shown as "—"), never 0 (R7).
 */

export const LATENCY_JOB = 'avg-latency-per-node'
export const DASH = '—'

/** A finite number, or null: unavailable data is never turned into 0 or NaN. */
export function num(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

/** A value for display, or "—" when it is missing. */
export function dash(value, unit) {
  if (value === null || value === undefined || value === '' || (typeof value === 'number' && !Number.isFinite(value))) {
    return DASH
  }
  return unit ? `${value} ${unit}` : String(value)
}

/** Milliseconds as text: "12.5 ms", or "—" when not measured. */
export function ms(value) {
  const formatted = formatMillis(num(value))
  return formatted === null ? DASH : `${formatted} ms`
}

export const isTerminal = (state) => state === 'COMPLETED' || state === 'FAILED'

const list = (value) => (Array.isArray(value) ? value : [])

/** The overview's fields with safe defaults for missing lists. */
export function overviewModel(overview) {
  return {
    status: overview?.status ?? null,
    currentAction: overview?.currentAction ?? null,
    jobs: list(overview?.jobs),
    inputTypes: list(overview?.inputTypes),
    coordinatorId: num(overview?.coordinatorId),
    workerIds: list(overview?.workerIds).filter((id) => num(id) !== null),
    limits: overview?.limits ?? null,
    notes: list(overview?.notes),
  }
}

/**
 * The live workers a crash can be planned on: every worker except the coordinator, and only
 * with at least two live workers (the backend's rule: the task must be able to go elsewhere).
 *
 * @returns {{ candidates: number[], reason: string|null }}
 */
export function crashChoices(overview) {
  const { workerIds, coordinatorId } = overviewModel(overview)
  if (workerIds.length < 2) {
    return { candidates: [], reason: 'A crash needs at least two live workers, so the task can be retried on another one.' }
  }
  const candidates = workerIds.filter((id) => id !== coordinatorId)
  return { candidates, reason: candidates.length === 0 ? 'No live worker other than the coordinator.' : null }
}

function perNode(map, nodeId) {
  if (!map || typeof map !== 'object') {
    return null
  }
  const value = map[String(nodeId)]
  return num(value) ?? 0   // the report lists every node that ran a task; a node not listed ran none
}

/** Task rows that needed more than one attempt and then completed, as "from node -> to node". */
export function retryArrows(run) {
  return list(run?.report?.tasks)
    .filter((task) => task.completed && list(task.failedAttempts).length > 0)
    .flatMap((task) => list(task.failedAttempts).map((failed) => ({
      key: `${task.taskType}-${task.taskNumber}-${failed.attempt}`,
      taskType: task.taskType,
      taskNumber: task.taskNumber,
      fromNode: failed.workerId,
      toNode: task.workerId,
      reason: failed.reason ?? null,
    })))
}

/** Failed attempts across every task of the run, or null when there is no report yet. */
export function failedAttemptCount(run) {
  const tasks = run?.report?.tasks
  if (!Array.isArray(tasks)) {
    return null
  }
  return tasks.reduce((total, task) => total + list(task.failedAttempts).length, 0)
}

/**
 * The pipeline's stages in order, each with the numbers the report really has (null while the
 * run is RUNNING, or for a stage a failed run never finished).
 */
export function pipelineStages(run) {
  const report = run?.report ?? null
  const timings = report?.timings ?? null
  const workers = list(run?.workerIds)
  const crashed = run?.crash?.triggered && run.crash.nodeCrashed !== false ? run.crash.workerId : null
  const lanes = (perNodeMap) => workers.map((nodeId) => ({
    nodeId,
    tasks: report ? perNode(perNodeMap, nodeId) : null,
    crashed: nodeId === crashed,
  }))
  return [
    { id: 'input', label: 'Input', value: dash(run?.inputName), detail: `${dash(num(report?.inputLines))} lines` },
    { id: 'split', label: 'Split', value: dash(num(report?.splits)), detail: 'splits, one map task each' },
    { id: 'map', label: 'Map', value: dash(num(report?.mapTasks)), detail: 'map tasks', timing: ms(timings?.mapMillis), lanes: lanes(report?.mapTasksPerNode) },
    {
      id: 'combine',
      label: 'Combine',
      value: dash(num(report?.pairsAfterCombine)),
      detail: `pairs sent, from ${dash(num(report?.pairsEmitted))} emitted`,
    },
    { id: 'shuffle', label: 'Shuffle', value: dash(num(report?.shuffleKeys)), detail: 'distinct keys', timing: ms(timings?.shuffleMillis) },
    { id: 'partition', label: 'Partition', value: dash(num(report?.partitions)), detail: `non-empty of R = ${dash(num(report?.reducers))}` },
    { id: 'reduce', label: 'Reduce', value: dash(num(report?.partitions)), detail: 'reduce tasks', timing: ms(timings?.reduceMillis), lanes: lanes(report?.reduceTasksPerNode) },
    { id: 'result', label: 'Result', value: dash(num(report?.resultKeys)), detail: 'result keys', timing: ms(timings?.totalMillis) },
  ]
}

/** One sentence on the run's state, for the live region and the figure caption. */
export function runStatusText(run) {
  if (!run) {
    return 'No run yet. Choose a job and an input, then select Run.'
  }
  const what = `${run.jobTitle ?? run.jobId} on ${dash(run.inputName)}`
  if (run.state === 'RUNNING') {
    return `${what}: running.`
  }
  if (run.state === 'FAILED') {
    return `${what}: failed. ${run.error ?? ''}`.trim()
  }
  const keys = num(run.report?.resultKeys)
  return `${what}: ${runStateLabel(run.state).toLowerCase()} in ${ms(run.report?.timings?.totalMillis)}`
    + (keys === null ? '.' : `, ${plural(keys, 'result key')}.`)
}

/** Every attempt of every task: failed attempts first, then the attempt that completed it. */
export function taskAttemptRows(run) {
  return list(run?.report?.tasks).flatMap((task) => {
    const label = `${taskTypeLabel(task.taskType)} task ${task.taskNumber}`
    const failed = list(task.failedAttempts).map((attempt) => ({
      key: `${task.taskType}-${task.taskNumber}-${attempt.attempt}`,
      task: label,
      taskType: task.taskType,
      attempt: attempt.attempt,
      nodeId: num(attempt.workerId),
      outcome: 'failed',
      reason: attempt.reason ?? null,
      retried: task.attempts > 1,
    }))
    const done = task.completed ? [{
      key: `${task.taskType}-${task.taskNumber}-ok`,
      task: label,
      taskType: task.taskType,
      attempt: num(task.attempts),
      nodeId: num(task.workerId),
      outcome: 'completed',
      reason: null,
      retried: task.attempts > 1,
    }] : []
    return [...failed, ...done]
  })
}

/** The result rows as the backend sent them (sorted by key, at most resultRowsMax). */
export function resultRows(run) {
  return list(run?.report?.results).map((row) => ({
    key: row.key,
    display: dash(row.display),
    count: num(row.count),
    averageMillis: num(row.averageMillis),
  }))
}

/**
 * Bars for the results chart: the latency job draws each node's average, every other job its
 * count. A row without that value is left out (never drawn as 0). Largest first, at most maxBars.
 */
export function chartModel(run, maxBars = 20) {
  const metric = run?.jobId === LATENCY_JOB ? 'average' : 'count'
  const valued = resultRows(run)
    .map((row) => ({ key: row.key, value: metric === 'average' ? row.averageMillis : row.count }))
    .filter((bar) => bar.value !== null)
    .sort((a, b) => b.value - a.value || a.key.localeCompare(b.key))
  const bars = valued.slice(0, maxBars)
  const unit = metric === 'average' ? 'ms average' : 'count'
  const summary = bars.length === 0
    ? 'No values to chart.'
    : `${metric === 'average' ? 'Average latency' : 'Count'} for the ${plural(bars.length, 'largest key')} of ${plural(resultRows(run).length, 'listed key')}: `
      + bars.map((bar) => `${bar.key} ${bar.value}`).join(', ') + '.'
  return { metric, unit, bars, omitted: valued.length - bars.length, summary }
}

/** Ids of the crashed nodes in a GET /api/cluster body. */
export function downNodes(cluster) {
  return list(cluster?.nodes).filter((node) => node.status === 'CRASHED').map((node) => node.id)
}

/** The run's crash plan in one sentence, or null for a run without one. */
export function crashSentence(run) {
  const crash = run?.crash
  if (!crash) {
    return null
  }
  if (!crash.triggered) {
    return run.state === 'RUNNING'
      ? `Node ${crash.workerId} will be crashed right after the first task is sent to it.`
      : `Node ${crash.workerId} received no task in this run, so it was not crashed.`
  }
  const task = crash.taskType ? taskTypePhrase(crash.taskType) : 'first'
  if (crash.nodeCrashed === false) {
    return `Node ${crash.workerId} was already down when its first ${task} task was sent.`
  }
  return `Node ${crash.workerId} was crashed right after its first ${task} task was sent (the ${task} stage).`
}

/**
 * The newest earlier COMPLETED run without a crash plan, with the same job, input type and input
 * name, from the history summaries (newest first). Never one for the live event log, whose
 * content changes between runs. The caller fetches it and compareResults checks the rest.
 */
export function baselineCandidate(run, summaries) {
  if (!run || run.inputType === 'EVENT_LOG') {
    return null
  }
  const started = Date.parse(run.startedAt ?? '')
  return list(summaries).find((summary) => summary.runId !== run.runId
    && summary.state === 'COMPLETED'
    && (summary.crashWorkerId === null || summary.crashWorkerId === undefined)
    && summary.jobId === run.jobId
    && summary.inputType === run.inputType
    && summary.inputName === run.inputName
    && (!Number.isFinite(started) || Date.parse(summary.startedAt ?? '') < started)) ?? null
}

const sameRow = (a, b) => a.key === b.key && a.display === b.display
  && (a.count ?? null) === (b.count ?? null) && (a.averageMillis ?? null) === (b.averageMillis ?? null)

/**
 * Whether a crash run's result is provably identical to a baseline run's. "identical" only when:
 * the same job and input type; the input content is provably the same (the bundled sample, or an
 * upload with the same file name and the same inputBytes; never the live event log); both runs
 * COMPLETED; neither result list truncated; and every row equal.
 *
 * @returns {{ verdict: 'identical'|'different'|'not-comparable', reason: string }}
 */
export function compareResults(run, baseline) {
  if (run?.inputType === 'EVENT_LOG') {
    return { verdict: 'not-comparable', reason: 'event-log' }
  }
  if (!baseline) {
    return { verdict: 'not-comparable', reason: 'no-baseline' }
  }
  if (baseline.jobId !== run.jobId || baseline.inputType !== run.inputType) {
    return { verdict: 'not-comparable', reason: 'different-input' }
  }
  if (run.inputType === 'UPLOAD') {
    const sameBytes = num(run.inputBytes) !== null && num(run.inputBytes) === num(baseline.inputBytes)
    if (run.inputName !== baseline.inputName || !sameBytes) {
      return { verdict: 'not-comparable', reason: 'different-input' }
    }
  } else if (run.inputType !== 'SAMPLE') {
    return { verdict: 'not-comparable', reason: 'different-input' }
  }
  if (run.state !== 'COMPLETED' || baseline.state !== 'COMPLETED' || !run.report || !baseline.report) {
    return { verdict: 'not-comparable', reason: 'not-completed' }
  }
  if (run.report.resultsTruncated || baseline.report.resultsTruncated) {
    return { verdict: 'not-comparable', reason: 'truncated' }
  }
  const a = list(run.report.results)
  const b = list(baseline.report.results)
  const equal = run.report.resultKeys === baseline.report.resultKeys && a.length === b.length
    && a.every((row, index) => sameRow(row, b[index]))
  return equal ? { verdict: 'identical', reason: 'all-rows-match' } : { verdict: 'different', reason: 'rows-differ' }
}

const NOT_COMPARED = {
  'event-log': 'The live event log changes between runs (each run adds events), so this result is not compared with another run.',
  'no-baseline': 'There is no earlier run of the same job on the same input without a crash, so the result is not compared.',
  'different-input': 'The earlier run used a different upload (name or size), so the results are not compared.',
  'not-completed': 'The earlier run did not complete, so the results are not compared.',
  truncated: 'A result list is truncated, so the runs cannot be compared row by row.',
  'baseline-unavailable': 'The earlier run could not be loaded, so the results are not compared.',
}

/** The crash callout: what is known about the crash, and the comparison only where it is provable. */
export function crashCallout(run, comparison) {
  const crash = run?.crash
  if (!crash || !crash.triggered || run.state !== 'COMPLETED') {
    return null
  }
  const retried = num(run.report?.retriedTasks)
  const parts = [
    `Node ${crash.workerId} was crashed during the ${taskTypePhrase(crash.taskType)} stage;`
      + ` ${retried === null ? 'the' : plural(retried, 'task')} ${retried === 1 ? 'was' : 'were'} re-executed on other nodes and the run completed.`,
  ]
  if (comparison?.verdict === 'identical') {
    parts.push(`All ${plural(num(run.report?.resultKeys) ?? 0, 'result row')} are identical to the earlier run ${comparison.baselineId} without a crash.`)
  } else if (comparison?.verdict === 'different') {
    parts.push(`The result differs from the earlier run ${comparison.baselineId} without a crash.`)
  } else if (comparison?.reason && NOT_COMPARED[comparison.reason]) {
    parts.push(NOT_COMPARED[comparison.reason])
  }
  return parts.join(' ')
}

/**
 * The "What to notice" callouts, read from the real latest run. Without a run (or without the
 * number a callout needs) the callout explains what to try, with no number in it.
 */
export function noticeCallouts(run, comparison) {
  const report = run?.state === 'COMPLETED' ? run.report : null
  const emitted = num(report?.pairsEmitted)
  const shipped = num(report?.pairsAfterCombine)
  const saving = num(report?.combinerSavingPercent)
  const combiner = emitted !== null && shipped !== null && saving !== null
    ? `In the latest run the mappers emitted ${emitted} pairs and the combiner cut them to ${shipped} before the shuffle: ${saving}% less data crossed the network.`
    : 'The combiner adds up values on the mapper\'s own node before anything is sent. Run a job to see how many pairs it saves.'

  const crash = crashCallout(run, comparison)
    ?? 'Crash a worker during a run: the coordinator sends that worker\'s task to another node, and the run still completes.'

  const latency = run?.jobId === LATENCY_JOB && report
    ? resultRows(run).find((row) => row.averageMillis !== null && row.count !== null && row.count > 0)
    : null
  const sumCount = latency
    ? `${latency.key}: ${latency.averageMillis} ms is the sum of ${latency.count} measured ${latency.count === 1 ? 'latency' : 'latencies'} divided by ${latency.count}, once, at the end. Averaging each mapper's average instead would give every mapper the same weight, whatever its count.`
    : 'The average latency job carries a sum and a count through every stage and divides only at the end, because an average of averages is not the true average.'

  return [combiner, crash, sumCount]
}

/**
 * A refused request (an ApiError from services/api.js, built from the backend's ProblemDetail) as
 * the page shows it: the backend's own title and detail, and its field messages.
 */
export function actionErrorFrom(err) {
  return {
    status: typeof err?.status === 'number' ? err.status : null,
    title: err?.title || 'Request failed',
    detail: err?.detail || err?.message || 'The request failed.',
    fields: err?.errors && typeof err.errors === 'object' ? err.errors : {},
  }
}

/** The history rows; every missing value stays null for "—". */
export function historyRows(summaries) {
  return list(summaries).map((summary) => ({
    runId: summary.runId,
    state: summary.state,
    jobId: summary.jobId,
    inputType: summary.inputType,
    inputName: summary.inputName ?? null,
    startedAt: summary.startedAt ?? null,
    totalMillis: num(summary.totalMillis),
    resultKeys: num(summary.resultKeys),
    retriedTasks: num(summary.retriedTasks),
    crashWorkerId: num(summary.crashWorkerId),
  }))
}
