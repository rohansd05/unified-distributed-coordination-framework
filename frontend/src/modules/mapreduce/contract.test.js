import { describe, expect, it } from 'vitest'
import { ApiError } from '@/services/api'
import exportText from '@/test/fixtures/mapreduce/events-export.txt?raw'
import {
  actionErrorFrom,
  chartModel,
  crashChoices,
  downNodes,
  historyRows,
  overviewModel,
  pipelineStages,
  resultRows,
  runStatusText,
  taskAttemptRows,
} from './runModel'

/*
 * Every fixture captured from the real backend in E7c (frontend/src/test/fixtures/mapreduce,
 * never edited) is checked here: the fields the page reads have the types it expects, and each
 * one goes through the page's mapping layer (runModel) without producing "undefined" or "NaN".
 * A backend rename or type change breaks this test. No fixture is left out.
 */

const jsonFixtures = import.meta.glob('@/test/fixtures/mapreduce/*.json', { eager: true, import: 'default' })
const byName = Object.fromEntries(Object.entries(jsonFixtures).map(([path, value]) => [path.split('/').pop(), value]))

const isString = (value) => typeof value === 'string'
const isNumber = (value) => typeof value === 'number' && Number.isFinite(value)
const isBoolean = (value) => typeof value === 'boolean'
const orNull = (check) => (value) => value === null || check(value)
const isList = Array.isArray

function expectShape(object, shape, where) {
  expect(object, `${where} is an object`).toBeTypeOf('object')
  for (const [field, check] of Object.entries(shape)) {
    expect(object, `${where} has ${field}`).toHaveProperty(field)
    expect(check(object[field]), `${where}.${field} = ${JSON.stringify(object[field])}`).toBe(true)
  }
}

const noBadText = (text, where) => {
  expect(text, where).not.toMatch(/undefined|NaN|\[object Object\]/)
}

const OVERVIEW = {
  status: isString,
  currentAction: orNull(isString),
  jobs: isList,
  inputTypes: isList,
  coordinatorId: orNull(isNumber),
  workerIds: isList,
  limits: (v) => ['uploadMaxBytes', 'requestBodyMaxBytes', 'eventLogMaxBytes', 'eventLogMaxEvents', 'resultRowsMax',
    'runHistorySize', 'taskTimeoutMillis'].every((key) => isNumber(v?.[key])),
  latestRun: (v) => v === null || typeof v === 'object',
  notes: isList,
}
const RUN = {
  runId: isString,
  state: (v) => ['RUNNING', 'COMPLETED', 'FAILED'].includes(v),
  jobId: isString,
  jobTitle: isString,
  inputType: (v) => ['SAMPLE', 'UPLOAD', 'EVENT_LOG'].includes(v),
  inputName: isString,
  inputBytes: orNull(isNumber),
  coordinatorId: isNumber,
  workerIds: isList,
  crash: (v) => v === null || (isNumber(v.workerId) && isBoolean(v.triggered)
    && orNull(isBoolean)(v.nodeCrashed) && orNull(isString)(v.taskType)),
  startedAt: isString,
  finishedAt: orNull(isString),
  report: (v) => v === null || typeof v === 'object',
  error: orNull(isString),
  notice: orNull(isString),
}
const REPORT = {
  reducers: isNumber,
  inputLines: isNumber,
  inputLinesDropped: orNull(isNumber),
  splits: isNumber,
  mapTasks: isNumber,
  pairsEmitted: orNull(isNumber),
  pairsAfterCombine: orNull(isNumber),
  shuffleKeys: orNull(isNumber),
  partitions: orNull(isNumber),
  resultKeys: orNull(isNumber),
  combinerSavingPercent: orNull(isNumber),
  retriedTasks: isNumber,
  timings: (v) => ['mapMillis', 'shuffleMillis', 'reduceMillis', 'totalMillis'].every((key) => orNull(isNumber)(v?.[key])),
  mapTasksPerNode: (v) => typeof v === 'object' && Object.values(v).every(isNumber),
  reduceTasksPerNode: (v) => typeof v === 'object' && Object.values(v).every(isNumber),
  tasks: isList,
  results: isList,
  resultsTruncated: isBoolean,
}
const TASK = { taskType: isString, taskNumber: isNumber, completed: isBoolean, workerId: orNull(isNumber), attempts: isNumber, failedAttempts: isList }
const RESULT = { key: isString, display: isString, count: orNull(isNumber), averageMillis: orNull(isNumber) }
const SUMMARY = {
  runId: isString, state: isString, jobId: isString, inputType: isString, inputName: isString, startedAt: isString,
  finishedAt: orNull(isString), totalMillis: orNull(isNumber), resultKeys: orNull(isNumber), retriedTasks: orNull(isNumber),
  crashWorkerId: orNull(isNumber),
}
const PROBLEM = { type: isString, title: isString, status: isNumber, detail: isString, instance: isString }
/** Event types the backend source really publishes on /topic/modules/mapreduce (checked with grep in E7d). */
const MAPREDUCE_EVENT_TYPES = ['JOB_STARTED', 'JOB_COMPLETED', 'JOB_FAILED', 'WORKER_CRASH_TRIGGERED', 'TASK_SENT',
  'TASK_RECEIVED', 'TASK_COMPLETED', 'TASK_FAILED', 'TASK_ATTEMPT_FAILED', 'REQUEST_REFUSED', 'SERVICE_START_FAILED']

function checkRun(run, name) {
  expectShape(run, RUN, name)
  if (run.report) {
    expectShape(run.report, REPORT, `${name}.report`)
    run.report.tasks.forEach((task, i) => expectShape(task, TASK, `${name}.tasks[${i}]`))
    run.report.results.forEach((row, i) => expectShape(row, RESULT, `${name}.results[${i}]`))
  }
  const stages = pipelineStages(run)
  expect(stages).toHaveLength(8)
  stages.forEach((stage) => noBadText(`${stage.value} ${stage.detail} ${stage.timing ?? ''}`, `${name} stage ${stage.id}`))
  taskAttemptRows(run).forEach((row) => noBadText(`${row.task} ${row.nodeId} ${row.reason ?? ''}`, `${name} task row`))
  expect(resultRows(run)).toHaveLength(run.report ? run.report.results.length : 0)
  noBadText(chartModel(run).summary, `${name} chart`)
  noBadText(runStatusText(run), `${name} status`)
}

function checkProblem(body, name) {
  expectShape(body, PROBLEM, name)
  const shown = actionErrorFrom(new ApiError({ status: body.status, title: body.title, detail: body.detail, errors: body.errors ?? null }))
  expect(shown).toEqual({ status: body.status, title: body.title, detail: body.detail, fields: body.errors ?? {} })
  return shown
}

const CHECKS = {
  'overview-idle.json': (body) => {
    expectShape(body, OVERVIEW, 'overview-idle')
    expect(body.latestRun).toBeNull()
    expect(overviewModel(body).jobs.map((job) => job.id)).toEqual(['word-count', 'event-category-count', 'avg-latency-per-node'])
    expect(overviewModel(body).inputTypes.map((type) => type.id)).toEqual(['SAMPLE', 'UPLOAD', 'EVENT_LOG'])
    expect(crashChoices(body).candidates).toEqual(body.workerIds.filter((id) => id !== body.coordinatorId))
  },
  'overview-after-runs.json': (body) => {
    expectShape(body, OVERVIEW, 'overview-after-runs')
    checkRun(body.latestRun, 'overview-after-runs.latestRun')
  },
  'runs-history.json': (body) => {
    expect(isList(body)).toBe(true)
    body.forEach((summary, i) => expectShape(summary, SUMMARY, `runs-history[${i}]`))
    expect(historyRows(body)).toHaveLength(body.length)
  },
  'cluster-after-crash.json': (body) => {
    expect(downNodes(body)).toEqual([3])
  },
  'events-mapreduce.json': (body) => {
    expect(isList(body)).toBe(true)
    body.forEach((event) => {
      expectShape(event, { sequence: isNumber, module: isString, nodeId: isNumber, type: isString, lamportTime: isNumber, message: isString }, 'event')
      expect(MAPREDUCE_EVENT_TYPES).toContain(event.type)
    })
  },
  'error-400-missing-fields.json': (body) => expect(Object.keys(checkProblem(body, '400').fields)).toEqual(['jobId', 'inputType']),
  'error-400-crash-worker.json': (body) => expect(checkProblem(body, '400').fields).toHaveProperty('crashWorkerId'),
  'error-404-unknown-run.json': (body) => {
    checkProblem(body, '404')
    expect(body.runId).toBe('00000000')
  },
  'error-404-no-run-yet.json': (body) => {
    checkProblem(body, '404')
    expect(body.runId).toBeNull()
  },
  'error-404-unknown-job.json': (body) => {
    checkProblem(body, '404')
    expect(body.jobId).toBe('nope')
  },
  'error-409-busy.json': (body) => expect(checkProblem(body, '409').title).toBe('Module busy'),
  'error-409-no-live-worker.json': (body) => expect(checkProblem(body, '409').title).toBe('No live worker'),
  'error-413-body-too-large.json': (body) => {
    expect(checkProblem(body, '413').status).toBe(413)
    expect(isNumber(body.limitBytes)).toBe(true)
  },
}

const UPLOAD_FIELD = {
  'error-400-upload-missing.json': 'upload',
  'error-400-upload-file-name.json': 'upload.fileName',
  'error-400-upload-content-type.json': 'upload.contentType',
  'error-400-upload-not-base64.json': 'upload.contentBase64',
  'error-400-upload-empty.json': 'upload.contentBase64',
  'error-400-upload-too-large.json': 'upload.contentBase64',
  'error-400-upload-not-utf8.json': 'upload.contentBase64',
  'error-400-upload-nul.json': 'upload.contentBase64',
  'error-400-upload-control-character.json': 'upload.contentBase64',
}

function checkFixture(name, body) {
  if (CHECKS[name]) {
    CHECKS[name](body)
  } else if (UPLOAD_FIELD[name]) {
    expect(checkProblem(body, name).fields).toHaveProperty(UPLOAD_FIELD[name])
  } else if (name.startsWith('run-')) {
    checkRun(body, name)
  } else {
    throw new Error(`No contract check for ${name}`)
  }
}

describe('MapReduce contract fixtures (E7c, real backend JSON)', () => {
  it('has all 30 JSON fixtures plus the export text: none is left out', () => {
    expect(Object.keys(byName)).toHaveLength(30)
    expect(exportText.length).toBeGreaterThan(0)
  })

  it.each(Object.keys(byName).sort())('%s parses through the page\'s mapping layer', (name) => {
    checkFixture(name, byName[name])
  })

  it('events-export.txt: every line is in the MapReduce log format, from module mapreduce', () => {
    const lines = exportText.split('\n').filter((line) => line !== '')
    expect(lines.length).toBeGreaterThan(0)
    for (const line of lines) {
      expect(line).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \| node=\d+ \| category=[A-Z_]+ \| seq=\d+ \| lamport=\d+ \| module=mapreduce/)
    }
  })

  it('run-accepted.json: a RUNNING run maps to "—" for every measured stage, never 0', () => {
    const stages = pipelineStages(byName['run-accepted.json'])
    expect(stages.filter((stage) => stage.id !== 'input').map((stage) => stage.value)).toEqual(Array(7).fill('—'))
    expect(stages.find((stage) => stage.id === 'map').lanes.every((lane) => lane.tasks === null)).toBe(true)
  })
})
