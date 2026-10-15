import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { ApiError } from '@/services/api'
import overviewBefore from '@/test/fixtures/faulttolerance/overview-before-start.json'
import overviewAfterStart from '@/test/fixtures/faulttolerance/overview-after-start.json'
import statusRunning from '@/test/fixtures/faulttolerance/status-stream-running.json'
import statusLimitReached from '@/test/fixtures/faulttolerance/status-limit-reached.json'
import runSyncRecovered from '@/test/fixtures/faulttolerance/run-sync-recovered.json'
import limitReached from '@/test/fixtures/faulttolerance/error-409-limit-reached.json'
import busy from '@/test/fixtures/faulttolerance/error-409-busy.json'
import rateTooHigh from '@/test/fixtures/faulttolerance/error-400-rate-too-high.json'
import runInProgress from '@/test/fixtures/faulttolerance/synthetic/run-in-progress.json'
import statusFailoverStopped from '@/test/fixtures/faulttolerance/synthetic/status-failover-stream-stopped.json'
import statusStreamError from '@/test/fixtures/faulttolerance/synthetic/status-stream-error.json'
import { describeFaultProblem } from './faultToleranceApi'
import { controlsModel, situationText, uiState } from './faultToleranceModel'
import { FaultToleranceControls } from './FaultToleranceControls'

const { settings } = overviewBefore

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

function problemFor(body) {
  return describeFaultProblem(new ApiError({ status: body.status, title: body.title, detail: body.detail, errors: body.errors ?? null,
    originalError: { response: { status: body.status, data: body } } }))
}

function renderControls({ status, run = null, problem = null, locked = false, awaitingIdle = false }) {
  const state = uiState(status, run)
  const props = {
    status, settings, state, problem, awaitingIdle, pending: null,
    controls: controlsModel(status, run, { locked }),
    situation: situationText(state, status, run),
    onStart: vi.fn(), onStartStream: vi.fn(), onStopStream: vi.fn(), onMode: vi.fn(), onCrash: vi.fn(), onRecover: vi.fn(),
  }
  render(<MemoryRouter><FaultToleranceControls {...props} /></MemoryRouter>)
  return props
}

const button = (name) => screen.getByRole('button', { name })
const describedBy = (element) => document.getElementById(element.getAttribute('aria-describedby'))?.textContent

describe('FaultToleranceControls', () => {
  it('names every control', () => {
    renderControls({ status: overviewBefore.current })
    for (const name of ['Start experiment 8', 'Start stream', 'Stop stream', 'Crash primary', 'Recover old primary']) {
      expect(button(name)).toBeTruthy()
    }
    expect(screen.getByRole('radio', { name: 'Synchronous' })).toBeTruthy()
    expect(screen.getByRole('radio', { name: 'Asynchronous' })).toBeTruthy()
    expect(screen.getByRole('spinbutton', { name: 'Rate (updates per second)' })).toBeTruthy()
    expect(screen.getByRole('group', { name: 'Consistency mode' })).toBeTruthy()
  })

  it('not started: crash is not offered, with the visible "start the stream first" hint (item 10)', () => {
    renderControls({ status: overviewAfterStart.current })
    expect(button('Crash primary').disabled).toBe(true)
    expect(describedBy(button('Crash primary'))).toMatch(/^Start the update stream first/)
    expect(screen.getByText(/^Start the update stream first/)).toBeTruthy()
  })

  it('streaming: crash and stop are offered; the rate is fixed', () => {
    const props = renderControls({ status: statusRunning })
    expect(button('Crash primary').disabled).toBe(false)
    expect(button('Start stream').disabled).toBe(true)
    expect(screen.getByRole('spinbutton').disabled).toBe(true)
    fireEvent.click(button('Crash primary'))
    fireEvent.click(button('Stop stream'))
    expect(props.onCrash).toHaveBeenCalledTimes(1)
    expect(props.onStopStream).toHaveBeenCalledTimes(1)
    expect(screen.getByTestId('stream-summary').textContent).toContain('Running: synchronous updates, 4 per second.')
  })

  it('starts the stream at the served default rate, bounded by the served maximum', () => {
    const props = renderControls({ status: overviewBefore.current })
    const rate = screen.getByRole('spinbutton')
    expect(rate.value).toBe('4')
    expect(rate.getAttribute('max')).toBe('10')
    fireEvent.change(rate, { target: { value: '7' } })
    fireEvent.click(button('Start stream'))
    expect(props.onStartStream).toHaveBeenCalledWith(7)
  })

  it('switches the mode, says when it applies, and badges only the asynchronous delay as simulated', () => {
    const props = renderControls({ status: statusRunning })
    fireEvent.click(screen.getByRole('radio', { name: 'Asynchronous' }))
    expect(props.onMode).toHaveBeenCalledWith('ASYNCHRONOUS')
    expect(screen.getByText('A change applies from the next update, running or not.')).toBeTruthy()
    expect(screen.getByTestId('async-delay').textContent).toContain('Asynchronous updates wait 450 ms before the push to the backups')
    expect(screen.getAllByTestId('simulated-badge')).toHaveLength(1)
    expect(screen.getByRole('tooltip').textContent).toBe(settings.asyncDelayReason)
  })

  it('Q1: with the budget used, stream start is disabled with the served numbers and the Cluster page link', () => {
    renderControls({ status: statusLimitReached, run: runSyncRecovered })
    expect(button('Start stream').disabled).toBe(true)
    expect(describedBy(button('Start stream'))).toBe('Budget used: 400 of 400 updates this session. A cluster reset gives a fresh budget.')
    expect(screen.getByRole('link', { name: 'Reset the cluster on the Cluster page' }).getAttribute('href')).toBe('/cluster')
    expect(screen.getByTestId('budget').textContent).toBe('0 of 400 updates left this session')
    expect(screen.getByTestId('stream-summary').textContent).toContain('Stopped: the session budget is used.')
  })

  it('Q1: a 409 UPDATE_LIMIT_REACHED from a race still shows the backend text and the link', () => {
    renderControls({ status: statusRunning, problem: problemFor(limitReached) })
    const alert = screen.getByTestId('action-error')
    expect(alert.getAttribute('data-reason')).toBe('UPDATE_LIMIT_REACHED')
    expect(alert.textContent).toContain(limitReached.detail)
    expect(alert.querySelector('a').getAttribute('href')).toBe('/cluster')
  })

  it('shows 409 busy calmly, naming the action in progress', () => {
    renderControls({ status: statusRunning, problem: problemFor(busy) })
    const alert = screen.getByTestId('action-error')
    expect(alert.getAttribute('data-kind')).toBe('busy')
    expect(alert.className).toContain('border-warning')
    expect(alert.textContent).toContain('Running now: Start of the fault tolerance experiment')
  })

  it('lists 400 field errors by their page names', () => {
    renderControls({ status: overviewBefore.current, problem: problemFor(rateTooHigh) })
    expect(screen.getByText('Rate: must be between 1 and 10')).toBeTruthy()
  })

  it('C3: a failover waiting for an update says so, offers the stream, and withholds the crash (synthetic)', () => {
    renderControls({ status: statusFailoverStopped, run: runInProgress })
    const situation = screen.getByTestId('situation')
    expect(situation.getAttribute('data-state')).toBe('failover-waiting')
    expect(situation.textContent).toBe('The new primary only starts serving when the next update arrives. Start the update stream to finish the failover.')
    expect(button('Start stream').disabled).toBe(false)
    expect(button('Crash primary').disabled).toBe(true)
    expect(button('Recover old primary').disabled).toBe(false)
  })

  it('C3: with the budget used as well, it points at the cluster reset (synthetic)', () => {
    const used = JSON.parse(JSON.stringify(statusFailoverStopped))
    used.stream.remainingUpdates = 0
    renderControls({ status: used, run: runInProgress })
    expect(screen.getByTestId('situation').textContent)
      .toContain('The budget is used. Reset the cluster on the Cluster page.')
    expect(button('Start stream').disabled).toBe(true)
  })

  it('shows a stream stopped by an error and the update it gave up on (synthetic status-stream-error)', () => {
    renderControls({ status: statusStreamError })
    const summary = screen.getByTestId('stream-summary').textContent
    expect(summary).toContain('Stopped: stopped by an error.')
    expect(summary).toContain('Gave up1 (outlasted the retry window)')
  })

  it('C2: while locked after a timeout every action is disabled and the page says why', () => {
    renderControls({ status: statusRunning, locked: true, awaitingIdle: true })
    for (const name of ['Start experiment 8', 'Start stream', 'Stop stream', 'Crash primary', 'Recover old primary']) {
      expect(button(name).disabled).toBe(true)
    }
    expect(screen.getByRole('group', { name: 'Consistency mode' }).disabled).toBe(true)
    expect(screen.getByTestId('awaiting-idle').textContent).toBe('Buttons stay disabled until the backend has finished the request.')
  })
})
