import { describe, expect, it } from 'vitest'
import overviewBefore from '@/test/fixtures/faulttolerance/overview-before-start.json'
import overviewAfterStart from '@/test/fixtures/faulttolerance/overview-after-start.json'
import overviewAfterRuns from '@/test/fixtures/faulttolerance/overview-after-runs.json'
import statusRunning from '@/test/fixtures/faulttolerance/status-stream-running.json'
import statusDuringFailover from '@/test/fixtures/faulttolerance/status-during-failover.json'
import statusAfterDemotion from '@/test/fixtures/faulttolerance/status-after-demotion.json'
import statusLimitReached from '@/test/fixtures/faulttolerance/status-limit-reached.json'
import runSyncRestored from '@/test/fixtures/faulttolerance/run-sync-restored.json'
import runSyncRecovered from '@/test/fixtures/faulttolerance/run-sync-recovered.json'
import runInProgress from '@/test/fixtures/faulttolerance/synthetic/run-in-progress.json'
import runInterrupted from '@/test/fixtures/faulttolerance/synthetic/run-interrupted.json'
import runPrimaryReturned from '@/test/fixtures/faulttolerance/synthetic/run-primary-returned.json'
import runRecovering from '@/test/fixtures/faulttolerance/synthetic/run-recovering.json'
import statusFailoverStopped from '@/test/fixtures/faulttolerance/synthetic/status-failover-stream-stopped.json'
import statusRejoinStalled from '@/test/fixtures/faulttolerance/synthetic/status-rejoin-stalled.json'
import statusRestoredDown from '@/test/fixtures/faulttolerance/synthetic/status-restored-old-primary-down.json'
import {
  WAITING_BUDGET_USED,
  WAITING_FOR_UPDATE,
  axisTicks,
  budgetUsedText,
  controlsModel,
  expectedDetectionWindow,
  formatMillis,
  isFailoverInProgress,
  isRecovering,
  measurement,
  situationText,
  tickLabel,
  timelineModel,
  uiState,
  words,
} from './faultToleranceModel'

const settings = overviewBefore.settings
const glossary = overviewBefore.glossary
const copy = (value) => JSON.parse(JSON.stringify(value))

describe('uiState (requirement 11 and C3)', () => {
  it.each([
    ['not-started', overviewBefore.current, null],
    ['appointing', statusDuringFailover, null],
    ['no-stream', overviewAfterStart.current, null],
    ['streaming', statusRunning, null],
    ['failover (synthetic run-in-progress)', statusDuringFailover, runInProgress],
    ['failover-waiting (synthetic status and run)', statusFailoverStopped, runInProgress],
    ['restored (synthetic old primary down)', statusRestoredDown, runSyncRestored],
    ['recovering (synthetic run and status)', statusRejoinStalled, runRecovering],
    ['demoted', overviewAfterRuns.current, overviewAfterRuns.latestRun],
    ['streaming', statusAfterDemotion, runSyncRecovered],
  ])('%s', (name, status, run) => {
    expect(uiState(status, run)).toBe(name.split(' ')[0])
  })

  it('is loading without a status', () => {
    expect(uiState(null, null)).toBe('loading')
  })

  it('counts the SUSPECTED and PROMOTING phases as a failover even before the run is read', () => {
    expect(isFailoverInProgress({ ...statusRunning, phase: 'SUSPECTED' }, null)).toBe(true)
    expect(isFailoverInProgress({ ...statusRunning, phase: 'PROMOTING' }, null)).toBe(true)
    expect(isFailoverInProgress(statusRunning, runSyncRestored)).toBe(false)
  })

  it('treats a recovery as open until RESYNCHRONISED is recorded (synthetic run-recovering)', () => {
    expect(isRecovering(runRecovering)).toBe(true)
    expect(isRecovering(runSyncRecovered)).toBe(false)
    expect(isRecovering(runSyncRestored)).toBe(false)
    expect(isRecovering(runPrimaryReturned)).toBe(false)
  })
})

describe('situationText', () => {
  it('explains the waiting failover (C3), and points at the reset when the budget is used', () => {
    expect(situationText('failover-waiting', statusFailoverStopped, runInProgress)).toBe(WAITING_FOR_UPDATE)
    const used = copy(statusFailoverStopped)
    used.stream.remainingUpdates = 0
    expect(situationText('failover-waiting', used, runInProgress)).toBe(`${WAITING_FOR_UPDATE} ${WAITING_BUDGET_USED}`)
    expect(WAITING_FOR_UPDATE).toBe('The new primary only starts serving when the next update arrives. Start the update stream to finish the failover.')
    expect(WAITING_BUDGET_USED).toBe('The budget is used. Reset the cluster on the Cluster page.')
  })

  it('names the demoted old primary and its new epoch', () => {
    expect(situationText('demoted', overviewAfterRuns.current, overviewAfterRuns.latestRun))
      .toBe('Node 1 came back and demoted itself to backup at epoch 3.')
  })
})

describe('controlsModel', () => {
  const enabled = (model) => Object.fromEntries(Object.entries(model).map(([key, value]) => [key, value.enabled]))

  it('not started: start, mode, rate and stream start only; crash says to start the stream first', () => {
    const model = controlsModel(overviewBefore.current, null)
    expect(enabled(model)).toEqual({ start: true, mode: true, rate: true, startStream: true, stopStream: false, crash: false, recover: false })
    expect(model.crash.hint).toBe('Start the update stream first: a failover is only measured once the new primary accepts an update.')
    expect(model.recover.hint).toBe('No primary has failed since the last reset.')
  })

  it('no stream (item 10): crash stays disabled with the hint', () => {
    const model = controlsModel(overviewAfterStart.current, null)
    expect(model.start.enabled).toBe(false)
    expect(model.crash.enabled).toBe(false)
    expect(model.crash.hint).toMatch(/^Start the update stream first/)
  })

  it('streaming: crash and stop enabled, rate and stream start disabled', () => {
    expect(enabled(controlsModel(statusRunning, null)))
      .toEqual({ start: false, mode: true, rate: false, startStream: false, stopStream: true, crash: true, recover: false })
  })

  it('appointing: no crash before the first primary serves', () => {
    const appointing = { ...statusRunning, currentPrimaryId: null, primaryEpoch: null }
    expect(controlsModel(appointing, null).crash).toEqual({ enabled: false, hint: 'No primary serves yet: the first primary is being appointed.' })
  })

  it('failover in progress (Q3, synthetic run): crash disabled, recover offered while the old primary is down', () => {
    const model = controlsModel(statusDuringFailover, runInProgress)
    expect(model.crash).toEqual({ enabled: false, hint: 'A failover is in progress: wait until the service is restored.' })
    expect(model.recover.enabled).toBe(true)
  })

  it('failover waiting for an update (C3, synthetic): stream start enabled, crash disabled', () => {
    const model = controlsModel(statusFailoverStopped, runInProgress)
    expect(model.startStream.enabled).toBe(true)
    expect(model.crash.enabled).toBe(false)
  })

  it('failover waiting with the budget used (C3, synthetic): stream start disabled with the budget text', () => {
    const used = copy(statusFailoverStopped)
    used.stream.remainingUpdates = 0
    const model = controlsModel(used, runInProgress)
    expect(model.startStream).toEqual({ enabled: false, hint: budgetUsedText(used.stream) })
  })

  it('budget used (Q1): stream start disabled with the served numbers', () => {
    const model = controlsModel(statusLimitReached, runSyncRecovered)
    expect(model.startStream).toEqual({ enabled: false,
      hint: 'Budget used: 400 of 400 updates this session. A cluster reset gives a fresh budget.' })
  })

  it('restored with the old primary down (synthetic): recover enabled', () => {
    expect(controlsModel(statusRestoredDown, runSyncRestored).recover).toEqual({ enabled: true, hint: null })
  })

  it('recovering (synthetic): recover disabled, the node is rejoining', () => {
    expect(controlsModel(statusRejoinStalled, runRecovering).recover).toEqual({ enabled: false, hint: 'Node 1 is rejoining.' })
  })

  it('demoted: recover disabled, the old primary is up', () => {
    expect(controlsModel(overviewAfterRuns.current, overviewAfterRuns.latestRun).recover)
      .toEqual({ enabled: false, hint: 'Node 1, the old primary, is already up.' })
  })

  it('locked (pending request or C2): every control disabled', () => {
    const model = controlsModel(statusRunning, null, { locked: true })
    expect(Object.values(model).every((control) => control.enabled === false)).toBe(true)
  })

  it('everything disabled without a status', () => {
    expect(Object.values(controlsModel(null, null)).every((control) => !control.enabled)).toBe(true)
  })
})

describe('expectedDetectionWindow (C4)', () => {
  it('is the heartbeat timeout minus and plus one interval, as the glossary defines it', () => {
    const definition = glossary.find((g) => g.id === 'expectedDetection').definition
    expect(definition).toContain('Between the heartbeat timeout minus one interval and the timeout plus one interval')
    expect(expectedDetectionWindow(settings)).toEqual({
      from: settings.heartbeatTimeoutMillis - settings.heartbeatIntervalMillis,
      to: settings.heartbeatTimeoutMillis + settings.heartbeatIntervalMillis,
    })
    expect(expectedDetectionWindow(settings)).toEqual({ from: 1800, to: 3200 })
  })

  it('is null when either setting is missing', () => {
    expect(expectedDetectionWindow({ ...settings, heartbeatTimeoutMillis: null })).toBeNull()
    expect(expectedDetectionWindow({ ...settings, heartbeatIntervalMillis: null })).toBeNull()
    expect(expectedDetectionWindow(undefined)).toBeNull()
  })
})

describe('timelineModel', () => {
  it('is null without a run', () => {
    expect(timelineModel(null, settings)).toBeNull()
  })

  it('places every milestone of a full run at its offset, with all four intervals', () => {
    const model = timelineModel(runSyncRecovered, settings)
    expect(model.rows.map((row) => [row.kind, row.state, row.offset])).toEqual(
      runSyncRecovered.milestones.map((m) => [m.kind, 'recorded', m.offsetMillis]))
    expect(model.intervals.map((i) => i.id)).toEqual(['detection', 'failover', 'serviceRestored', 'recovery'])
    expect(model.axis.end).toBeGreaterThanOrEqual(2713.1272)
    expect(model.crashObserved).toBe(false)
  })

  it('leaves unrecorded milestones pending with no offset, never 0 (synthetic run-in-progress)', () => {
    const model = timelineModel(runInProgress, settings)
    const pending = model.rows.filter((row) => row.state !== 'recorded')
    expect(pending.map((row) => row.kind)).toEqual(['ELECTED', 'PROMOTED', 'SERVICE_RESTORED', 'OLD_PRIMARY_RECOVERED', 'DEMOTED', 'RESYNCHRONISED'])
    expect(pending.every((row) => row.offset === null)).toBe(true)
    expect(pending.slice(0, 3).every((row) => row.state === 'pending')).toBe(true)
    expect(pending.slice(3).every((row) => row.state === 'waiting')).toBe(true)
  })

  it('reaches at least to the end of the derived band while detection is pending', () => {
    const crashedOnly = { ...runInProgress, milestones: [runInProgress.milestones[0]] }
    expect(timelineModel(crashedOnly, settings).axis.end).toBeGreaterThanOrEqual(3200)
    expect(timelineModel(crashedOnly, { ...settings, heartbeatTimeoutMillis: null }).band).toBeNull()
  })

  it('marks unrecorded milestones of an interrupted run as not reached (synthetic)', () => {
    const rows = timelineModel(runInterrupted, settings).rows.filter((row) => row.state !== 'recorded')
    expect(rows.every((row) => row.state === 'not-reached' && row.note === 'Not reached: another crash interrupted this run.')).toBe(true)
  })

  it('marks the skipped milestones when the old primary returned first (synthetic)', () => {
    const model = timelineModel(runPrimaryReturned, settings)
    expect(model.rows.find((row) => row.kind === 'OLD_PRIMARY_RECOVERED').state).toBe('recorded')
    expect(model.rows.find((row) => row.kind === 'DETECTED').state).toBe('not-reached')
    expect(model.intervals).toEqual([])
  })

  it('flags a crash stamped OBSERVED', () => {
    expect(timelineModel({ ...runSyncRestored, crashSource: 'OBSERVED' }, settings).crashObserved).toBe(true)
  })
})

describe('axis and formats', () => {
  it('picks the smallest step with at most 8 ticks', () => {
    expect(axisTicks(2713.1)).toEqual({ end: 3000, step: 500, ticks: [0, 500, 1000, 1500, 2000, 2500, 3000] })
    expect(axisTicks(0).ticks.length).toBeGreaterThan(1)
    expect(axisTicks(11200).step).toBe(2000)
  })

  it('labels ticks in ms below one-second steps, in s otherwise', () => {
    expect(tickLabel(500, 500)).toBe('500 ms')
    expect(tickLabel(2000, 1000)).toBe('2 s')
  })

  it('formats ms with one decimal and keeps null', () => {
    expect(formatMillis(2238.1059)).toBe('2238.1 ms')
    expect(formatMillis(0)).toBe('0.0 ms')
    expect(formatMillis(null)).toBeNull()
    expect(formatMillis(undefined)).toBeNull()
  })

  it('turns enumerations into sentence-case words', () => {
    expect(words('ASYNCHRONOUS')).toBe('Asynchronous')
    expect(words('IN_PROGRESS')).toBe('In progress')
    expect(words(null)).toBeNull()
  })
})

describe('measurement', () => {
  it('reads the served label and kind, and the run value', () => {
    const m = measurement('detection', runSyncRecovered, glossary)
    expect(m).toMatchObject({ label: 'Detection time', kind: 'measured', text: '2238.1 ms', exact: '2238.1059 ms', reason: null })
  })

  it('reads data loss as lost of acknowledged, a measured 0 included', () => {
    expect(measurement('dataLoss', runSyncRestored, glossary).text).toBe('0 of 11')
  })

  it('gives a reason instead of a value for every null (synthetic run-primary-returned)', () => {
    for (const id of ['detection', 'failover', 'serviceRestored', 'outage', 'recovery', 'dataLoss']) {
      const m = measurement(id, runPrimaryReturned, glossary)
      expect(m.text).toBeNull()
      expect(m.reason).toBe('The old primary came back before anyone was chosen')
    }
  })

  it('says why recovery is missing', () => {
    expect(measurement('recovery', runSyncRestored, glossary).reason).toBe('The old primary has not been recovered yet')
    expect(measurement('recovery', null, glossary).reason).toBe('No failover since the reset')
  })
})
